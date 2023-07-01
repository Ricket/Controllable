package com.mrcrayfish.controllable.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mrcrayfish.controllable.Config;
import com.mrcrayfish.controllable.Controllable;
import com.mrcrayfish.controllable.Reference;
import com.mrcrayfish.controllable.client.gui.ControllerLayoutScreen;
import com.mrcrayfish.controllable.client.gui.navigation.BasicNavigationPoint;
import com.mrcrayfish.controllable.client.gui.navigation.NavigationPoint;
import com.mrcrayfish.controllable.client.gui.navigation.SlotNavigationPoint;
import com.mrcrayfish.controllable.client.gui.navigation.WidgetNavigationPoint;
import com.mrcrayfish.controllable.client.util.ReflectUtil;
import com.mrcrayfish.controllable.event.ControllerEvent;
import com.mrcrayfish.controllable.event.GatherNavigationPointsEvent;
import com.mrcrayfish.controllable.integration.JustEnoughItems;
import com.mrcrayfish.controllable.mixin.client.CreativeScreenMixin;
import com.mrcrayfish.controllable.mixin.client.RecipeBookGuiMixin;
import com.mrcrayfish.controllable.mixin.client.RecipeBookPageAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.SimpleSound;
import net.minecraft.client.gui.IGuiEventListener;
import net.minecraft.client.gui.INestedGuiEventHandler;
import net.minecraft.client.gui.advancements.AdvancementsScreen;
import net.minecraft.client.gui.recipebook.*;
import net.minecraft.client.gui.screen.IngameMenuScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.inventory.ContainerScreen;
import net.minecraft.client.gui.screen.inventory.CreativeScreen;
import net.minecraft.client.gui.screen.inventory.InventoryScreen;
import net.minecraft.client.gui.screen.inventory.MerchantScreen;
import net.minecraft.client.gui.widget.Widget;
import net.minecraft.client.gui.widget.list.AbstractList;
import net.minecraft.client.settings.PointOfView;
import net.minecraft.client.util.NativeUtil;
import net.minecraft.entity.item.BoatEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.container.RecipeBookContainer;
import net.minecraft.inventory.container.Slot;
import net.minecraft.item.ItemGroup;
import net.minecraft.network.play.client.CPlayerDiggingPacket;
import net.minecraft.util.*;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.ForgeHooksClient;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.InputUpdateEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.ObfuscationReflectionHelper;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

/**
 * Author: MrCrayfish
 */
@OnlyIn(Dist.CLIENT)
public class ControllerInput
{
    private static final ResourceLocation CURSOR_TEXTURE = new ResourceLocation(Reference.MOD_ID, "textures/gui/cursor.png");

    private int lastUse = 0;
    private boolean keyboardSneaking = false;
    private boolean sneaking = false;
    private boolean isFlying = false;
    private boolean nearSlot = false;
    private boolean moving = false;
    private boolean preventReset;
    private boolean ignoreInput;
    private double virtualMouseX;
    private double virtualMouseY;
    private int prevTargetMouseX;
    private int prevTargetMouseY;
    private int targetMouseX;
    private int targetMouseY;
    private double mouseSpeedX;
    private double mouseSpeedY;
    private boolean moved;
    private float targetPitch;
    private float targetYaw;
    private long lastMerchantScroll;

    private int dropCounter = -1;

    public double getVirtualMouseX()
    {
        return this.virtualMouseX;
    }

    public double getVirtualMouseY()
    {
        return this.virtualMouseY;
    }

    private void setControllerInUse()
    {
        this.lastUse = 100;
    }

    public boolean isControllerInUse()
    {
        return this.lastUse > 0;
    }

    public int getLastUse()
    {
        return this.lastUse;
    }

