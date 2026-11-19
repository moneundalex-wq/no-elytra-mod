package dev.autototem;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Client-seitige Mod, kein Server-Teil, keine Mixins.
 *
 * Genau ein Auslöser: Dein Totem ist gepoppt. Dann wird EINMAL auf ein Totem in deiner
 * Hotbar gewechselt (Haupthand), falls du keins in der Haupthand hältst. Danach tut die Mod
 * nichts mehr, bis das nächste Totem poppt. Sie hält dich nie fest: Du kannst sofort wieder
 * auf jeden anderen Slot wechseln, es wird nichts erneut erzwungen.
 *
 * Es wird nichts in die Offhand gelegt und nie ein Inventar geöffnet. Es werden nur Totems
 * aus der Hotbar (Slots 1-9) genutzt.
 *
 * Pop-Erkennung: Beim Pop gibt der Server dir Regeneration II (45 s), Absorption und
 * Feuerresistenz. Taucht dieses frische Regeneration-II-Paket auf, ist ein Totem gepoppt.
 */
public class AutoTotemClient implements ClientModInitializer {

	/** So viele Ticks nach einem Pop wird auf die aktualisierte Hand gewartet (20 Ticks = 1 Sekunde). */
	private static final int PENDING_TICKS = 6;
	/** Ein frischer Totem-Pop gibt Regeneration II mit 900 Ticks. Ab dieser Restdauer gilt sie als frisch. */
	private static final int FRESH_REGEN_MIN_TICKS = 850;

	private boolean hadPopEffects = false;
	private int pendingTicks = 0;

	@Override
	public void onInitializeClient() {
		ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
	}

	private void onTick(Minecraft client) {
		LocalPlayer player = client.player;
		ClientLevel level = client.level;

		if (player == null || level == null) {
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

		// Haupthand hält schon ein Totem: nichts zu tun
		if (isTotem(player.getMainHandItem())) {
			return;
		}

		int slot = findHotbarTotem(player.getInventory());
		if (slot < 0) {
			pendingTicks = 0;
			return;
		}

		// Einmal auf das Totem wechseln, danach ist dieser Pop erledigt.
		pendingTicks = 0;
		player.getInventory().setSelectedSlot(slot);
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

	/** Sucht das erste Totem in der Hotbar (Slots 0-8). */
	private static int findHotbarTotem(Inventory inventory) {
		for (int i = 0; i < 9; i++) {
			if (isTotem(inventory.getItem(i))) {
				return i;
			}
		}
		return -1;
	}
}
