package dev.autototem;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Client-seitige Mod, kein Server-Teil, keine Mixins.
 *
 * Ein Totem aus der Hotbar kommt in die Offhand, sobald
 *   1) dein Totem gepoppt ist (Totem in den Händen weg und du wurdest getroffen), oder
 *   2) du unter einem ganzen Herz bist (weniger als LOW_HEALTH Lebenspunkte).
 *
 * Es wird nie das Inventar geöffnet: Der Server bekommt "Slot wählen", "Item in die
 * Offhand tauschen" (wie die F-Taste) und "alten Slot wieder wählen". Du hältst
 * danach also wieder dasselbe Item in der Hand wie vorher.
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

		if (player == null || level == null) {
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

		moveTotemToOffhand(player, slot);
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

	/**
	 * Slot wählen, mit der Offhand tauschen, alten Slot wieder wählen. Alles in einem Tick,
	 * ohne Inventar. Die Auswahl auf deinem Bildschirm ändert sich dabei nicht.
	 */
	private static void moveTotemToOffhand(LocalPlayer player, int slot) {
		int previous = player.getInventory().getSelectedSlot();

		if (slot != previous) {
			player.connection.send(new ServerboundSetCarriedItemPacket(slot));
		}
		player.connection.send(new ServerboundPlayerActionPacket(
				ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND,
				BlockPos.ZERO,
				Direction.DOWN));
		if (slot != previous) {
			player.connection.send(new ServerboundSetCarriedItemPacket(previous));
		}
	}
}
