package dev.turtlealert;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;

/**
 * Client-seitige Mod, kein Server-Teil, keine Mixins.
 *
 * Turtle Master ist in Minecraft kein eigener Effekt, sondern ein Trank, der gleichzeitig
 * Langsamkeit IV und Resistenz III gibt (die starke Variante: Langsamkeit VI und Resistenz IV).
 * Diese Mod erkennt Turtle Master daran, dass beide Effekte zusammen aktiv sind
 * (Langsamkeit ab Stufe IV, Resistenz ab Stufe III).
 *
 * Zeigt oben rechts eine Meldung (Toast), wenn
 *   1) Turtle Master nur noch ca. eine Minute läuft, und
 *   2) Turtle Master abgelaufen ist (du hast ihn nicht mehr).
 *
 * Die Warnung bei einer Minute kommt nur, wenn der Effekt mit MEHR als einer Minute
 * angefangen hat (oder verlängert wurde). Ein kurzer Effekt, der schon unter einer Minute
 * startet, löst keine sinnlose "noch eine Minute"-Meldung beim Trinken aus.
 */
public class TurtleMasterAlertClient implements ClientModInitializer {

	/** Restdauer, ab der gewarnt wird (20 Ticks = 1 Sekunde, also 1200 = 1 Minute). */
	private static final int LOW_WARNING_TICKS = 60 * 20;

	/** Turtle Master: Langsamkeit IV = Stufe 3 (0 = Stufe I). */
	private static final int MIN_SLOWNESS_AMPLIFIER = 3;
	/** Turtle Master: Resistenz III = Stufe 2 (0 = Stufe I). */
	private static final int MIN_RESISTANCE_AMPLIFIER = 2;

	private static final SystemToast.SystemToastId LOW_TOAST = new SystemToast.SystemToastId();
	private static final SystemToast.SystemToastId EMPTY_TOAST = new SystemToast.SystemToastId();

	private boolean hadEffect = false;
	private boolean lowWarned = false;
	private int previousDuration = 0;

	@Override
	public void onInitializeClient() {
		ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
	}

	private void onTick(Minecraft client) {
		LocalPlayer player = client.player;
		if (player == null) {
			reset();
			return;
		}

		MobEffectInstance slowness = player.getEffect(MobEffects.SLOWNESS);
		MobEffectInstance resistance = player.getEffect(MobEffects.RESISTANCE);

		boolean active = slowness != null
				&& resistance != null
				&& slowness.getAmplifier() >= MIN_SLOWNESS_AMPLIFIER
				&& resistance.getAmplifier() >= MIN_RESISTANCE_AMPLIFIER;

		if (active) {
			if (slowness.isInfiniteDuration() || resistance.isInfiniteDuration()) {
				// Unendlicher Effekt: nie eine "bald weg"-Warnung
				hadEffect = true;
				lowWarned = true;
				previousDuration = Integer.MAX_VALUE;
				return;
			}

			int duration = Math.min(slowness.getDuration(), resistance.getDuration());

			// Neu bekommen oder verlängert: Warnung für diesen Durchlauf neu scharf schalten,
			// aber nur, wenn der Effekt über der Warngrenze liegt.
			if (!hadEffect || duration > previousDuration) {
				lowWarned = duration <= LOW_WARNING_TICKS;
			}
			hadEffect = true;
			previousDuration = duration;

			if (!lowWarned && duration <= LOW_WARNING_TICKS) {
				lowWarned = true;
				show(client, LOW_TOAST,
						Component.literal("Turtle Master"),
						Component.literal("Nur noch ca. 1 Minute übrig!"));
			}
		} else if (hadEffect) {
			reset();
			show(client, EMPTY_TOAST,
					Component.literal("Turtle Master"),
					Component.literal("Effekt ist abgelaufen!"));
		}
	}

	private void reset() {
		hadEffect = false;
		lowWarned = false;
		previousDuration = 0;
	}

	private static void show(Minecraft client, SystemToast.SystemToastId id, Component title, Component message) {
		SystemToast.addOrUpdate(client.getToastManager(), id, title, message);
	}
}
