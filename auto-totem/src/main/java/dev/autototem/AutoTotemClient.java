package dev.autototem;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Client-seitige Mod, kein Server-Teil, keine Mixins.
 *
 * Genau ein Auslöser: Dein Totem ist gepoppt. Dann wird EINMAL ein Totem aus der Hotbar
 * in die Offhand getauscht, falls deine Offhand danach leer ist. Danach tut die Mod nichts
 * mehr, bis das nächste Totem poppt. Sie hält dich nie fest: Du kannst das Totem sofort
 * wieder aus der Offhand nehmen, auf jeden Slot wechseln, alles. Es wird nichts erneut
 * erzwungen.
 *
 * Deine Hotbar-Auswahl wird nie verändert. Das Totem wird direkt aus seinem Hotbar-Slot
 * in die Offhand getauscht (derselbe Klick wie "Maus über Item + F-Taste"), ohne dass ein
 * Inventar-Fenster geöffnet wird. Nur Totems aus der Hotbar (Slots 1-9) werden benutzt.
 *
 * Pop-Erkennung: Beim Pop gibt der Server dir Regeneration II (45 s), Absorption und
 * Feuerresistenz. Taucht dieses frische Regeneration-II-Paket auf, ist ein Totem gepoppt.
 */
public class AutoTotemClient implements ClientModInitializer {

	/** So viele Ticks nach einem Pop wird auf die leere Offhand gewartet (20 Ticks = 1 Sekunde). */
	private static final int PENDING_TICKS = 10;
	/** Ein frischer Totem-Pop gibt Regeneration II mit 900 Ticks. Ab dieser Restdauer gilt sie als frisch. */
	private static final int FRESH_REGEN_MIN_TICKS = 850;

	/** In der Inventar-Ansicht des Spielers beginnen die Hotbar-Slots bei Nummer 36. */
	private static final int HOTBAR_MENU_OFFSET = 36;
	/** Mausknopf-Wert 40 bedeutet bei ClickType.SWAP "mit der Offhand tauschen". */
	private static final int OFFHAND_SWAP_BUTTON = 40;

	private boolean hadPopEffects = false;
	private int pendingTicks = 0;

	@Override
	public void onInitializeClient() {
		ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
	}

	private void onTick(Minecraft client) {
		LocalPlayer player = client.player;
		ClientLevel level = client.level;
		MultiPlayerGameMode gameMode = client.gameMode;

		if (player == null || level == null || gameMode == null) {
			hadPopEffects = false;
			pendingTicks = 0;
			return;
		}
		if (player.isSpectator()) {
			return;
		}

		// --- Totem-Pop erkennen (nur der Moment, in dem das Effekt-Paket neu auftaucht) ---
		boolean popEffects = hasFreshPopEffects(player);
		if (popEffects && !hadPopEffects) {
			pendingTicks = PENDING_TICKS;
		}
		hadPopEffects = popEffects;

		if (pendingTicks <= 0) {
			return;
		}
		pendingTicks--;

		// Kein Eingriff, solange du selbst ein Inventar/Menü offen hast
		if (client.screen != null || !player.isAlive()) {
			return;
		}

		// Offhand schon belegt (z. B. das andere Totem ist gepoppt): nichts tun
		if (isTotem(player.getOffhandItem())) {
			return;
		}

		int slot = findHotbarTotem(player.getInventory());
		if (slot < 0) {
			pendingTicks = 0;
			return;
		}

		// Einmal tauschen, danach ist dieser Pop erledigt. Hand-Auswahl bleibt unberührt.
		pendingTicks = 0;
		gameMode.handleInventoryMouseClick(
				player.inventoryMenu.containerId,
				HOTBAR_MENU_OFFSET + slot,
				OFFHAND_SWAP_BUTTON,
				ClickType.SWAP,
				player);
	}

	/** Frische Regeneration II (ca. 900 Ticks) plus Absorption: typisches Paket nach einem Totem-Pop. */
	private static boolean hasFreshPopEffects(LocalPlayer player) {
		MobEffectInstance regen = player.getEffect(MobEffects.REGENERATION);
		MobEffectInstance absorption = player.getEffect(MobEffects.ABSORPTION);
		return regen != null
				&& absorption != null
				&& regen.getAmplifier() == 1
				&& regen.getDuration() >= FRESH_REGEN_MIN_TICKS;
	}

	private static boolean isTotem(ItemStack stack) {
		return stack.is(Items.TOTEM_OF_UNDYING);
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
