package com.chestmaster.mixin;

import com.chestmaster.ChestMasterMod;
import com.chestmaster.scanner.ChestScanner;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ChestMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ContainerScreen.class)
public abstract class GenericContainerScreenMixin {
    @Inject(method = "<init>", at = @At("TAIL"))
    private void onInit(ChestMenu handler, Inventory inventory, Component title, CallbackInfo ci) {
        if (!ChestScanner.INSTANCE.isAutoScanEnabled()) {
            return;
        }

        try {
            if (ChestMasterMod.Companion.isVerboseLogging()) {
                ChestMasterMod.Companion.getLOGGER().debug("Container opened: " + title.getString());
            }
            ChestScanner.INSTANCE.onScreenOpen((AbstractContainerScreen<?>)(Object)this, handler);
        } catch (Exception e) {
            ChestMasterMod.Companion.getLOGGER().error("Error in ChestScanner.onScreenOpen", e);
        }
    }
}
