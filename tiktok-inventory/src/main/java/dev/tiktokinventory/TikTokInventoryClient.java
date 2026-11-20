package dev.tiktokinventory;

import net.dimaskama.mcef.api.MCEFApi;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;

public class TikTokInventoryClient implements ClientModInitializer {

	@Override
	public void onInitializeClient() {
		// Chromium schon beim Start im Hintergrund vorbereiten (Download beim ersten Mal)
		try {
			MCEFApi.initialize();
		} catch (Throwable t) {
			System.err.println("[TikTokInventory] MCEF Start fehlgeschlagen: " + t);
		}

		ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
			if (screen instanceof InventoryScreen) {
				Button b = Button.builder(Component.literal("TikTok"),
								btn -> client.setScreen(new TikTokScreen(screen)))
						.bounds(w - 64, 4, 60, 20)
						.build();
				Screens.getButtons(screen).add(b);
			}
		});
	}
}