    public void resetLastUse()
    {
        if(!this.preventReset)
        {
            this.lastUse = 0;
        }
        this.preventReset = false;
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event)
    {
        if(event.phase == TickEvent.Phase.START)
        {
            this.prevTargetMouseX = this.targetMouseX;
            this.prevTargetMouseY = this.targetMouseY;

            if(this.lastUse > 0)
            {
                this.lastUse--;
            }

            Controller controller = Controllable.getController();
            if(controller == null)
                return;

            if((Math.abs(controller.getLTriggerValue()) >= 0.2F || Math.abs(controller.getRTriggerValue()) >= 0.2F) && !(Minecraft.getInstance().currentScreen instanceof ControllerLayoutScreen))
            {
                this.setControllerInUse();
            }

            Minecraft mc = Minecraft.getInstance();
            if(mc.mouseHelper.isMouseGrabbed())
                return;

            if(mc.currentScreen == null || mc.currentScreen instanceof ControllerLayoutScreen)
                return;

            float deadZone = (float) Math.min(1.0F, Config.CLIENT.options.deadZone.get() + 0.25F);

            /* Only need to run code if left thumb stick has input */
            boolean lastMoving = this.moving;
            float xAxis = Config.CLIENT.options.cursorThumbstick.get() == Thumbstick.LEFT ? controller.getLThumbStickXValue() : controller.getRThumbStickXValue();
            float yAxis = Config.CLIENT.options.cursorThumbstick.get() == Thumbstick.LEFT ? controller.getLThumbStickYValue() : controller.getRThumbStickYValue();
            this.moving = Math.abs(xAxis) >= deadZone || Math.abs(yAxis) >= deadZone;
            if(this.moving)
            {
                /* Updates the target mouse position when the initial thumb stick movement is
                 * detected. This fixes an issue when the user moves the cursor with the mouse then
                 * switching back to controller, the cursor would jump to old target mouse position. */
                if(!lastMoving)
                {
                    double mouseX = mc.mouseHelper.getMouseX();
                    double mouseY = mc.mouseHelper.getMouseY();
                    if(Controllable.getController() != null && Config.CLIENT.options.virtualMouse.get())
                    {
                        mouseX = this.virtualMouseX;
                        mouseY = this.virtualMouseY;
                    }
                    this.prevTargetMouseX = this.targetMouseX = (int) mouseX;
                    this.prevTargetMouseY = this.targetMouseY = (int) mouseY;
                }

                this.mouseSpeedX = Math.abs(xAxis) >= deadZone ? Math.signum(xAxis) * (Math.abs(xAxis) - deadZone) / (1.0F - deadZone) : 0.0F;
                this.mouseSpeedY = Math.abs(yAxis) >= deadZone ? Math.signum(yAxis) * (Math.abs(yAxis) - deadZone) / (1.0F - deadZone) : 0.0F;
                this.setControllerInUse();
            }

            if(this.lastUse <= 0)
            {
                this.mouseSpeedX = 0F;
                this.mouseSpeedY = 0F;
                return;
            }

            if(Math.abs(this.mouseSpeedX) > 0F || Math.abs(this.mouseSpeedY) > 0F)
            {
                double mouseSpeed = Config.CLIENT.options.mouseSpeed.get() * mc.getMainWindow().getGuiScaleFactor();

                // When hovering over slots, slows down the mouse speed to make it easier
                if(mc.currentScreen instanceof ContainerScreen)
                {
                    ContainerScreen screen = (ContainerScreen) mc.currentScreen;
                    if(screen.getSlotUnderMouse() != null)
                    {
                        mouseSpeed *= Config.CLIENT.options.hoverModifier.get();
                    }
                }

                double mouseX = this.virtualMouseX * (double) mc.getMainWindow().getScaledWidth() / (double) mc.getMainWindow().getWidth();
                double mouseY = this.virtualMouseY * (double) mc.getMainWindow().getScaledHeight() / (double) mc.getMainWindow().getHeight();
                List<IGuiEventListener> eventListeners = new ArrayList<>(mc.currentScreen.getEventListeners());
                if(mc.currentScreen instanceof IRecipeShownListener)
                {
                    RecipeBookGui recipeBook = ((IRecipeShownListener) mc.currentScreen).getRecipeGui();
                    if(recipeBook.isVisible())
                    {
                        eventListeners.add(((RecipeBookGuiMixin) recipeBook).getToggleRecipesBtn());
                        eventListeners.addAll(((RecipeBookGuiMixin) recipeBook).getRecipeTabs());
                        RecipeBookPage recipeBookPage = ((RecipeBookGuiMixin) recipeBook).getRecipeBookPage();
                        eventListeners.addAll(((RecipeBookPageAccessor) recipeBookPage).getButtons());
                        eventListeners.add(((RecipeBookPageAccessor) recipeBookPage).getForwardButton());
                        eventListeners.add(((RecipeBookPageAccessor) recipeBookPage).getBackButton());
                    }
                }
                IGuiEventListener hoveredListener = eventListeners.stream().filter(o -> o != null && o.isMouseOver(mouseX, mouseY)).findFirst().orElse(null);
                if(hoveredListener instanceof AbstractList<?>)
                {
                    AbstractList<?> list = (AbstractList<?>) hoveredListener;
                    hoveredListener = null;
                    int count = list.getEventListeners().size();
                    for(int i = 0; i < count; i++)
                    {
                        int rowTop = ReflectUtil.getAbstractListRowTop(list, i);
                        int rowBottom = ReflectUtil.getAbstractListRowBottom(list, i);
                        if(rowTop < list.getTop() && rowBottom > list.getBottom()) // Is visible
                            continue;

                        AbstractList.AbstractListEntry<?> entry = list.getEventListeners().get(i);
                        if(!(entry instanceof INestedGuiEventHandler))
                            continue;

                        INestedGuiEventHandler handler = (INestedGuiEventHandler) entry;
                        IGuiEventListener hovered = handler.getEventListeners().stream().filter(o -> o != null && o.isMouseOver(mouseX, mouseY)).findFirst().orElse(null);
                        if(hovered == null)
                            continue;

                        hoveredListener = hovered;
                        break;
                    }
                }
                if(hoveredListener != null)
                {
                    mouseSpeed *= Config.CLIENT.options.hoverModifier.get();
                }

                this.targetMouseX += mouseSpeed * this.mouseSpeedX;
                this.targetMouseX = MathHelper.clamp(this.targetMouseX, 0, mc.getMainWindow().getWidth());
                this.targetMouseY += mouseSpeed * this.mouseSpeedY;
                this.targetMouseY = MathHelper.clamp(this.targetMouseY, 0, mc.getMainWindow().getHeight());
                this.setControllerInUse();
                this.moved = true;
            }

            this.moveMouseToClosestSlot(this.moving, mc.currentScreen);

            if(mc.currentScreen instanceof CreativeScreen)
            {
                this.handleCreativeScrolling((CreativeScreen) mc.currentScreen, controller);
            }

            if(Config.CLIENT.options.virtualMouse.get() && (this.targetMouseX != this.prevTargetMouseX || this.targetMouseY != this.prevTargetMouseY))
            {
                this.performMouseDrag(this.virtualMouseX, this.virtualMouseY, this.targetMouseX - this.prevTargetMouseX, this.targetMouseY - this.prevTargetMouseY);
            }
        }
    }

    @SubscribeEvent(receiveCanceled = true)
    public void onScreenInit(GuiOpenEvent event)
    {
        Minecraft mc = Minecraft.getInstance();
        if(mc.currentScreen == null)
        {
            this.nearSlot = false;
            this.moved = false;
            this.mouseSpeedX = 0.0;
            this.mouseSpeedY = 0.0;
            this.virtualMouseX = this.targetMouseX = this.prevTargetMouseX = (int) (mc.getMainWindow().getWidth() / 2F);
            this.virtualMouseY = this.targetMouseY = this.prevTargetMouseY = (int) (mc.getMainWindow().getHeight() / 2F);
        }
    }

    @SubscribeEvent(receiveCanceled = true)
    public void onRenderScreen(GuiScreenEvent.DrawScreenEvent.Pre event)
    {
        /* Makes the cursor movement appear smooth between ticks. This will only run if the target
         * mouse position is different to the previous tick's position. This allows for the mouse
         * to still be used as input. */
        Minecraft mc = Minecraft.getInstance();
        if(mc.currentScreen != null && (this.targetMouseX != this.prevTargetMouseX || this.targetMouseY != this.prevTargetMouseY))
        {
            if(!(mc.currentScreen instanceof ControllerLayoutScreen))
            {
                float partialTicks = Minecraft.getInstance().getRenderPartialTicks();
                double mouseX = (this.prevTargetMouseX + (this.targetMouseX - this.prevTargetMouseX) * partialTicks + 0.5);
                double mouseY = (this.prevTargetMouseY + (this.targetMouseY - this.prevTargetMouseY) * partialTicks + 0.5);
                this.setMousePosition(mouseX, mouseY);
            }
        }
    }

    private void performMouseDrag(double mouseX, double mouseY, double dragX, double dragY)
    {
        if(Controllable.getController() != null)
        {
            Minecraft mc = Minecraft.getInstance();
            Screen screen = mc.currentScreen;
            if(screen != null)
            {
                if(mc.loadingGui == null)
                {
                    double finalMouseX = mouseX * (double) mc.getMainWindow().getScaledWidth() / (double) mc.getMainWindow().getWidth();
                    double finalMouseY = mouseY * (double) mc.getMainWindow().getScaledHeight() / (double) mc.getMainWindow().getHeight();
                    Screen.wrapScreenError(() -> screen.mouseMoved(finalMouseX, finalMouseY), "mouseMoved event handler", ((IGuiEventListener) screen).getClass().getCanonicalName());
                    if(mc.mouseHelper.activeButton != -1 && mc.mouseHelper.eventTime > 0.0D)
                    {
                        Screen.wrapScreenError(() ->
                        {
                            double finalDragX = dragX * (double) mc.getMainWindow().getScaledWidth() / (double) mc.getMainWindow().getWidth();
                            double finalDragY = dragY * (double) mc.getMainWindow().getScaledHeight() / (double) mc.getMainWindow().getHeight();
                            if(net.minecraftforge.client.ForgeHooksClient.onGuiMouseDragPre(screen, finalMouseX, finalMouseY, mc.mouseHelper.activeButton, finalDragX, finalDragY))
                            {
                                return;
                            }
                            if(((IGuiEventListener) screen).mouseDragged(finalMouseX, finalMouseY, mc.mouseHelper.activeButton, finalDragX, finalDragY))
                            {
                                return;
                            }
                            net.minecraftforge.client.ForgeHooksClient.onGuiMouseDragPost(screen, finalMouseX, finalMouseY, mc.mouseHelper.activeButton, finalDragX, finalDragY);
                        }, "mouseDragged event handler", ((IGuiEventListener) screen).getClass().getCanonicalName());
                    }
                }
            }
        }
    }

