package dev.kianj.materialsgui;

import dev.kianj.materialsgui.data.ProjectStore;
import java.nio.file.Path;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Which save file the world or server you're on uses. This is the only part of the key that needs the running client,
 * so it sits out here rather than in {@code data/}, which is kept free of client and loader APIs.
 */
final class WorldKeys {
	private WorldKeys() {}

	/**
	 * Singleplayer worlds are keyed by their save folder, which is unique (two worlds can share a name). LAN worlds and
	 * Realms are keyed by name, since their address changes each time.
	 */
	static String of(Minecraft mc) {
		IntegratedServer sp = mc.getSingleplayerServer();
		if (sp != null) {
			Path folder = sp.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().getFileName();
			return "sp_" + ProjectStore.sanitize(folder != null ? folder.toString() : sp.getWorldData().getLevelName());
		}
		ServerData server = mc.getCurrentServer();
		if (server != null) {
			if (server.isRealm()) {
				return "realm_" + ProjectStore.sanitize(server.name);
			}
			if (server.isLan()) {
				return "lan_" + ProjectStore.sanitize(server.name);
			}
			return "mp_" + ProjectStore.sanitize(server.ip);
		}
		return "default";
	}
}
