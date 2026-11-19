package dev.autototem;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;

/**
 * Client-seitige Mod, kein Server-Teil, keine Mixins.
 *
 * 1) Erscheint ein End Crystal im Umkreis von CRYSTAL_RADIUS Blöcken, wird für
 *    ARMED_TICKS Ticks "scharf geschaltet": ein Totem aus der Hotbar kommt in die
 *    Offhand, und ein weiteres Hotbar-Totem wird in die Haupthand gewählt.
 * 2) Poppt ein Totem (Totem in den Händen verschwindet und man wurde verletzt),
 *    wird ebenfalls scharf geschaltet, sodass sofort das nächste Hotbar-Totem
 *    nachrückt.
 *
 * Es wird nie das Inventar geöffnet: Gewechselt wird nur über Hotbar-Slot
 * auswählen und "Item in Offhand tauschen" (wie die F-Taste).
 * Nur Totems aus der Hotbar (Slots 1-9) werden benutzt.
 */
public class AutoTotemClient implements ClientModInitializer {

	/** Umkreis in Blöcken, in dem neue End Crystals erkannt werden. */
	private static final double CRYSTAL_RADIUS = 3.0;
	/** Wie lange nach einem Auslöser (Crystal / Pop) die Mod aktiv bleibt (20 Ticks = 1 Sekunde). */
	private static final int ARMED_TICKS = 40;
	/** Pause zwischen zwei Aktionen, damit der Server zwischendurch antworten kann. */
	private static final int ACTION_COOLDOWN = 3;
	/** Ein Crystal gilt als "frisch platziert", wenn er höchstens so viele Ticks alt ist. */
	private static final int NEW_CRYSTAL_MAX_AGE = 3;
	/** Unterhalb dieser Lebenspunkte (oder bei Treffer-Animation) zählt ein Totem-Verlust als Pop. */
	private static final float LOW_HEALTH = 6.0f;

	private Set<Integer> knownCrystals = new HashSet<>();
	private int lastHandTotems = 0;
	private int armedTicks = 0;
	private int cooldown = 0;

	@Override
	public void onInitializeClient() {
		ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
	}

	private void onTick(Minecraft client) {
		LocalPlayer player = client.player;
		ClientLevel level = client.level;

		if (player == null || level == null) {
			reset();
			return;
		}
		if (player.isSpectator()) {
			return;
		}

		// --- Totem-Pop erkennen ---
		int handTotems = countHandTotems(player);
		boolean hurt = player.hurtTime > 0 || player.getHealth() <= LOW_HEALTH;
		if (handTotems < lastHandTotems && hurt) {
			armedTicks = ARMED_TICKS;
		}
		lastHandTotems = handTotems;

		// --- Neu platzierte End Crystals erkennen ---
		AABB box = player.getBoundingBox().inflate(CRYSTAL_RADIUS);
		List<EndCrystal> crystals = level.getEntitiesOfClass(EndCrystal.class, box);
		Set<Integer> current = new HashSet<>();
		boolean placed = false;
		double radiusSq = CRYSTAL_RADIUS * CRYSTAL_RADIUS;
		for (EndCrystal crystal : crystals) {
			if (crystal.distanceToSqr(player) > radiusSq) {
				continue;
			}
			current.add(crystal.getId());
			if (crystal.tickCount <= NEW_CRYSTAL_MAX_AGE && !knownCrystals.contains(crystal.getId())) {
				placed = true;
			}
		}
		knownCrystals = current;
		if (placed) {
			armedTicks = ARMED_TICKS;
		}

		// --- Aktiv werden ---
		if (armedTicks > 0) {
			armedTicks--;
		}
		if (cooldown > 0) {
			cooldown--;
			return;
		}
		if (armedTicks <= 0 || client.screen != null || !player.isAlive()) {
			return;
		}

		Inventory inventory = player.getInventory();

		// Schritt 1: Offhand braucht ein Totem
		if (!isTotem(player.getOffhandItem())) {
			int slot = findHotbarTotem(inventory);
			if (slot >= 0) {
				selectSlot(player, slot);
				swapWithOffhand(player);
				cooldown = ACTION_COOLDOWN;
			}
			return;
		}

		// Schritt 2: In der Haupthand soll ebenfalls ein Totem liegen
		if (!isTotem(player.getMainHandItem())) {
			int slot = findHotbarTotem(inventory);
			if (slot >= 0) {
				selectSlot(player, slot);
				cooldown = ACTION_COOLDOWN;
			}
		}
	}

	private void reset() {
		knownCrystals = new HashSet<>();
		lastHandTotems = 0;
		armedTicks = 0;
		cooldown = 0;
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

	/** Sucht ein Totem in der Hotbar. Der aktuell gewählte Slot wird bevorzugt. */
	private static int findHotbarTotem(Inventory inventory) {
		int selected = inventory.getSelectedSlot();
		if (isTotem(inventory.getItem(selected))) {
			return selected;
		}
		for (int i = 0; i < 9; i++) {
			if (isTotem(inventory.getItem(i))) {
				return i;
			}
		}
		return -1;
	}

	private static void selectSlot(LocalPlayer player, int slot) {
		Inventory inventory = player.getInventory();
		if (inventory.getSelectedSlot() != slot) {
			inventory.setSelectedSlot(slot);
			// Server sofort informieren, damit der folgende Tausch den richtigen Slot trifft
			player.connection.send(new ServerboundSetCarriedItemPacket(slot));
		}
	}

	/** Entspricht der F-Taste: Haupthand und Offhand tauschen. Kein Inventar nötig. */
	private static void swapWithOffhand(LocalPlayer player) {
		player.connection.send(new ServerboundPlayerActionPacket(
				ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND,
				BlockPos.ZERO,
				Direction.DOWN));
	}
}
