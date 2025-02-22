package com.mrcrayfish.controllable.mixin.client;

import com.mrcrayfish.controllable.Controllable;
import com.mrcrayfish.controllable.client.ButtonBindings;
import com.mrcrayfish.controllable.client.input.Controller;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Author: MrCrayfish
 */
@Mixin(ForgeGui.class)
public class ForgeGuiMixin
{
    /**
     * Fixes record name rendering not being offset by console hotbar
     */
    @Redirect(method = "renderPlayerList", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/KeyMapping;isDown()Z"))
    private boolean controllableRenderPlayerList(KeyMapping mapping)
    {
        return mapping.isDown() || canShowPlayerList();
    }

    /**
     * Checks if a controller is connected and the player list button is down
     */
    private static boolean canShowPlayerList()
    {
        Controller controller = Controllable.getController();
        return controller != null && ButtonBindings.PLAYER_LIST.isButtonDown();
    }
}
