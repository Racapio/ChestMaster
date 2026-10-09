package com.chestmaster.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes the protected layout fields needed to draw slot highlights and read the hovered slot. */
@Mixin(AbstractContainerScreen.class)
public interface AbstractContainerScreenAccessor {
    @Accessor("leftPos")
    int chestmaster$getLeftPos();

    @Accessor("topPos")
    int chestmaster$getTopPos();

    @Accessor("hoveredSlot")
    Slot chestmaster$getHoveredSlot();
}
