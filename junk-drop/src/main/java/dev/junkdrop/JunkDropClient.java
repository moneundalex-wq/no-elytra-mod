package dev.junkdrop;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.mojang.blaze3d.platform.InputConstants;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;

/**
 * Client-seitige Mod, kein Server-Teil, keine Mixins.
 *
 * Drei Tasten (unter Steuerung > Inventar einstellbar):
 *   J  Junk droppen aus dem Inventar (ohne Hotbar, ohne Rüstung, ohne Offhand)
 *   K  Junk droppen aus Inventar UND Hotbar
 *   -  (ungebunden) Handitem zur Junk-Liste hinzufügen bzw. entfernen
 *
 * Was Junk ist, steht in der Datei  config/junkdrop.txt  (eine Item-ID pro Zeile, z. B.
 * minecraft:totem_of_undying). Die Datei wird bei jedem Tastendruck neu gelesen, du kannst
 * sie also auch im Spiel bearbeiten. Beim ersten Start sind Totems und Tränke eingetragen.
 *
 * Es wird nie das Inventar geöffnet. Gedroppt wird pro Tick ein Stack, damit nicht
 * dutzende Pakete auf einmal gesendet werden. Offhand und Rüstung werden nie angefasst.
 */
public class JunkDropClient implements ClientModInitializer {

	private static final String[] DEFAULT_JUNK = {
			"minecraft:totem_of_undying",
			"minecraft:potion",
			"minecraft:splash_potion",
			"minecraft:lingering_potion"
	};

	/** Menü-Slots im Spieler-Inventar: 9-35 = Inventar, 36-44 = Hotbar. */
	private static final int INVENTORY_FIRST = 9;
	private static final int INVENTORY_LAST = 35;
	private static final int HOTBAR_FIRST = 36;
	private static final int HOTBAR_LAST = 44;
	/** Mausknopf 1 bei ClickType.THROW = ganzen Stack droppen (wie Strg+Q). */
	private static final int THROW_WHOLE_STACK = 1;

	private final Set<String> junk = new LinkedHashSet<>();
	private final Deque<Integer> queue = new ArrayDeque<>();
	private Path configPath;

	private KeyMapping dropKey;
	private KeyMapping dropWithHotbarKey;
	private KeyMapping toggleKey;

	@Override
	public void onInitializeClient() {
		configPath = FabricLoader.getInstance().getConfigDir().resolve("junkdrop.txt");
		loadConfig();

		KeyMapping.Category category = KeyMapping.Category.INVENTORY;
		dropKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
				"key.junkdrop.drop", InputConstants.Type.KEYSYM, InputConstants.KEY_J, category));
		dropWithHotbarKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
				"key.junkdrop.drop_hotbar", InputConstants.Type.KEYSYM, InputConstants.KEY_K, category));
		toggleKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
				"key.junkdrop.toggle", InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(), category));

		ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
	}

	private void onTick(Minecraft client) {
		LocalPlayer player = client.player;
		MultiPlayerGameMode gameMode = client.gameMode;

		if (player == null || gameMode == null) {
			queue.clear();
			return;
		}

		while (dropKey.consumeClick()) {
			startDrop(client, player, false);
		}
		while (dropWithHotbarKey.consumeClick()) {
			startDrop(client, player, true);
		}
		while (toggleKey.consumeClick()) {
			toggleHeldItem(player);
		}

		// Pausiert, solange du selbst ein Inventar/Menü offen hast
		if (client.screen != null || queue.isEmpty()) {
			return;
		}

		Integer slot = queue.poll();
		if (slot == null) {
			return;
		}
		// Slot erneut prüfen: Inhalt kann sich inzwischen geändert haben
		if (!isJunk(player.inventoryMenu.getSlot(slot).getItem())) {
			return;
		}
		gameMode.handleInventoryMouseClick(
				player.inventoryMenu.containerId,
				slot,
				THROW_WHOLE_STACK,
				ClickType.THROW,
				player);
	}

	private void startDrop(Minecraft client, LocalPlayer player, boolean includeHotbar) {
		if (client.screen != null) {
			return;
		}
		loadConfig();
		queue.clear();

		for (int slot = INVENTORY_FIRST; slot <= INVENTORY_LAST; slot++) {
			addIfJunk(player, slot);
		}
		if (includeHotbar) {
			for (int slot = HOTBAR_FIRST; slot <= HOTBAR_LAST; slot++) {
				addIfJunk(player, slot);
			}
		}

		int count = queue.size();
		player.displayClientMessage(Component.literal(
				count == 0 ? "Kein Junk gefunden." : count + " Junk-Stacks werden gedroppt ..."), true);
	}

	private void addIfJunk(LocalPlayer player, int menuSlot) {
		if (isJunk(player.inventoryMenu.getSlot(menuSlot).getItem())) {
			queue.add(menuSlot);
		}
	}

	private void toggleHeldItem(LocalPlayer player) {
		ItemStack held = player.getMainHandItem();
		if (held.isEmpty()) {
			player.displayClientMessage(Component.literal("Halte ein Item in der Hand, um es zur Junk-Liste hinzuzufügen."), true);
			return;
		}

		String id = idOf(held);
		boolean added = junk.add(id);
		if (!added) {
			junk.remove(id);
		}
		saveConfig();

		player.displayClientMessage(Component.literal("Junk-Liste: ")
				.append(held.getHoverName())
				.append(Component.literal(added ? " hinzugefügt" : " entfernt")), true);
	}

	private boolean isJunk(ItemStack stack) {
		return !stack.isEmpty() && junk.contains(idOf(stack));
	}

	private static String idOf(ItemStack stack) {
		return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
	}

	private void loadConfig() {
		junk.clear();
		try {
			if (!Files.exists(configPath)) {
				for (String id : DEFAULT_JUNK) {
					junk.add(id);
				}
				saveConfig();
				return;
			}
			for (String line : Files.readAllLines(configPath, StandardCharsets.UTF_8)) {
				String entry = line.strip();
				if (entry.isEmpty() || entry.startsWith("#")) {
					continue;
				}
				if (!entry.contains(":")) {
					entry = "minecraft:" + entry;
				}
				junk.add(entry.toLowerCase(Locale.ROOT));
			}
		} catch (IOException e) {
			junk.clear();
			for (String id : DEFAULT_JUNK) {
				junk.add(id);
			}
		}
	}

	private void saveConfig() {
		List<String> lines = new ArrayList<>();
		lines.add("# Junk Drop: Items, die per Hotkey gedroppt werden.");
		lines.add("# Eine Item-ID pro Zeile, zum Beispiel minecraft:totem_of_undying");
		lines.add("# Zeilen mit # am Anfang werden ignoriert.");
		lines.addAll(junk);
		try {
			Files.write(configPath, lines, StandardCharsets.UTF_8);
		} catch (IOException ignored) {
			// Konfig konnte nicht gespeichert werden: Änderungen gelten nur bis zum Neustart
		}
	}
}
