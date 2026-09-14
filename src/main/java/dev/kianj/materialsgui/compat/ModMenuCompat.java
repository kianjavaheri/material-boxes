package dev.kianj.materialsgui.compat;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import dev.kianj.materialsgui.screen.SettingsScreen;

/** Puts the settings screen in Mod Menu. Only loaded when Mod Menu is installed. */
public class ModMenuCompat implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return SettingsScreen::new;
	}
}
