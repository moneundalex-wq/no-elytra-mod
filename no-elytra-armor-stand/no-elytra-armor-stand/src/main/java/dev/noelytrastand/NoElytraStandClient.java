package dev.noelytrastand;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.Items;

/**
 * Client-seitige Mod: Verhindert, dass man eine Elytra per Rechtsklick auf
 * einen Armor Stand legt.
 *
 * Funktionsweise: Der Rechtsklick wird bereits auf dem Client abgebrochen,
 * bevor das Interaktions-Paket an den Server gesendet wird. Der Server bekommt
 * davon nichts mit, deshalb funktioniert die Mod auf jedem Server (und in
 * Singleplayer) ohne serverseitige Installation.
 *
 * Es werden bewusst keine Mixins verwendet, nur das Event aus der Fabric API.
 * Dadurch gibt es keine Konflikte mit Sodium oder anderen Mods.
 */
public class NoElytraStandClient implements ClientModInitializer {

	@Override
	public void onInitializeClient() {
		UseEntityCallback.EVENT.register((player, level, hand, entity, hitResult) -> {
			// Nur auf der Client-Seite eingreifen (im Singleplayer läuft das Event
			// sonst zusätzlich auf dem integrierten Server).
			if (!level.isClientSide()) {
				return InteractionResult.PASS;
			}

			if (entity instanceof ArmorStand && player.getItemInHand(hand).is(Items.ELYTRA)) {
				// FAIL: Interaktion abbrechen, kein Paket an den Server,
				// kein Platzieren, kein Tauschen mit bereits angelegtem Item.
				return InteractionResult.FAIL;
			}

			return InteractionResult.PASS;
		});
	}
}
