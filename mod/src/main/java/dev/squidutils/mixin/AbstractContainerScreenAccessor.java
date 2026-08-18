package dev.squidutils.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reads the screen's own top-left origin directly. A {@link
 * net.minecraft.world.inventory.Slot}'s own {@code x}/{@code y} are public
 * but only relative to this - converting one into an absolute screen
 * position (to draw a highlight behind it, as {@code OrderOverlay} does)
 * needs both, and {@code leftPos}/{@code topPos} are {@code protected} on
 * the vanilla class.
 */
@Mixin(AbstractContainerScreen.class)
public interface AbstractContainerScreenAccessor {

    @Accessor("leftPos")
    int squidutils$leftPos();

    @Accessor("topPos")
    int squidutils$topPos();
}
