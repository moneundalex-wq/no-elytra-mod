package dev.junkdrop;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
 *   J  Junk droppen aus dem Inventar (Hotbar bleibt unberührt, zählt aber zum Behalten)
 *   K  Junk droppen aus Inventar UND Hotbar
 *   -  (ungebunden) Handitem zur Junk-Liste hinzufügen bzw. entfernen
 *
 * Was Junk ist, steht in der Datei  config/junkdrop.txt  (eine Item-ID pro Zeile).
 * Mit  =Zahl  hinter der ID behältst du so viele davon, z. B.
 *   minecraft:totem_of_undying=3
 * Gezählt wird in dieser Reihenfolge: Offhand, Hotbar, Inventar. Alles darüber hinaus wird
 * gedroppt. Standard: 3 Totems behalten (Offhand + Hotbar + eins im Inventar).
 * Die Datei wird bei jedem Tastendruck neu gelesen, du kannst sie also auch im Spiel ändern.
 *
 * Es wird nie das Inventar geöffnet. Gedroppt wird pro Tick eine Aktion, damit nicht dutzende
 * Pakete auf einmal gesendet werden. Offhand und Rüstung werden nie angefasst.
 */
public class JunkDropClient implements ClientModInitializer {

	private static final String TOTEM = "minecraft:totem_of_undying";
	private static final String VERSION_LINE = "# junkdrop-config-version: 2";

	/** Menü-Slots im Spieler-Inventar: 9-35 = Inventar, 36-44 = Hotbar. */
	private static final int INVENTORY_FIRST = 9;
	private static final int INVENTORY_LAST = 35;
	private static final int HOTBAR_FIRST = 36;
	private static final int HOTBAR_LAST = 44;
	/** ClickType.THROW: Knopf 1 = ganzen Stack droppen (Strg+Q), Knopf 0 = ein Item droppen (Q). */
	private static final int THROW_WHOLE_STACK = 1;
	private static final int THROW_ONE = 0;

	private record DropAction(int slot, boolean wholeStack) {
	}

	/** Item-ID -> Anzahl, die behalten wird (0 = alles droppen). */
	private final Map<String, Integer> junk = new LinkedHashMap<>();
	private final Deque<DropAction> queue = new ArrayDeque<>();
	private Path configPath;

	private KeyMapping dropKey;
	private KeyMapping dropWithHotbarKey;
	private KeyMapping toggleKey;
	private KeyMapping menuKey;

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

		menuKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
				"key.junkdrop.menu", InputConstants.Type.KEYSYM, InputConstants.KEY_H, category));

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
		while (menuKey.consumeClick()) {
			if (client.screen == null) {
				loadConfig();
				client.setScreen(new JunkDropScreen(null, this));
			}
		}

		// Pausiert, solange du selbst ein Inventar/Menü offen hast
		if (client.screen != null || queue.isEmpty()) {
			return;
		}

		DropAction action = queue.poll();
		if (action == null) {
			return;
		}
		// Slot erneut prüfen: Inhalt kann sich inzwischen geändert haben
		if (!isJunk(player.inventoryMenu.getSlot(action.slot()).getItem())) {
			return;
		}
		gameMode.handleInventoryMouseClick(
				player.inventoryMenu.containerId,
				action.slot(),
				action.wholeStack() ? THROW_WHOLE_STACK : THROW_ONE,
				ClickType.THROW,
				player);
	}

	private void startDrop(Minecraft client, LocalPlayer player, boolean includeHotbar) {
		if (client.screen != null) {
			return;
		}
		loadConfig();
		queue.clear();

		// Noch zu behaltende Anzahl pro Item
		Map<String, Integer> remaining = new HashMap<>();
		for (Map.Entry<String, Integer> entry : junk.entrySet()) {
			if (entry.getValue() > 0) {
				remaining.put(entry.getKey(), entry.getValue());
			}
		}

		// 1) Offhand zählt zum Behalten, wird aber nie gedroppt
		countAsKept(remaining, player.getOffhandItem());

		// 2) Hotbar: zählt zuerst zum Behalten, gedroppt wird nur mit Taste K
		for (int slot = HOTBAR_FIRST; slot <= HOTBAR_LAST; slot++) {
			processSlot(player, slot, remaining, includeHotbar);
		}

		// 3) Inventar
		for (int slot = INVENTORY_FIRST; slot <= INVENTORY_LAST; slot++) {
			processSlot(player, slot, remaining, true);
		}

		player.displayClientMessage(Component.literal(
				queue.isEmpty() ? "Kein Junk zum Droppen gefunden." : "Junk wird gedroppt ..."), true);
	}

	private void processSlot(LocalPlayer player, int menuSlot, Map<String, Integer> remaining, boolean canDrop) {
		ItemStack stack = player.inventoryMenu.getSlot(menuSlot).getItem();
		if (!isJunk(stack)) {
			return;
		}
		String id = idOf(stack);

		if (!canDrop) {
			// Slot wird nicht angefasst, zählt aber komplett zum Behalten
			countAsKept(remaining, stack);
			return;
		}

		int keep = Math.min(stack.getCount(), remaining.getOrDefault(id, 0));
		if (keep > 0) {
			remaining.put(id, remaining.get(id) - keep);
		}
		int excess = stack.getCount() - keep;
		if (excess <= 0) {
			return;
		}
		if (keep == 0) {
			queue.add(new DropAction(menuSlot, true));
		} else {
			// Stack teilweise behalten: überzählige Items einzeln droppen
			for (int i = 0; i < excess; i++) {
				queue.add(new DropAction(menuSlot, false));
			}
		}
	}

	private void countAsKept(Map<String, Integer> remaining, ItemStack stack) {
		if (stack.isEmpty()) {
			return;
		}
		String id = idOf(stack);
		Integer left = remaining.get(id);
		if (left != null) {
			remaining.put(id, Math.max(0, left - stack.getCount()));
		}
	}

	private void toggleHeldItem(LocalPlayer player) {
		ItemStack held = player.getMainHandItem();
		if (held.isEmpty()) {
			player.displayClientMessage(Component.literal("Halte ein Item in der Hand, um es zur Junk-Liste hinzuzufügen."), true);
			return;
		}

		String id = idOf(held);
		boolean added = !junk.containsKey(id);
		if (added) {
			junk.put(id, 0);
		} else {
			junk.remove(id);
		}
		saveConfig();

		player.displayClientMessage(Component.literal("Junk-Liste: ")
				.append(held.getHoverName())
				.append(Component.literal(added ? " hinzugefügt" : " entfernt")), true);
	}

	private boolean isJunk(ItemStack stack) {
		return !stack.isEmpty() && junk.containsKey(idOf(stack));
	}

	private static String idOf(ItemStack stack) {
		return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
	}

	private void putDefaults() {
		junk.clear();
		junk.put(TOTEM, 3);
		junk.put("minecraft:potion", 0);
		junk.put("minecraft:splash_potion", 0);
		junk.put("minecraft:lingering_potion", 0);
	}

	/** Die Junk-Liste (Item-ID -> Behalte-Anzahl), vom Einstellungs-Menü bearbeitet. */
	Map<String, Integer> junkMap() {
		return junk;
	}

	void loadConfig() {
		junk.clear();
		try {
			if (!Files.exists(configPath)) {
				putDefaults();
				saveConfig();
				return;
			}

			List<String> lines = Files.readAllLines(configPath, StandardCharsets.UTF_8);
			boolean currentVersion = false;
			for (String line : lines) {
				String entry = line.strip();
				if (entry.equals(VERSION_LINE)) {
					currentVersion = true;
				}
				if (entry.isEmpty() || entry.startsWith("#")) {
					continue;
				}

				int keep = 0;
				int equals = entry.indexOf('=');
				if (equals >= 0) {
					try {
						keep = Math.max(0, Integer.parseInt(entry.substring(equals + 1).strip()));
					} catch (NumberFormatException e) {
						keep = 0;
					}
					entry = entry.substring(0, equals).strip();
				}
				if (!entry.contains(":")) {
					entry = "minecraft:" + entry;
				}
				junk.put(entry.toLowerCase(Locale.ROOT), keep);
			}

			if (!currentVersion) {
				// Datei aus der ersten Version: Totems ab jetzt standardmäßig behalten (3)
				Integer totems = junk.get(TOTEM);
				if (totems != null && totems == 0) {
					junk.put(TOTEM, 3);
				}
				saveConfig();
			}
		} catch (IOException e) {
			putDefaults();
		}
	}

	void saveConfig() {
		List<String> lines = new ArrayList<>();
		lines.add("# Junk Drop: Items, die per Hotkey gedroppt werden.");
		lines.add("# Eine Item-ID pro Zeile, zum Beispiel minecraft:ender_pearl");
		lines.add("# Mit =Zahl dahinter behältst du so viele davon (Offhand, dann Hotbar, dann Inventar),");
		lines.add("# zum Beispiel minecraft:totem_of_undying=3");
		lines.add("# Zeilen mit # am Anfang werden ignoriert.");
		lines.add(VERSION_LINE);
		for (Map.Entry<String, Integer> entry : junk.entrySet()) {
			lines.add(entry.getValue() > 0 ? entry.getKey() + "=" + entry.getValue() : entry.getKey());
		}
		try {
			Files.write(configPath, lines, StandardCharsets.UTF_8);
		} catch (IOException ignored) {
			// Konfig konnte nicht gespeichert werden: Änderungen gelten nur bis zum Neustart
		}
	}
}
