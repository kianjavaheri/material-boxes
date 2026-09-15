package dev.kianj.materialsgui.mixin;

import dev.kianj.materialsgui.box.BoxTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MultiPlayerGameMode.class)
public abstract class MultiPlayerGameModeMixin {
	// The click's prediction sequence is only known once it's been sent, after Fabric's UseBlockCallback has run.
	@Inject(method = "useItemOn", at = @At("RETURN"))
	private void materialsgui$afterUseItemOn(LocalPlayer player, InteractionHand hand, BlockHitResult hit, CallbackInfoReturnable<InteractionResult> cir) {
		if (Minecraft.getInstance().level != null) {
			BoxTracker.afterUseItemOn(Minecraft.getInstance().level);
		}
	}
}