    @SubscribeEvent(receiveCanceled = true)
    public void onRenderScreen(GuiScreenEvent.DrawScreenEvent.Post event)
    {
        if(Controllable.getController() != null && Config.CLIENT.options.virtualMouse.get() && lastUse > 0)
        {
            RenderSystem.pushMatrix();
            {
                CursorType type = Config.CLIENT.options.cursorType.get();
                Minecraft minecraft = Minecraft.getInstance();
                if(minecraft.player == null || (minecraft.player.inventory.getItemStack().isEmpty() || type == CursorType.CONSOLE))
                {
                    double mouseX = (this.prevTargetMouseX + (this.targetMouseX - this.prevTargetMouseX) * Minecraft.getInstance().getRenderPartialTicks());
                    double mouseY = (this.prevTargetMouseY + (this.targetMouseY - this.prevTargetMouseY) * Minecraft.getInstance().getRenderPartialTicks());
                    RenderSystem.translated(mouseX / minecraft.getMainWindow().getGuiScaleFactor(), mouseY / minecraft.getMainWindow().getGuiScaleFactor(), 500);
                    RenderSystem.color3f(1.0F, 1.0F, 1.0F);
                    RenderSystem.disableLighting();
                    event.getGui().getMinecraft().getTextureManager().bindTexture(CURSOR_TEXTURE);
                    if(type == CursorType.CONSOLE)
                    {
                        RenderSystem.scaled(0.5, 0.5, 0.5);
                    }
                    Screen.blit(event.getMatrixStack(), -8, -8, 16, 16, this.nearSlot ? 16 : 0, type.ordinal() * 16, 16, 16, 32, CursorType.values().length * 16);
                }
            }
            RenderSystem.popMatrix();
        }
    }

    @SubscribeEvent
    public void onRender(TickEvent.RenderTickEvent event)
    {
        Controller controller = Controllable.getController();
        if(controller == null)
            return;

        if(event.phase == TickEvent.Phase.END)
            return;

        Minecraft mc = Minecraft.getInstance();
        double mouseX = this.virtualMouseX * (double) mc.getMainWindow().getScaledWidth() / (double) mc.getMainWindow().getWidth();
        double mouseY = this.virtualMouseY * (double) mc.getMainWindow().getScaledHeight() / (double) mc.getMainWindow().getHeight();
        if(mc.currentScreen != null && this.lastUse > 0)
        {
            if(mc.currentScreen instanceof MerchantScreen)
            {
                this.handleMerchantScrolling((MerchantScreen) mc.currentScreen, controller);
                return;
            }
            IGuiEventListener hoveredListener = mc.currentScreen.getEventListeners().stream().filter(o -> o.isMouseOver(mouseX, mouseY)).findFirst().orElse(null);
            if(hoveredListener instanceof AbstractList<?>)
            {
                this.handleListScrolling((AbstractList<?>) hoveredListener, controller);
            }
        }

        PlayerEntity player = mc.player;
        if(player == null)
            return;

        if(mc.currentScreen == null && (this.targetYaw != 0F || this.targetPitch != 0F))
        {
            float elapsedTicks = Minecraft.getInstance().getTickLength();
            if(!RadialMenuHandler.instance().isVisible())
            {
                player.rotateTowards((this.targetYaw / 0.15) * elapsedTicks, (this.targetPitch / 0.15) * (Config.CLIENT.options.invertLook.get() ? -1 : 1) * elapsedTicks);
            }
            if(player.getRidingEntity() != null)
            {
                player.getRidingEntity().applyOrientationToEntity(player);
            }
        }
    }

    @SubscribeEvent
    public void onRender(TickEvent.ClientTickEvent event)
    {
        if(event.phase == TickEvent.Phase.END)
            return;

        this.targetYaw = 0F;
        this.targetPitch = 0F;

        Minecraft mc = Minecraft.getInstance();
        PlayerEntity player = mc.player;
        if(player == null)
            return;

        Controller controller = Controllable.getController();
        if(controller == null)
            return;

        if(mc.currentScreen == null)
        {
            float deadZone = Config.CLIENT.options.deadZone.get().floatValue();

            /* Handles rotating the yaw of player */
            if(Math.abs(controller.getRThumbStickXValue()) >= deadZone)
            {
                this.setControllerInUse();
                double rotationSpeed = Config.CLIENT.options.rotationSpeed.get();
                ControllerEvent.Turn turnEvent = new ControllerEvent.Turn(controller, (float) rotationSpeed, (float) rotationSpeed * 0.75F);
                if(!MinecraftForge.EVENT_BUS.post(turnEvent))
                {
                    float deadZoneTrimX = (controller.getRThumbStickXValue() > 0 ? 1 : -1) * deadZone;
                    this.targetYaw = (turnEvent.getYawSpeed() * (controller.getRThumbStickXValue() - deadZoneTrimX) / (1.0F - deadZone)) * 0.33F;
                }
            }

            if(Math.abs(controller.getRThumbStickYValue()) >= deadZone)
            {
                this.setControllerInUse();
                double rotationSpeed = Config.CLIENT.options.rotationSpeed.get();
                ControllerEvent.Turn turnEvent = new ControllerEvent.Turn(controller, (float) rotationSpeed, (float) rotationSpeed * 0.75F);
                if(!MinecraftForge.EVENT_BUS.post(turnEvent))
                {
                    float deadZoneTrimY = (controller.getRThumbStickYValue() > 0 ? 1 : -1) * deadZone;
                    this.targetPitch = (turnEvent.getPitchSpeed() * (controller.getRThumbStickYValue() - deadZoneTrimY) / (1.0F - deadZone)) * 0.33F;
                }
            }
        }

        if(mc.currentScreen == null)
        {
            if(ButtonBindings.DROP_ITEM.isButtonDown())
            {
                this.setControllerInUse();
                this.dropCounter++;
            }
        }

        if(this.dropCounter > 20)
        {
            if (!mc.player.isSpectator())
            {
                mc.player.drop(true);
            }
            this.dropCounter = 0;
        }
        else if(this.dropCounter > 0 && !ButtonBindings.DROP_ITEM.isButtonDown())
        {
            if (!mc.player.isSpectator())
            {
                mc.player.drop(false);
            }
            this.dropCounter = 0;
        }
    }

    @SubscribeEvent
    public void onOpenScreen(GuiOpenEvent event)
    {
        Minecraft mc = Minecraft.getInstance();
        if(Config.SERVER.restrictToController.get() && mc.world != null && !this.isControllerInUse())
        {
            if(event.getGui() instanceof ContainerScreen)
            {
                event.setCanceled(true);
            }
        }
    }

