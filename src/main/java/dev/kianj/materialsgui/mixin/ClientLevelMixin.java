package dev.kianj.materialsgui.mixin;

import dev.kianj.materialsgui.box.BoxRefresher;
import dev.kianj.materialsgui.box.BoxTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientLevel.class)
public abstract class ClientLevelMixin {
	// The server acknowledges each block click after sending any container it opened, so by now that has arrived.
	@Inject(method = "handleBlockChangedAck", at = @At("HEAD"))
	private void materialsgui$onBlockChangedAck(int sequence, CallbackInfo ci) {
		BoxTracker.onBlockChangedAck(sequence);
		BoxRefresher.onBlockChangedAck(sequence);
	}
}
