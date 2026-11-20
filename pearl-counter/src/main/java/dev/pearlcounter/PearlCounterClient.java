package dev.pearlcounter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.mojang.blaze3d.platform.InputConstants;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ThrownEnderpearl;
import net.minecraft.world.phys.AABB;

/**
 * Client-seitige Mod, kein Server-Teil, keine Mixins.
 *
 * Zählt deine eigenen Enderperlen (Perlen, deren Werfer du bist). Taste U (änderbar unter
 * Steuerung > Inventar) zeigt im Chat die Zahl und die Koordinaten.
 *
 * WICHTIG, die Grenze: Dein Client weiß nur von Perlen, die der Server ihm schickt, also
 * in deiner Nähe. Perlen in weit entfernten Stasis-Kammern kennt er nicht. Deshalb merkt
 * sich die Mod Perlen, die du einmal gesehen hast ("zuletzt gesehen"). Eine gemerkte Perle
 * wird vergessen, wenn du ihre Position wieder aus der Nähe siehst und sie nicht mehr da ist.
 * Ist eine gemerkte Perle in der Zwischenzeit von jemand anderem ausgelöst worden, während du
 * weit weg warst, weiß die Mod das nicht. Die Zahl der "zuletzt gesehen"-Perlen ist also eine
 * Schätzung, die Zahl "in Sichtweite" ist exakt.
 */
public class PearlCounterClient implements ClientModInitializer {

	/** Umkreis (Blöcke), in dem nach Perlen gesucht wird. Weiter als der Server sendet, bringt nichts. */
	private static final double SEARCH_RADIUS = 256.0;
	/** Sieht man die Position einer gemerkten Perle aus dieser Nähe und sie fehlt, ist sie weg. */
	private static final double FORGET_RADIUS = 32.0;
	/** Maximal so viele Perlen werden einzeln im Chat aufgelistet. */
	private static final int MAX_LISTED = 15;

	private record Seen(double x, double y, double z, String dimension) {
	}

	private final Map<UUID, Seen> memory = new HashMap<>();
	private final Set<UUID> visible = new HashSet<>();
	private KeyMapping showKey;

	@Override
	public void onInitializeClient() {
		showKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
				"key.pearlcounter.show", InputConstants.Type.KEYSYM, InputConstants.KEY_U,
				KeyMapping.Category.INVENTORY));
		ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
	}

	private void onTick(Minecraft client) {
		LocalPlayer player = client.player;
		ClientLevel level = client.level;

		if (player == null || level == null) {
			memory.clear();
			visible.clear();
			while (showKey.consumeClick()) {
				// Tastendrücke außerhalb einer Welt verwerfen
			}
			return;
		}

		String dimension = dimensionName(level);

		// --- Sichtbare eigene Perlen erfassen ---
		visible.clear();
		AABB box = player.getBoundingBox().inflate(SEARCH_RADIUS);
		for (ThrownEnderpearl pearl : level.getEntitiesOfClass(ThrownEnderpearl.class, box)) {
			if (!isMine(pearl, player)) {
				continue;
			}
			visible.add(pearl.getUUID());
			memory.put(pearl.getUUID(), new Seen(pearl.getX(), pearl.getY(), pearl.getZ(), dimension));
		}

		// --- Gemerkte Perlen vergessen, die man aus der Nähe nicht mehr sieht ---
		double forgetSq = FORGET_RADIUS * FORGET_RADIUS;
		Iterator<Map.Entry<UUID, Seen>> it = memory.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<UUID, Seen> entry = it.next();
			if (visible.contains(entry.getKey())) {
				continue;
			}
			Seen seen = entry.getValue();
			if (!seen.dimension().equals(dimension)) {
				continue; // andere Dimension: gemerkt lassen
			}
			double dx = seen.x() - player.getX();
			double dz = seen.z() - player.getZ();
			if (dx * dx + dz * dz <= forgetSq) {
				it.remove();
			}
		}

		while (showKey.consumeClick()) {
			show(player, dimension);
		}
	}

	private void show(LocalPlayer player, String currentDimension) {
		int inSight = 0;
		int remembered = 0;
		List<String> lines = new ArrayList<>();

		for (Map.Entry<UUID, Seen> entry : memory.entrySet()) {
			Seen seen = entry.getValue();
			boolean isVisible = visible.contains(entry.getKey());
			if (isVisible) {
				inSight++;
			} else {
				remembered++;
			}

			if (lines.size() < MAX_LISTED) {
				String position = Math.round(seen.x()) + " " + Math.round(seen.y()) + " " + Math.round(seen.z());
				String distance = "";
				if (seen.dimension().equals(currentDimension)) {
					double dx = seen.x() - player.getX();
					double dy = seen.y() - player.getY();
					double dz = seen.z() - player.getZ();
					distance = ", " + Math.round(Math.sqrt(dx * dx + dy * dy + dz * dz)) + " m";
				}
				lines.add("  " + (isVisible ? "[sichtbar] " : "[zuletzt gesehen] ")
						+ position + " (" + seen.dimension() + distance + ")");
			}
		}

		int total = inSight + remembered;
		player.displayClientMessage(Component.literal(
				"Deine Enderperlen: " + total + " (" + inSight + " in Sichtweite, "
						+ remembered + " zuletzt gesehen)"), true);

		player.displayClientMessage(Component.literal(
				"Deine Enderperlen: " + total + " (" + inSight + " in Sichtweite, "
						+ remembered + " zuletzt gesehen)"), false);
		for (String line : lines) {
			player.displayClientMessage(Component.literal(line), false);
		}
		if (total > MAX_LISTED) {
			player.displayClientMessage(Component.literal("  ... und " + (total - MAX_LISTED) + " weitere"), false);
		}
		if (remembered > 0) {
			player.displayClientMessage(Component.literal(
					"Hinweis: \"zuletzt gesehen\" ist eine Schätzung. Weit entfernte Perlen sind für den Client unsichtbar."), false);
		}
	}

	private static boolean isMine(ThrownEnderpearl pearl, LocalPlayer player) {
		Entity owner = pearl.getOwner();
		return owner != null && owner.getUUID().equals(player.getUUID());
	}

	/** Kurzname der Dimension, z. B. "overworld" oder "the_nether". */
	private static String dimensionName(ClientLevel level) {
		String raw = level.dimension().toString();
		int slash = raw.lastIndexOf('/');
		String name = slash >= 0 ? raw.substring(slash + 1) : raw;
		return name.replace("]", "").trim();
	}
}
