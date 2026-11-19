package dev.autototem;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Client-seitige Mod, kein Server-Teil, keine Mixins.
 *
 * Ein Totem aus der Hotbar kommt in die Offhand, sobald
 *   1) dein Totem gepoppt ist (Totem in den Händen weg und du wurdest getroffen), oder
 *   2) du unter einem ganzen Herz bist (weniger als LOW_HEALTH Lebenspunkte).
 *
 * Wichtig: Deine Hotbar-Auswahl wird NIE verändert. Das Totem wird direkt aus seinem
 * Hotbar-Slot in die Offhand getauscht (derselbe Klick wie "Maus über Item + F-Taste"),
 * ohne dass ein Inventar-Fenster geöffnet wird. Du kannst jederzeit frei zwischen
 * den Slots wechseln, auch auf Totem-Slots.
 * Nur Totems aus der Hotbar (Slots 1-9) werden benutzt.
 */
public class AutoTotemClient implements ClientModInitializer {

	/** Lebenspunkte-Grenze: 2.0 = ein ganzes Herz. Alles darunter (z. B. ein halbes Herz = 1.0) löst aus. */
	private static final float LOW_HEALTH = 2.0f;
	/** Ab dieser Lebenszahl (oder bei Treffer-Animation) zählt ein Totem-Verlust als Pop. */
	private static final float POP_CHECK_HEALTH = 6.0f;
	/** Wie lange nach einem Pop nachgefüllt wird (20 Ticks = 1 Sekunde). */
	private static final int POP_REFILL_TICKS = 40;
	/** Pause zwischen zwei Tausch-Versuchen, damit der Server zwischendurch antworten kann. */
	private static final int ACTION_COOLDOWN = 3;

	/** In der Inventar-Ansicht des Spielers beginnen die Hotbar-Slots bei Nummer 36. */
	private static final int HOTBAR_MENU_OFFSET = 36;
	/** Mausknopf-Wert 40 bedeutet bei ClickType.SWAP "mit der Offhand tauschen". */
	private static final int OFFHAND_SWAP_BUTTON = 40;

	private int lastHandTotems = 0;
	private int popTicks = 0;
	private int cooldown = 0;

	@Override
	public void onInitializeClient() {
		ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
	}

	private void onTick(Minecraft client) {
		LocalPlayer player = client.player;
		ClientLevel level = client.level;
		MultiPlayerGameMode gameMode = client.gameMode;

		if (player == null || level == null || gameMode == null) {
			lastHandTotems = 0;
			popTicks = 0;
			cooldown = 0;
			return;
		}
		if (player.isSpectator()) {
			return;
		}

		// --- Totem-Pop erkennen ---
		int handTotems = countHandTotems(player);
		boolean hurt = player.hurtTime > 0 || player.getHealth() <= POP_CHECK_HEALTH;
		if (handTotems < lastHandTotems && hurt) {
			popTicks = POP_REFILL_TICKS;
		}
		lastHandTotems = handTotems;
		if (popTicks > 0) {
			popTicks--;
		}

		if (cooldown > 0) {
			cooldown--;
			return;
		}
		// Kein Eingriff, solange du selbst ein Inventar/Menü offen hast
		if (client.screen != null || !player.isAlive()) {
			return;
		}

		// --- Auslöser: Pop oder unter einem Herz ---
		boolean lowHealth = player.getHealth() < LOW_HEALTH;
		if (!lowHealth && popTicks <= 0) {
			return;
		}

		// Offhand hat schon ein Totem: nichts zu tun
		if (isTotem(player.getOffhandItem())) {
			return;
		}

		int slot = findHotbarTotem(player.getInventory());
		if (slot < 0) {
			return;
		}

		// Totem direkt aus dem Hotbar-Slot in die Offhand tauschen. Hand-Auswahl bleibt unberührt.
		gameMode.handleInventoryMouseClick(
				player.inventoryMenu.containerId,
				HOTBAR_MENU_OFFSET + slot,
				OFFHAND_SWAP_BUTTON,
				ClickType.SWAP,
				player);
		cooldown = ACTION_COOLDOWN;
	}

	private static boolean isTotem(ItemStack stack) {
		return stack.is(Items.TOTEM_OF_UNDYING);
	}

	private static int countHandTotems(LocalPlayer player) {
		int count = 0;
		if (isTotem(player.getMainHandItem())) {
			count++;
		}
		if (isTotem(player.getOffhandItem())) {
			count++;
		}
		return count;
	}

	/** Sucht ein Totem in der Hotbar. Slots, die du gerade nicht in der Hand hältst, werden bevorzugt. */
	private static int findHotbarTotem(Inventory inventory) {
		int selected = inventory.getSelectedSlot();
		for (int i = 0; i < 9; i++) {
			if (i != selected && isTotem(inventory.getItem(i))) {
				return i;
			}
		}
		if (isTotem(inventory.getItem(selected))) {
			return selected;
		}
		return -1;
	}
}
