package dev.kianj.materialsgui.mixin;

import dev.kianj.materialsgui.box.BoxRefresher;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
	// While a Material Box is being read in the background, the player's right-clicks wait (it takes a moment), so the
	// server never has two containers to open or close at once.
	@Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
	private void materialsgui$waitForBoxRefresh(CallbackInfo ci) {
		if (BoxRefresher.blocksPlayerUse()) {
			ci.cancel();
		}
	}
}
