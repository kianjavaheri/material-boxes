package dev.kianj.materialsgui.mixin;

import dev.kianj.materialsgui.box.BoxRefresher;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.MenuType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MenuScreens.class)
public abstract class MenuScreensMixin {
	// A Material Box being refreshed in the background gets its menu, but no screen.
	@Inject(method = "create", at = @At("HEAD"), cancellable = true)
	private static void materialsgui$refreshWithoutScreen(MenuType<?> type, Minecraft minecraft, int containerId, Component title, CallbackInfo ci) {
		if (BoxRefresher.onOpenScreen(type, containerId)) {
			ci.cancel();
		}
	}
}
