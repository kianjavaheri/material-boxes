package dev.kianj.materialsgui.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(AbstractContainerScreen.class)
public interface AbstractContainerScreenAccessor {
	@Accessor("leftPos")
	int materialsgui$getLeftPos();

	@Accessor("topPos")
	int materialsgui$getTopPos();

	@Accessor("imageWidth")
	int materialsgui$getImageWidth();

	@Accessor("imageHeight")
	int materialsgui$getImageHeight();

	@Accessor("hoveredSlot")
	@Nullable Slot materialsgui$getHoveredSlot();
}
