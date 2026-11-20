package dev.freecam;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.Level;

/**
 * Freecam: Die Kamera wird auf ein unsichtbares Hilfs-Objekt gesetzt, das du frei bewegst.
 * Dein echter Spieler steht still (Tasten werden fuer ihn gesperrt, Blickrichtung wird zurueckgesetzt).
 * Es wird nichts an den Server gesendet, das ueber normales Stehen hinausgeht.
 */
public class FreecamClient implements ClientModInitializer {

	private static KeyMapping toggleKey;
	private static ArmorStand cam;
	private static Level camLevel;
	private static float savedYaw, savedPitch;

	@Override
	public void onInitializeClient() {
		toggleKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
				"key.freecam.toggle", InputConstants.Type.KEYSYM, 297 /* F8 */, KeyMapping.Category.MISC));

		ClientTickEvents.START_CLIENT_TICK.register(this::tick);
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("freecamlite", "status"), (g, delta) -> {
			Minecraft mc = Minecraft.getInstance();
			if (cam != null) {
				transferLook(mc);
				g.drawString(mc.font, "Freecam (Taste F8 zum Beenden)", 6, 6, 0xFFFFFFFF, true);
			}
		});
	}

	private static boolean down(Minecraft mc, KeyMapping k) {
		return InputConstants.isKeyDown(mc.getWindow(), k.getDefaultKey() == null ? -1 : boundKey(k));
	}

	private static int boundKey(KeyMapping k) {
		// aktuell belegte Taste (nicht die Standardtaste)
		String name = k.saveString();
		InputConstants.Key key = InputConstants.getKey(name);
		return key.getValue();
	}

	private void tick(Minecraft mc) {
		while (toggleKey.consumeClick()) {
			if (cam == null) start(mc); else stop(mc);
		}
		if (cam == null) return;

		LocalPlayer p = mc.player;
		if (p == null || mc.level == null || mc.level != camLevel || !p.isAlive()) {
			stop(mc);
			return;
		}

		// Echter Spieler: keine Bewegung / Aktionen
		var o = mc.options;
		for (KeyMapping k : new KeyMapping[]{o.keyUp, o.keyDown, o.keyLeft, o.keyRight, o.keyJump, o.keyShift,
				o.keySprint, o.keyAttack, o.keyUse, o.keyPickItem, o.keyDrop, o.keySwapOffhand}) {
			k.setDown(false);
			while (k.consumeClick()) { /* Klicks verwerfen */ }
		}
		transferLook(mc);

		boolean fwd = down(mc, o.keyUp), back = down(mc, o.keyDown);
		boolean left = down(mc, o.keyLeft), right = down(mc, o.keyRight);
		boolean up = down(mc, o.keyJump), dn = down(mc, o.keyShift), fast = down(mc, o.keySprint);
		double speed = fast ? 1.6 : 0.6;

		double yaw = Math.toRadians(cam.getYRot());
		double fx = -Math.sin(yaw), fz = Math.cos(yaw);
		double rx = -Math.cos(yaw), rz = -Math.sin(yaw);
		double mx = 0, my = 0, mz = 0;
		if (fwd) { mx += fx; mz += fz; }
		if (back) { mx -= fx; mz -= fz; }
		if (left) { mx += rx; mz += rz; }
		if (right) { mx -= rx; mz -= rz; }
		if (up) my += 1;
		if (dn) my -= 1;
		double len = Math.sqrt(mx * mx + my * my + mz * mz);
		cam.xo = cam.getX(); cam.yo = cam.getY(); cam.zo = cam.getZ();
		cam.xOld = cam.getX(); cam.yOld = cam.getY(); cam.zOld = cam.getZ();
		if (len > 0) {
			cam.setPos(cam.getX() + mx / len * speed, cam.getY() + my / len * speed, cam.getZ() + mz / len * speed);
		}
	}

	/** Mausbewegung (die den echten Spieler drehen wuerde) auf die Kamera uebertragen. */
	private static void transferLook(Minecraft mc) {
		LocalPlayer p = mc.player;
		if (p == null || cam == null) return;
		float dy = p.getYRot() - savedYaw;
		float dp = p.getXRot() - savedPitch;
		if (dy != 0 || dp != 0) {
			float ny = cam.getYRot() + dy;
			float np = Math.max(-90f, Math.min(90f, cam.getXRot() + dp));
			cam.setYRot(ny); cam.setXRot(np);
			cam.yRotO = ny; cam.xRotO = np;
			p.setYRot(savedYaw); p.setXRot(savedPitch);
			p.yRotO = savedYaw; p.xRotO = savedPitch;
		}
	}

	private static void start(Minecraft mc) {
		LocalPlayer p = mc.player;
		if (p == null || mc.level == null) return;
		cam = new ArmorStand(EntityType.ARMOR_STAND, mc.level);
		camLevel = mc.level;
		cam.setInvisible(true);
		cam.noPhysics = true;
		cam.setPos(p.getX(), p.getEyeY() - cam.getEyeHeight(), p.getZ());
		cam.setYRot(p.getYRot()); cam.setXRot(p.getXRot());
		cam.yRotO = p.getYRot(); cam.xRotO = p.getXRot();
		cam.xo = cam.xOld = cam.getX(); cam.yo = cam.yOld = cam.getY(); cam.zo = cam.zOld = cam.getZ();
		savedYaw = p.getYRot(); savedPitch = p.getXRot();
		mc.setCameraEntity(cam);
	}

	private static void stop(Minecraft mc) {
		cam = null;
		camLevel = null;
		if (mc.player != null) mc.setCameraEntity(mc.player);
		else mc.setCameraEntity(null);
	}
}
