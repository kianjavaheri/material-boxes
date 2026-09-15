package dev.kianj.materialsgui;

import com.mojang.blaze3d.platform.InputConstants;
import dev.kianj.materialsgui.box.BoxRefresher;
import dev.kianj.materialsgui.box.BoxTracker;
import dev.kianj.materialsgui.box.BoxValidator;
import dev.kianj.materialsgui.box.ContainerHooks;
import dev.kianj.materialsgui.box.SlotTooltip;
import dev.kianj.materialsgui.data.ModConfig;
import dev.kianj.materialsgui.data.ProjectStore;
import dev.kianj.materialsgui.hud.MaterialsHud;
import dev.kianj.materialsgui.preview.ShulkerPreview;
import dev.kianj.materialsgui.screen.MaterialsScreen;
import dev.kianj.materialsgui.screen.SettingsScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.event.client.player.ClientPlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionResult;

public class MaterialsGuiClient implements ClientModInitializer {
	private static KeyMapping openKey;
	private static KeyMapping hudKey;
	private static boolean openNextTick;
	private static boolean openSettingsNextTick;

	@Override
	public void onInitializeClient() {
		KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("materialsgui", "main"));
		openKey = KeyMappingHelper.registerKeyMapping(
			new KeyMapping("key.materialsgui.open", InputConstants.Type.KEYSYM, InputConstants.KEY_B, category));
		hudKey = KeyMappingHelper.registerKeyMapping(
			new KeyMapping("key.materialsgui.hud", InputConstants.Type.KEYSYM, InputConstants.KEY_H, category));

		ClientTickEvents.END_CLIENT_TICK.register(BoxValidator::tick);
		ClientTickEvents.END_CLIENT_TICK.register(BoxRefresher::tick);
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			while (openKey.consumeClick()) {
				openNextTick |= mc.gui.screen() == null;
			}
			while (hudKey.consumeClick()) {
				ModConfig config = ModConfig.get();
				config.hudEnabled = !config.hudEnabled;
				config.save();
				if (mc.player != null) {
					mc.player.sendOverlayMessage(Component.literal("Missing materials HUD " + (config.hudEnabled ? "on" : "off")));
				}
			}
			// Deferred a tick so the chat screen closing after "/materials" doesn't close ours.
			if (openNextTick && mc.player != null) {
				openNextTick = false;
				mc.gui.setScreen(new MaterialsScreen());
			}
			if (openSettingsNextTick) {
				openSettingsNextTick = false;
				mc.gui.setScreen(new SettingsScreen(null));
			}
		});
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> dispatcher.register(
			ClientCommands.literal("materials")
				.executes(c -> {
					openNextTick = true;
					return 1;
				})
				.then(ClientCommands.literal("settings").executes(c -> {
					openSettingsNextTick = true;
					return 1;
				}))));

		ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> {
			// Nothing from the last world or server carries over.
			BoxRefresher.reset();
			BoxTracker.reset();
			BoxValidator.reset();
			MaterialsScreen.clearUnresolved();
			ProjectStore.load(mc);
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> {
			// Fabric can fire this on the network thread when the server drops the connection; save on the client thread.
			Runnable leave = () -> {
				ProjectStore.save();
				BoxRefresher.reset();
			};
			if (mc.isSameThread()) {
				leave.run();
			} else {
				mc.execute(leave);
			}
		});
		UseBlockCallback.EVENT.register(BoxTracker::onUseBlock);
		UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
			// A container opened from an entity (a chest boat, say) isn't the block clicked before it.
			if (level.isClientSide()) {
				BoxTracker.clearPending();
			}
			return InteractionResult.PASS;
		});
		ClientPlayerBlockBreakEvents.AFTER.register(BoxValidator::onPlayerBreak);
		ItemTooltipCallback.EVENT.register(SlotTooltip::appendToItemTooltip);
		// The preview goes first so, while open, it gets clicks before Material Box shift-click routing.
		ScreenEvents.AFTER_INIT.register(ShulkerPreview::afterInit);
		ScreenEvents.AFTER_INIT.register(ContainerHooks::afterInit);
		MaterialsHud.register();
	}
}