    @SubscribeEvent
    public void onMouseClicked(InputEvent.RawMouseEvent event)
    {
        Minecraft mc = Minecraft.getInstance();
        if(mc.world != null && (mc.currentScreen == null || mc.currentScreen instanceof ContainerScreen))
        {
            if(Config.SERVER.restrictToController.get())
            {
                event.setCanceled(true);
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onInputUpdate(InputUpdateEvent event)
    {
        PlayerEntity player = Minecraft.getInstance().player;
        if(player == null)
            return;

        if(Config.SERVER.restrictToController.get())
        {
            MovementInput input = event.getMovementInput();
            input.moveStrafe = 0F;
            input.moveForward = 0F;
            input.forwardKeyDown = false;
            input.backKeyDown = false;
            input.leftKeyDown = false;
            input.rightKeyDown = false;
            input.jump = false;
            input.sneaking = false;
        }

        Controller controller = Controllable.getController();
        if(controller == null)
        {
            return;
        }

        Minecraft mc = Minecraft.getInstance();

        if(this.keyboardSneaking && !mc.gameSettings.keyBindSneak.isKeyDown())
        {
            this.sneaking = false;
            this.keyboardSneaking = false;
        }

        if(Config.CLIENT.options.sneakMode.get() == SneakMode.HOLD)
        {
            this.sneaking = ButtonBindings.SNEAK.isButtonDown();
        }

        if(mc.gameSettings.keyBindSneak.isKeyDown())
        {
            this.sneaking = true;
            this.keyboardSneaking = true;
        }

        if(mc.player.abilities.isFlying || mc.player.isPassenger())
        {
            this.sneaking = mc.gameSettings.keyBindSneak.isKeyDown();
            this.sneaking |= ButtonBindings.SNEAK.isButtonDown();
            if(ButtonBindings.SNEAK.isButtonDown())
            {
                this.setControllerInUse();
            }
            this.isFlying = true;
        }
        else if(this.isFlying)
        {
            this.isFlying = false;
        }

        event.getMovementInput().sneaking = this.sneaking;

        if(mc.currentScreen == null)
        {
            if((!RadialMenuHandler.instance().isVisible() || Config.CLIENT.options.radialThumbstick.get() != Thumbstick.LEFT) && !MinecraftForge.EVENT_BUS.post(new ControllerEvent.Move(controller)))
            {
                float deadZone = Config.CLIENT.options.deadZone.get().floatValue();

                if(Math.abs(controller.getLThumbStickYValue()) >= deadZone)
                {
                    this.setControllerInUse();
                    int dir = controller.getLThumbStickYValue() > 0.0F ? -1 : 1;
                    event.getMovementInput().forwardKeyDown = dir > 0;
                    event.getMovementInput().backKeyDown = dir < 0;
                    event.getMovementInput().moveForward = dir * MathHelper.clamp((Math.abs(controller.getLThumbStickYValue()) - deadZone) / (1.0F - deadZone), 0.0F, 1.0F);

                    if(event.getMovementInput().sneaking)
                    {
                        event.getMovementInput().moveForward *= 0.3D;
                    }
                }

                if(player.getRidingEntity() instanceof BoatEntity)
                {
                    deadZone = 0.5F;
                }

                if(Math.abs(controller.getLThumbStickXValue()) >= deadZone)
                {
                    this.setControllerInUse();
                    int dir = controller.getLThumbStickXValue() > 0.0F ? -1 : 1;
                    event.getMovementInput().rightKeyDown = dir < 0;
                    event.getMovementInput().leftKeyDown = dir > 0;
                    event.getMovementInput().moveStrafe = dir * MathHelper.clamp((Math.abs(controller.getLThumbStickXValue()) - deadZone) / (1.0F - deadZone), 0.0F, 1.0F);

                    if(event.getMovementInput().sneaking)
                    {
                        event.getMovementInput().moveStrafe *= 0.3D;
                    }
                }
            }

            if(this.ignoreInput && !ButtonBindings.JUMP.isButtonDown())
            {
                this.ignoreInput = false;
            }

            if(ButtonBindings.JUMP.isButtonDown() && !this.ignoreInput)
            {
                event.getMovementInput().jump = true;
            }
        }

        if(ButtonBindings.USE_ITEM.isButtonDown() && mc.rightClickDelayTimer == 0 && !mc.player.isHandActive())
        {
            mc.rightClickMouse();
        }
    }

    public void handleButtonInput(Controller controller, int button, boolean state, boolean virtual)
    {
        if(controller == null)
            return;

        this.setControllerInUse();

        /* We don't send event for buttons that are not bound.
         * This can happen when using the radial menu. */
        if(button != -1)
        {
            ControllerEvent.ButtonInput eventInput = new ControllerEvent.ButtonInput(controller, button, state);
            if(MinecraftForge.EVENT_BUS.post(eventInput))
                return;

            button = eventInput.getModifiedButton();
            ButtonBinding.setButtonState(button, state);
        }

        ControllerEvent.Button event = new ControllerEvent.Button(controller);
        if(MinecraftForge.EVENT_BUS.post(event))
            return;

        Minecraft mc = Minecraft.getInstance();
        if(state)
        {
            if(ButtonBindings.FULLSCREEN.isButtonPressed())
            {
                mc.getMainWindow().toggleFullscreen();
                mc.gameSettings.fullscreen = mc.getMainWindow().isFullscreen();
                mc.gameSettings.saveOptions();
            }
            else if(ButtonBindings.SCREENSHOT.isButtonPressed())
            {
                if(mc.world != null)
                {
                    ScreenShotHelper.saveScreenshot(mc.gameDir, mc.getMainWindow().getFramebufferWidth(), mc.getMainWindow().getFramebufferHeight(), mc.getFramebuffer(), (textComponent) -> {
                        mc.execute(() -> mc.ingameGUI.getChatGUI().printChatMessage(textComponent));
                    });
                }
            }
            else if(mc.currentScreen == null)
            {
                if(ButtonBindings.INVENTORY.isButtonPressed())
                {
                    if(mc.playerController.isRidingHorse())
                    {
                        mc.player.sendHorseInventory();
                    }
                    else
                    {
                        mc.getTutorial().openInventory();
                        mc.displayGuiScreen(new InventoryScreen(mc.player));
                    }
                }
                else if(ButtonBindings.SPRINT.isButtonPressed())
                {
                    if(mc.player != null)
                    {
                        mc.player.setSprinting(true);
                    }
                }
                else if(ButtonBindings.SNEAK.isButtonPressed())
                {
                    if(mc.player != null && !mc.player.abilities.isFlying && !this.isFlying && !mc.player.isPassenger())
                    {
                        if(Config.CLIENT.options.sneakMode.get() == SneakMode.TOGGLE)
                        {
                            this.sneaking = !this.sneaking;
                        }
                    }
                }
                else if(ButtonBindings.SCROLL_RIGHT.isButtonPressed())
                {
                    if(mc.player != null)
                    {
                        mc.player.inventory.changeCurrentItem(-1);
                    }
                }
                else if(ButtonBindings.SCROLL_LEFT.isButtonPressed())
                {
                    if(mc.player != null)
                    {
                        mc.player.inventory.changeCurrentItem(1);
                    }
                }
                else if(ButtonBindings.SWAP_HANDS.isButtonPressed())
                {
                    if(mc.player != null && !mc.player.isSpectator() && mc.getConnection() != null)
                    {
                        mc.getConnection().sendPacket(new CPlayerDiggingPacket(CPlayerDiggingPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ZERO, Direction.DOWN));
                    }
                }
                else if(ButtonBindings.TOGGLE_PERSPECTIVE.isButtonPressed())
                {
                    cycleThirdPersonView();
                }
                else if(ButtonBindings.PAUSE_GAME.isButtonPressed())
                {
                    if(mc.player != null)
                    {
                        mc.displayInGameMenu(false);
                    }
                }
                else if(ButtonBindings.ADVANCEMENTS.isButtonPressed())
                {
                    if(mc.player != null)
                    {
                        mc.displayGuiScreen(new AdvancementsScreen(mc.player.connection.getAdvancementManager()));
                    }
                }
                else if(ButtonBindings.CINEMATIC_CAMERA.isButtonPressed())
                {
                    if(mc.player != null)
                    {
                        mc.gameSettings.smoothCamera = !mc.gameSettings.smoothCamera;
                    }
                }
                else if(ButtonBindings.DEBUG_INFO.isButtonPressed())
                {
                    mc.gameSettings.showDebugInfo = !mc.gameSettings.showDebugInfo;
                }
                else if(ButtonBindings.RADIAL_MENU.isButtonPressed() && !virtual)
                {
                    RadialMenuHandler.instance().interact();
                }
                else if(mc.player != null)
                {
                    for(int i = 0; i < 9; i++)
                    {
                        if(ButtonBindings.HOTBAR_SLOTS[i].isButtonPressed())
                        {
                            mc.player.inventory.currentItem = i;
                            return;
                        }
                    }

                    if(!mc.player.isHandActive())
                    {
                        if(ButtonBindings.ATTACK.isButtonPressed())
                        {
                            mc.clickMouse();
                        }
                        else if(ButtonBindings.USE_ITEM.isButtonPressed())
                        {
                            mc.rightClickMouse();
                        }
                        else if(ButtonBindings.PICK_BLOCK.isButtonPressed())
                        {
                            mc.middleClickMouse();
                        }
                    }
                }
            }
            else
            {
                if(ButtonBindings.INVENTORY.isButtonPressed())
                {
                    if(mc.player != null)
                    {
                        mc.player.closeScreen();
                    }
                }
                else if(ButtonBindings.PREVIOUS_CREATIVE_TAB.isButtonPressed())
                {
                    if(mc.currentScreen instanceof CreativeScreen)
                    {
                        this.scrollCreativeTabs((CreativeScreen) mc.currentScreen, 1);
                        Minecraft.getInstance().getSoundHandler().play(SimpleSound.master(SoundEvents.UI_BUTTON_CLICK, 1.0F));
                    }
                    else if(mc.currentScreen instanceof IRecipeShownListener)
                    {
                        IRecipeShownListener recipeShownListener = (IRecipeShownListener) mc.currentScreen;
                        this.scrollRecipePage(recipeShownListener.getRecipeGui(), 1);
                    }
                }
                else if(ButtonBindings.NEXT_CREATIVE_TAB.isButtonPressed())
                {
                    if(mc.currentScreen instanceof CreativeScreen)
                    {
                        this.scrollCreativeTabs((CreativeScreen) mc.currentScreen, -1);
                        Minecraft.getInstance().getSoundHandler().play(SimpleSound.master(SoundEvents.UI_BUTTON_CLICK, 1.0F));
                    }
                    else if(mc.currentScreen instanceof IRecipeShownListener)
                    {
                        IRecipeShownListener recipeShownListener = (IRecipeShownListener) mc.currentScreen;
                        this.scrollRecipePage(recipeShownListener.getRecipeGui(), -1);
                    }
                }
                else if(ButtonBindings.NEXT_RECIPE_TAB.isButtonPressed())
                {
                    if(mc.currentScreen instanceof IRecipeShownListener)
                    {
                        IRecipeShownListener recipeShownListener = (IRecipeShownListener) mc.currentScreen;
                        this.scrollRecipeTab(recipeShownListener.getRecipeGui(), -1);
                    }
                }
                else if(ButtonBindings.PREVIOUS_RECIPE_TAB.isButtonPressed())
                {
                    if(mc.currentScreen instanceof IRecipeShownListener)
                    {
                        IRecipeShownListener recipeShownListener = (IRecipeShownListener) mc.currentScreen;
                        this.scrollRecipeTab(recipeShownListener.getRecipeGui(), 1);
                    }
                }
                else if(ButtonBindings.PAUSE_GAME.isButtonPressed())
                {
                    if(mc.currentScreen instanceof IngameMenuScreen)
                    {
                        mc.displayGuiScreen(null);
                    }
                }
                else if(ButtonBindings.NAVIGATE_UP.isButtonPressed())
                {
                    this.navigateMouse(mc.currentScreen, Navigate.UP);
                }
                else if(ButtonBindings.NAVIGATE_DOWN.isButtonPressed())
                {
                    this.navigateMouse(mc.currentScreen, Navigate.DOWN);
                }
                else if(ButtonBindings.NAVIGATE_LEFT.isButtonPressed())
                {
                    this.navigateMouse(mc.currentScreen, Navigate.LEFT);
                }
                else if(ButtonBindings.NAVIGATE_RIGHT.isButtonPressed())
                {
                    this.navigateMouse(mc.currentScreen, Navigate.RIGHT);
                }
                else if(button == ButtonBindings.PICKUP_ITEM.getButton())
                {
                    invokeMouseClick(mc.currentScreen, 0);

                    if(mc.currentScreen == null)
                    {
                        this.ignoreInput = true;
                    }

                    if(Config.CLIENT.options.quickCraft.get())
                    {
                        this.craftRecipeBookItem();
                    }
                }
                else if(button == ButtonBindings.SPLIT_STACK.getButton())
                {
                    invokeMouseClick(mc.currentScreen, 1);
                }
                else if(button == ButtonBindings.QUICK_MOVE.getButton() && mc.player != null)
                {
                    if(mc.player.inventory.getItemStack().isEmpty())
                    {
                        invokeMouseClick(mc.currentScreen, 0);
                    }
                    else
                    {
                        invokeMouseReleased(mc.currentScreen, 1);
                    }
                }
            }
        }
        else
        {
            if(mc.currentScreen == null)
            {

            }
            else
            {
                if(button == ButtonBindings.PICKUP_ITEM.getButton())
                {
                    invokeMouseReleased(mc.currentScreen, 0);
                }
                else if(button == ButtonBindings.SPLIT_STACK.getButton())
                {
                    invokeMouseReleased(mc.currentScreen, 1);
                }
            }
        }
    }

    /**
     * Cycles the third person view. Minecraft doesn't have this code in a convenient method.
     */
    private void cycleThirdPersonView()
    {
        Minecraft mc = Minecraft.getInstance();
        PointOfView pointOfView = mc.gameSettings.getPointOfView();
        mc.gameSettings.setPointOfView(pointOfView.func_243194_c());
        if(pointOfView.func_243192_a() != mc.gameSettings.getPointOfView().func_243192_a())
        {
            mc.gameRenderer.loadEntityShader(mc.gameSettings.getPointOfView().func_243192_a() ? mc.getRenderViewEntity() : null);
        }
    }

    private void scrollCreativeTabs(CreativeScreen creative, int dir)
    {
        this.setControllerInUse();

        try
        {
            Method method = ObfuscationReflectionHelper.findMethod(CreativeScreen.class, "func_147050_b", ItemGroup.class);
            method.setAccessible(true);
            if(dir > 0)
            {
                if(creative.getSelectedTabIndex() < ItemGroup.GROUPS.length - 1)
                {
                    method.invoke(creative, ItemGroup.GROUPS[creative.getSelectedTabIndex() + 1]);
                }
            }
            else if(dir < 0)
            {
                if(creative.getSelectedTabIndex() > 0)
                {
                    method.invoke(creative, ItemGroup.GROUPS[creative.getSelectedTabIndex() - 1]);
                }
            }
        }
        catch(IllegalAccessException | InvocationTargetException e)
        {
            e.printStackTrace();
        }
    }

    private void scrollRecipeTab(RecipeBookGui recipeBook, int dir)
    {
        if(!recipeBook.isVisible())
            return;
        RecipeBookGuiMixin recipeBookMixin = ((RecipeBookGuiMixin) recipeBook);
        RecipeTabToggleWidget currentTab = recipeBookMixin.getCurrentTab();
        List<RecipeTabToggleWidget> tabs = recipeBookMixin.getRecipeTabs();
        int nextTabIndex = tabs.indexOf(currentTab) + dir;
        if(nextTabIndex >= 0 && nextTabIndex < tabs.size())
        {
            RecipeTabToggleWidget newTab = tabs.get(nextTabIndex);
            currentTab.setStateTriggered(false);
            recipeBookMixin.setCurrentTab(newTab);
            newTab.setStateTriggered(true);
            recipeBookMixin.invokeUpdateCollections(true);
            Minecraft.getInstance().getSoundHandler().play(SimpleSound.master(SoundEvents.UI_BUTTON_CLICK, 1.0F));
        }
    }

    private void scrollRecipePage(RecipeBookGui recipeBook, int dir)
    {
        if(!recipeBook.isVisible())
            return;
        RecipeBookPageAccessor page = (RecipeBookPageAccessor)((RecipeBookGuiMixin) recipeBook).getRecipeBookPage();
        if(dir > 0 && page.getForwardButton().visible || dir < 0 && page.getBackButton().visible)
        {
            int currentPage = page.getCurrentPage();
            page.setCurrentPage(currentPage + dir);
            page.invokeUpdateButtonsForPage();
            Minecraft.getInstance().getSoundHandler().play(SimpleSound.master(SoundEvents.UI_BUTTON_CLICK, 1.0F));
        }
    }

    private void navigateMouse(Screen screen, Navigate navigate)
    {
        Minecraft mc = Minecraft.getInstance();
        int mouseX = (int) (this.targetMouseX * (double) mc.getMainWindow().getScaledWidth() / (double) mc.getMainWindow().getWidth());
        int mouseY = (int) (this.targetMouseY * (double) mc.getMainWindow().getScaledHeight() / (double) mc.getMainWindow().getHeight());

        List<NavigationPoint> points = this.gatherNavigationPoints(screen);

        // Gather any extra navigation points from event
        GatherNavigationPointsEvent event = new GatherNavigationPointsEvent();
        MinecraftForge.EVENT_BUS.post(event);
        points.addAll(event.getPoints());

        // Get only the points that are in the target direction
        List<NavigationPoint> targetPoints = points.stream().filter(point -> navigate.getPredicate().test(point, mouseX, mouseY)).collect(Collectors.toList());
        if(targetPoints.isEmpty())
            return;

        Vector3d mousePos = new Vector3d(mouseX, mouseY, 0);
        Optional<NavigationPoint> minimumPointOptional = targetPoints.stream().min(navigate.getMinComparator(mouseX, mouseY));
        double minimumDelta = navigate.getKeyExtractor().apply(minimumPointOptional.get(), mousePos) + 10;
        Optional<NavigationPoint> targetPointOptional = targetPoints.stream().filter(point -> navigate.getKeyExtractor().apply(point, mousePos) <= minimumDelta).min(Comparator.comparing(p -> p.distanceTo(mouseX, mouseY)));
        if(targetPointOptional.isPresent())
        {
            this.performMouseDrag(this.targetMouseX, this.targetMouseY, 0, 0);
            NavigationPoint targetPoint = targetPointOptional.get();
            int screenX = (int) (targetPoint.getX() / ((double) mc.getMainWindow().getScaledWidth() / (double) mc.getMainWindow().getWidth()));
            int screenY = (int) (targetPoint.getY() / ((double) mc.getMainWindow().getScaledHeight() / (double) mc.getMainWindow().getHeight()));
            double lastTargetMouseX = this.targetMouseX;
            double lastTargetMouseY = this.targetMouseY;
            this.targetMouseX = this.prevTargetMouseX = screenX;
            this.targetMouseY = this.prevTargetMouseY = screenY;
            this.setMousePosition(screenX, screenY);
            if(Config.CLIENT.options.uiSounds.get())
            {
                mc.getSoundHandler().play(SimpleSound.master(SoundEvents.ENTITY_ITEM_PICKUP, 2.0F));
            }
            this.performMouseDrag(this.targetMouseX, this.targetMouseY, screenX - lastTargetMouseX, screenY - lastTargetMouseY);
        }
    }

    private List<NavigationPoint> gatherNavigationPoints(Screen screen)
    {
        List<NavigationPoint> points = new ArrayList<>();

        if(screen instanceof ContainerScreen)
        {
            ContainerScreen containerScreen = (ContainerScreen) screen;
            int guiLeft = containerScreen.getGuiLeft();
            int guiTop = containerScreen.getGuiTop();
            for(Slot slot : containerScreen.getContainer().inventorySlots)
            {
                if(containerScreen.getSlotUnderMouse() == slot)
                    continue;
                int posX = guiLeft + slot.xPos + 8;
                int posY = guiTop + slot.yPos + 8;
                points.add(new SlotNavigationPoint(posX, posY, slot));
            }
        }

        List<Widget> widgets = new ArrayList<>();
        for(IGuiEventListener listener : screen.getEventListeners())
        {
            if(listener instanceof Widget)
            {
                Widget widget = (Widget) listener;
                if(widget.active && widget.visible)
                {
                    widgets.add((Widget) listener);
                }
            }
            else if(listener instanceof AbstractList<?>)
            {
                AbstractList<?> list = (AbstractList<?>) listener;
                int count = list.getEventListeners().size();
                for(int i = 0; i < count; i++)
                {
                    int rowTop = ReflectUtil.getAbstractListRowTop(list, i);
                    int rowBottom = ReflectUtil.getAbstractListRowBottom(list, i);
                    if(rowTop >= list.getTop() && rowBottom <= list.getBottom()) // Is visible
                    {
                        AbstractList.AbstractListEntry<?> entry = list.getEventListeners().get(i);
                        if(entry instanceof INestedGuiEventHandler)
                        {
                            INestedGuiEventHandler handler = (INestedGuiEventHandler) entry;
                            for(IGuiEventListener child : handler.getEventListeners())
                            {
                                if(child instanceof Widget)
                                {
                                    Widget widget = (Widget) child;
                                    if(widget.active && widget.visible)
                                    {
                                        widgets.add((Widget) child);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if(screen instanceof IRecipeShownListener)
        {
            RecipeBookGui recipeBook = ((IRecipeShownListener) screen).getRecipeGui();
            if(recipeBook.isVisible())
            {
                widgets.add(((RecipeBookGuiMixin) recipeBook).getToggleRecipesBtn());
                widgets.addAll(((RecipeBookGuiMixin) recipeBook).getRecipeTabs());
                RecipeBookPage recipeBookPage = ((RecipeBookGuiMixin) recipeBook).getRecipeBookPage();
                widgets.addAll(((RecipeBookPageAccessor) recipeBookPage).getButtons());
                widgets.add(((RecipeBookPageAccessor) recipeBookPage).getForwardButton());
                widgets.add(((RecipeBookPageAccessor) recipeBookPage).getBackButton());
            }
        }

        for(Widget widget : widgets)
        {
            if(widget == null || widget.isHovered() || !widget.visible || !widget.active)
                continue;
            int posX = widget.x + widget.getWidth() / 2;
            int posY = widget.y + widget.getHeight() / 2;
            points.add(new WidgetNavigationPoint(posX, posY, widget));
        }

        if(screen instanceof CreativeScreen)
        {
            int tabPage = CreativeScreenMixin.getTabPage();
            int start = tabPage * 10;
            int end = Math.min(ItemGroup.GROUPS.length, ((tabPage + 1) * 10 + 2));
            for(int i = start; i < end; i++)
            {
                ItemGroup group = ItemGroup.GROUPS[i];
                if(group != null)
                {
                    points.add(this.getCreativeTabPoint((CreativeScreen) screen, group));
                }
            }
        }

        if(Controllable.isJeiLoaded())
        {
            points.addAll(JustEnoughItems.getNavigationPoints());
        }

        return points;
    }

    /**
     * Gets the navigation point of a creative tab.
     */
    private BasicNavigationPoint getCreativeTabPoint(ContainerScreen screen, ItemGroup group)
    {
        boolean topRow = group.isOnTopRow();
        int column = group.getColumn();
        int width = 28;
        int height = 32;
        int x = screen.getGuiLeft() + width * column;
        int y = screen.getGuiTop();
        x = group.isAlignedRight() ? screen.getGuiLeft() + screen.getXSize() - width * (6 - column) : (column > 0 ? x + column : x);
        y = topRow ? y - width : y + (screen.getYSize() - 4);
        return new BasicNavigationPoint(x + width / 2.0, y + height / 2.0);
    }

    private void craftRecipeBookItem()
    {
        Minecraft mc = Minecraft.getInstance();
        if(mc.player == null)
            return;

        if(!(mc.currentScreen instanceof ContainerScreen) || !(mc.currentScreen instanceof IRecipeShownListener))
            return;

        IRecipeShownListener listener = (IRecipeShownListener) mc.currentScreen;
        if(!listener.getRecipeGui().isVisible())
            return;

        ContainerScreen screen = (ContainerScreen) mc.currentScreen;
        if(!(screen.getContainer() instanceof RecipeBookContainer))
            return;

        RecipeBookPage recipeBookPage = ((RecipeBookGuiMixin) listener.getRecipeGui()).getRecipeBookPage();
        RecipeWidget recipe = ((RecipeBookPageAccessor) recipeBookPage).getButtons().stream().filter(Widget::isHovered).findFirst().orElse(null);
        if(recipe != null)
        {
            RecipeBookContainer container = (RecipeBookContainer) screen.getContainer();
            Slot slot = container.getSlot(container.getOutputSlot());
            if(mc.player.inventory.getItemStack().isEmpty())
            {
                this.invokeMouseClick(screen, GLFW.GLFW_MOUSE_BUTTON_LEFT, screen.getGuiLeft() + slot.xPos + 8, screen.getGuiTop() + slot.yPos + 8);
            }
            else
            {
                this.invokeMouseReleased(screen, GLFW.GLFW_MOUSE_BUTTON_LEFT, screen.getGuiLeft() + slot.xPos + 8, screen.getGuiTop() + slot.yPos + 8);
            }
        }
    }

    private void moveMouseToClosestSlot(boolean moving, Screen screen)
    {
        this.nearSlot = false;

        /* Makes the mouse attracted to slots. This helps with selecting items when using
         * a controller. */
        if(screen instanceof ContainerScreen)
        {
            /* Prevents cursor from moving until at least some input is detected */
            if(!this.moved) return;

            Minecraft mc = Minecraft.getInstance();
            ContainerScreen guiContainer = (ContainerScreen) screen;
            int guiLeft = guiContainer.getGuiLeft();
            int guiTop = guiContainer.getGuiTop();
            int mouseX = (int) (this.targetMouseX * (double) mc.getMainWindow().getScaledWidth() / (double) mc.getMainWindow().getWidth());
            int mouseY = (int) (this.targetMouseY * (double) mc.getMainWindow().getScaledHeight() / (double) mc.getMainWindow().getHeight());

            /* Finds the closest slot in the GUI within 14 pixels (inclusive) */
            Slot closestSlot = null;
            double closestDistance = -1.0;
            for(Slot slot : guiContainer.getContainer().inventorySlots)
            {
                int posX = guiLeft + slot.xPos + 8;
                int posY = guiTop + slot.yPos + 8;

                double distance = Math.sqrt(Math.pow(posX - mouseX, 2) + Math.pow(posY - mouseY, 2));
                if((closestDistance == -1.0 || distance < closestDistance) && distance <= 14.0)
                {
                    closestSlot = slot;
                    closestDistance = distance;
                }
            }

            if(closestSlot != null && (closestSlot.getHasStack() || !mc.player.inventory.getItemStack().isEmpty()))
            {
                this.nearSlot = true;
                int slotCenterXScaled = guiLeft + closestSlot.xPos + 8;
                int slotCenterYScaled = guiTop + closestSlot.yPos + 8;
                int slotCenterX = (int) (slotCenterXScaled / ((double) mc.getMainWindow().getScaledWidth() / (double) mc.getMainWindow().getWidth()));
                int slotCenterY = (int) (slotCenterYScaled / ((double) mc.getMainWindow().getScaledHeight() / (double) mc.getMainWindow().getHeight()));
                double deltaX = slotCenterX - targetMouseX;
                double deltaY = slotCenterY - targetMouseY;

                if(!moving)
                {
                    if(mouseX != slotCenterXScaled || mouseY != slotCenterYScaled)
                    {
                        this.targetMouseX += deltaX * 0.75;
                        this.targetMouseY += deltaY * 0.75;
                    }
                    else
                    {
                        this.mouseSpeedX = 0.0F;
                        this.mouseSpeedY = 0.0F;
                    }
                }

                this.mouseSpeedX *= 0.75F;
                this.mouseSpeedY *= 0.75F;
            }
            else
            {
                this.mouseSpeedX = 0.0F;
                this.mouseSpeedY = 0.0F;
            }
        }
        else
        {
            this.mouseSpeedX = 0.0F;
            this.mouseSpeedY = 0.0F;
        }
    }

    private void setMousePosition(double mouseX, double mouseY)
    {
        if(Config.CLIENT.options.virtualMouse.get())
        {
            this.virtualMouseX = mouseX;
            this.virtualMouseY = mouseY;
        }
        else
        {
            Minecraft mc = Minecraft.getInstance();
            GLFW.glfwSetCursorPos(mc.getMainWindow().getHandle(), mouseX, mouseY);
            this.preventReset = true;
        }
    }

    private void handleCreativeScrolling(CreativeScreen creative, Controller controller)
    {
        try
        {
            int i = (creative.getContainer().itemList.size() + 9 - 1) / 9 - 5;
            int dir = 0;

            if(controller.getRThumbStickYValue() <= -0.8F)
            {
                dir = 1;
            }
            else if(controller.getRThumbStickYValue() >= 0.8F)
            {
                dir = -1;
            }

            Field field = ObfuscationReflectionHelper.findField(CreativeScreen.class, "field_147067_x");
            field.setAccessible(true);

            float currentScroll = field.getFloat(creative);
            currentScroll = (float) ((double) currentScroll - (double) dir / (double) i);
            currentScroll = MathHelper.clamp(currentScroll, 0.0F, 1.0F);
            field.setFloat(creative, currentScroll);
            creative.getContainer().scrollTo(currentScroll);
        }
        catch(IllegalAccessException e)
        {
            e.printStackTrace();
        }
    }

    private void handleListScrolling(AbstractList list, Controller controller)
    {
        double dir = 0;
        float yValue = Config.CLIENT.options.cursorThumbstick.get() == Thumbstick.LEFT ? controller.getRThumbStickYValue() : controller.getLThumbStickYValue();
        if(Math.abs(yValue) >= 0.2F)
        {
            this.setControllerInUse();
            dir = yValue;
        }
        dir *= Minecraft.getInstance().getTickLength();
        list.setScrollAmount(list.getScrollAmount() + dir * 10);
    }

    private void handleMerchantScrolling(MerchantScreen screen, Controller controller)
    {
        double dir = 0;
        float yValue = Config.CLIENT.options.cursorThumbstick.get() == Thumbstick.LEFT ? controller.getRThumbStickYValue() : controller.getLThumbStickYValue();
        if(Math.abs(yValue) >= 0.5F)
        {
            this.setControllerInUse();
            dir = -yValue;
        }
        else
        {
            // Do this to allow thumbstick to be tap up or down
            this.lastMerchantScroll = 0;
        }
        long scrollTime = Util.milliTime();
        if(dir != 0 && scrollTime - this.lastMerchantScroll >= 150)
        {
            screen.mouseScrolled(this.getMouseX(), this.getMouseY(), Math.signum(dir));
            this.lastMerchantScroll = scrollTime;
        }
    }

    private double getMouseX()
    {
        Minecraft mc = Minecraft.getInstance();
        double mouseX = mc.mouseHelper.getMouseX();
        if(Controllable.getController() != null && Config.CLIENT.options.virtualMouse.get() && this.lastUse > 0)
        {
            mouseX = this.virtualMouseX;
        }
        return mouseX * (double) mc.getMainWindow().getScaledWidth() / (double) mc.getMainWindow().getWidth();
    }

    private double getMouseY()
    {
        Minecraft mc = Minecraft.getInstance();
        double mouseY = mc.mouseHelper.getMouseY();
        if(Controllable.getController() != null && Config.CLIENT.options.virtualMouse.get() && this.lastUse > 0)
        {
            mouseY = this.virtualMouseY;
        }
        return mouseY * (double) mc.getMainWindow().getScaledHeight() / (double) mc.getMainWindow().getHeight();
    }

    /**
     * Invokes a mouse click in a GUI. This is modified version that is designed for controllers.
     * Upon clicking, mouse released is called straight away to make sure dragging doesn't happen.
     *
     * @param screen the screen instance
     * @param button the button to click with
     */
    private void invokeMouseClick(Screen screen, int button)
    {
        if(screen != null)
        {
            double mouseX = this.getMouseX();
            double mouseY = this.getMouseY();
            this.invokeMouseClick(screen, button, mouseX, mouseY);
        }
    }

    private void invokeMouseClick(Screen screen, int button, double mouseX, double mouseY)
    {
        Minecraft mc = Minecraft.getInstance();
        if(screen != null)
        {
            mc.mouseHelper.activeButton = button;
            mc.mouseHelper.eventTime = NativeUtil.getTime();

            Screen.wrapScreenError(() ->
            {
                boolean cancelled = ForgeHooksClient.onGuiMouseClickedPre(screen, mouseX, mouseY, button);
                if(!cancelled)
                {
                    cancelled = screen.mouseClicked(mouseX, mouseY, button);
                }
                if(!cancelled)
                {
                    ForgeHooksClient.onGuiMouseClickedPost(screen, mouseX, mouseY, button);
                }
            }, "mouseClicked event handler", screen.getClass().getCanonicalName());
        }
    }

    /**
     * Invokes a mouse released in a GUI. This is modified version that is designed for controllers.
     * Upon clicking, mouse released is called straight away to make sure dragging doesn't happen.
     *
     * @param screen the screen instance
     * @param button the button to click with
     */
    private void invokeMouseReleased(Screen screen, int button)
    {
        if(screen != null)
        {
            double mouseX = this.getMouseX();
            double mouseY = this.getMouseY();
            this.invokeMouseReleased(screen, button, mouseX, mouseY);
        }
    }

    private void invokeMouseReleased(Screen screen, int button, double mouseX, double mouseY)
    {
        Minecraft mc = Minecraft.getInstance();
        if(screen != null)
        {
            mc.mouseHelper.activeButton = -1;

            Screen.wrapScreenError(() ->
            {
                boolean cancelled = ForgeHooksClient.onGuiMouseReleasedPre(screen, mouseX, mouseY, button);
                if(!cancelled)
                {
                    cancelled = screen.mouseReleased(mouseX, mouseY, button);
                }
                if(!cancelled)
                {
                    ForgeHooksClient.onGuiMouseReleasedPost(screen, mouseX, mouseY, button);
                }
            }, "mouseReleased event handler", screen.getClass().getCanonicalName());
        }
    }

    private enum Navigate
    {
        UP((p, x, y) -> p.getY() < y, (p, v) -> Math.abs(p.getX() - v.x)),
        DOWN((p, x, y) -> p.getY() > y + 1, (p, v) -> Math.abs(p.getX() - v.x)),
        LEFT((p, x, y) -> p.getX() < x, (p, v) -> Math.abs(p.getY() - v.y)),
        RIGHT((p, x, y) -> p.getX() > x + 1, (p, v) -> Math.abs(p.getY() - v.y));

        private NavigatePredicate predicate;
        private BiFunction<? super NavigationPoint, Vector3d, Double> keyExtractor;

        Navigate(NavigatePredicate predicate, BiFunction<? super NavigationPoint, Vector3d, Double> keyExtractor)
        {
            this.predicate = predicate;
            this.keyExtractor = keyExtractor;
        }

        public NavigatePredicate getPredicate()
        {
            return this.predicate;
        }

        public BiFunction<? super NavigationPoint, Vector3d, Double> getKeyExtractor()
        {
            return this.keyExtractor;
        }

        public Comparator<NavigationPoint> getMinComparator(int mouseX, int mouseY)
        {
            return Comparator.comparing(p -> this.keyExtractor.apply(p, new Vector3d(mouseX, mouseY, 0)));
        }

        public static void main(String[] args)
        {
            int slotX = 10;
            int slotY = 20;
            int mouseX = 50;
            int mouseY = 20;
            angle(new SlotNavigationPoint(slotX, slotY, null), mouseX, mouseY, 0);
        }

        private static boolean angle(NavigationPoint point, int mouseX, int mouseY, double offset)
        {
            double angle = Math.toDegrees(Math.atan2(point.getY() - mouseY, point.getX() - mouseX)) + offset;
            return angle > -45 && angle < 45;
        }
    }

    private interface NavigatePredicate
    {
        boolean test(NavigationPoint point, int mouseX, int mouseY);
    }
}
