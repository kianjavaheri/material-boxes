package dev.kianj.materialsgui.mixin;

import dev.kianj.materialsgui.box.SlotOverlay;
import dev.kianj.materialsgui.box.SlotTooltip;
import dev.kianj.materialsgui.preview.ShulkerPreview;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractContainerScreen.class)
public abstract class AbstractContainerScreenMixin {
	@Inject(method = "extractSlot", at = @At("HEAD"))
	private void materialsgui$beforeSlot(GuiGraphicsExtractor graphics, Slot slot, int mouseX, int mouseY, CallbackInfo ci) {
		SlotOverlay.before((AbstractContainerScreen<?>) (Object) this, graphics, slot);
	}

	@Inject(method = "extractSlot", at = @At("TAIL"))
	private void materialsgui$afterSlot(GuiGraphicsExtractor graphics, Slot slot, int mouseX, int mouseY, CallbackInfo ci) {
		SlotOverlay.after((AbstractContainerScreen<?>) (Object) this, graphics, slot);
	}

	// While a shulker preview is open it draws on top and replaces the tooltip of whatever slot is underneath.
	@Inject(method = "extractTooltip", at = @At("HEAD"), cancellable = true)
	private void materialsgui$shulkerPreview(GuiGraphicsExtractor graphics, int mouseX, int mouseY, CallbackInfo ci) {
		if (ShulkerPreview.extract((AbstractContainerScreen<?>) (Object) this, graphics, mouseX, mouseY)) {
			ci.cancel();
		}
	}

	// Tooltips must be set here, before the screen's deferred tooltip pass; later hooks are too late for the frame.
	// RETURN, not TAIL: vanilla returns early when the hovered slot is empty, and TAIL only hooks the last return.
	@Inject(method = "extractTooltip", at = @At("RETURN"))
	private void materialsgui$ghostTooltip(GuiGraphicsExtractor graphics, int mouseX, int mouseY, CallbackInfo ci) {
		SlotTooltip.extractGhostTooltip((AbstractContainerScreen<?>) (Object) this, graphics, mouseX, mouseY);
	}
}
