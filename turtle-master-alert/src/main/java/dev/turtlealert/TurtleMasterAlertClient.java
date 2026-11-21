package dev.turtlealert;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.Identifier;
import net.minecraft.client.player.LocalPlayer;
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
 * Zeigt eine große Meldung oben in der Mitte (grauer, leuchtender Rand, 3 Sekunden), wenn
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

	/** So lange (ms) bleibt die große Meldung mit Rand sichtbar. */
	private static final long SHOW_MS = 3000;
	/** Die letzten ms davon blendet sie weich aus. */
	private static final long FADE_MS = 700;

	private static String overlayText = null;
	private static long overlayStart = 0;

	private boolean hadEffect = false;
	private boolean lowWarned = false;
	private int previousDuration = 0;

	@Override
	public void onInitializeClient() {
		ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("turtlemasteralert", "alert"),
				(g, delta) -> renderOverlay(g));
	}

	private static void renderOverlay(GuiGraphics g) {
		if (overlayText == null) return;
		long age = System.currentTimeMillis() - overlayStart;
		if (age >= SHOW_MS) { overlayText = null; return; }
		float a = age > SHOW_MS - FADE_MS ? (SHOW_MS - age) / (float) FADE_MS : 1f;
		a = Math.max(0f, Math.min(1f, a));

		Minecraft mc = Minecraft.getInstance();
		float scale = 2.5f;
		int textW = mc.font.width(overlayText);
		int boxW = (int) (textW * scale) + 40;
		int boxH = (int) (mc.font.lineHeight * scale) + 24;
		int x = g.guiWidth() / 2 - boxW / 2;
		int y = 36;

		// Weicher grauer Leuchtrand: mehrere Rahmen, nach außen immer durchsichtiger
		for (int i = 10; i >= 1; i--) {
			float f = 1f - i / 11f;
			int col = alpha(a * f * f * 0.55f, 0xB4B4B4);
			g.fill(x - i, y - i, x + boxW + i, y - i + 1, col);                 // oben
			g.fill(x - i, y + boxH + i - 1, x + boxW + i, y + boxH + i, col);   // unten
			g.fill(x - i, y - i, x - i + 1, y + boxH + i, col);                 // links
			g.fill(x + boxW + i - 1, y - i, x + boxW + i, y + boxH + i, col);   // rechts
		}
		// Innerer feiner Rand + dunkler Hintergrund
		g.fill(x - 1, y - 1, x + boxW + 1, y + boxH + 1, alpha(a * 0.9f, 0xC8C8C8));
		g.fill(x, y, x + boxW, y + boxH, alpha(a * 0.80f, 0x101010));

		// Große Schrift
		g.pose().pushMatrix();
		g.pose().translate(x + 20, y + 12);
		g.pose().scale(scale, scale);
		g.drawString(mc.font, overlayText, 0, 0, alpha(a, 0xFFFFFF), true);
		g.pose().popMatrix();
	}

	private static int alpha(float a, int rgb) {
		int al = Math.max(0, Math.min(255, (int) (a * 255)));
		return (al << 24) | (rgb & 0xFFFFFF);
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
				show("TURTLE MASTER: nur noch ca. 1 Minute!");
			}
		} else if (hadEffect) {
			reset();
			show("TURTLE MASTER ist abgelaufen!");
		}
	}

	private void reset() {
		hadEffect = false;
		lowWarned = false;
		previousDuration = 0;
	}

	private static void show(String text) {
		overlayText = text;
		overlayStart = System.currentTimeMillis();
	}
}
