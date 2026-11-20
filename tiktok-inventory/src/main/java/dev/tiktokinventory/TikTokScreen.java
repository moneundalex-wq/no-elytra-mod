package dev.tiktokinventory;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.dimaskama.mcef.api.MCEFApi;
import net.dimaskama.mcef.api.MCEFBrowser;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.gui.render.state.BlitRenderState;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import org.joml.Matrix3x2f;

public class TikTokScreen extends Screen {

	private static final String URL = "https://www.tiktok.com/foryou";

	private final Screen parent;
	private MCEFBrowser browser;
	private int px, py, pw, ph; // Panel in GUI-Einheiten
	private Button reloadBtn;
	private double scale = 1;   // Browser-Pixel pro GUI-Einheit

	private void layout() {
		int mx = Math.max(28, width / 12);
		int my = Math.max(8, height / 14);
		px = mx; py = my; pw = width - 2 * mx; ph = height - 2 * my;
		scale = minecraft.getWindow().getGuiScale();
	}

	private int bw() { return Math.max(1, (int) Math.round(pw * scale)); }
	private int bh() { return Math.max(1, (int) Math.round(ph * scale)); }
	private double bx(double x) { return (x - px) * scale; }
	private double by(double y) { return (y - py) * scale; }
	private boolean inside(double x, double y) { return x >= px && y >= py && x < px + pw && y < py + ph; }

	public TikTokScreen(Screen parent) {
		super(Component.literal("TikTok"));
		this.parent = parent;
	}

	private void reload() {
		if (browser != null) browser.getCefBrowser().reload();
	}

	private boolean ready() {
		return MCEFApi.initialize().isDone();
	}

	private void ensureBrowser() {
		if (browser == null && ready()) {
			browser = MCEFApi.getInstance().createBrowser(URL, false);
			layout();
			browser.resize(bw(), bh());
			browser.setFocus(true);
		}
	}

	@Override
	protected void init() {
		layout();
		reloadBtn = Button.builder(Component.literal("R"), b -> reload())
				.bounds(px + pw + 4, py, 20, 20).build();
		ensureBrowser();
		if (browser != null) {
			browser.resize(bw(), bh());
		}
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		ensureBrowser();
		// Minecraft im Hintergrund noch sichtbar, nur abgedunkelt
		parent.render(g, -1, -1, partialTick);
		g.fill(0, 0, width, height, 0x99000000);
		g.fill(px - 2, py - 2, px + pw + 2, py + ph + 2, 0xFF303030);
		g.fill(px, py, px + pw, py + ph, 0xFF101010);
		if (browser == null) {
			MCEFApi.Initialization init = MCEFApi.initialize();
			float p = init.getPercentage();
			String text = "Browser wird vorbereitet: " + init.getStage()
					+ (p >= 0 ? " " + (int) p + "%" : "") + " (ESC = zurück)";
			g.drawCenteredString(font, text, width / 2, height / 2, 0xFFFFFFFF);
			return;
		}
		GpuTextureView view = browser.getTextureView();
		if (view != null) {
			g.guiRenderState.submitGuiElement(new BlitRenderState(
					RenderPipelines.GUI_TEXTURED,
					TextureSetup.singleTexture(view, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR)),
					new Matrix3x2f(g.pose()),
					px, py, px + pw, py + ph,
					0.0F, 1.0F, 0.0F, 1.0F,
					0xFFFFFFFF,
					g.scissorStack.peek()));
		} else {
			g.drawCenteredString(font, "TikTok lädt …", width / 2, height / 2, 0xFFFFFFFF);
		}
		g.requestCursor(browser.getCursorType());
		if (reloadBtn != null) reloadBtn.render(g, mouseX, mouseY, partialTick);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void onClose() {
		minecraft.setScreen(parent);
	}

	@Override
	public void removed() {
		if (browser != null) {
			browser.close();
			browser = null;
		}
	}

	private MouseButtonEvent scaled(MouseButtonEvent e) {
		return new MouseButtonEvent(bx(e.x()), by(e.y()), e.buttonInfo());
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
		if (browser != null) {
			if (reloadBtn != null && reloadBtn.mouseClicked(event, doubled)) return true;
			if (!inside(event.x(), event.y())) { onClose(); return true; } // Klick daneben = schließen
			browser.onMouseMoved((int) bx(event.x()), (int) by(event.y()));
			browser.onMouseClicked(scaled(event), doubled);
		}
		return true;
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (browser != null) browser.onMouseReleased(scaled(event));
		return true;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (browser != null) browser.onMouseMoved((int) bx(event.x()), (int) by(event.y()));
		return true;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		if (browser != null) browser.onMouseScrolled((int) bx(mouseX), (int) by(mouseY), verticalAmount);
		return true;
	}

	@Override
	public void mouseMoved(double x, double y) {
		if (browser != null) browser.onMouseMoved((int) bx(x), (int) by(y));
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.key() == 256) { // ESC
			onClose();
			return true;
		}
		if (event.key() == 294) { reload(); return true; } // F5
		if (browser != null) browser.onKeyPressed(event);
		return true;
	}

	@Override
	public boolean keyReleased(KeyEvent event) {
		if (browser != null) browser.onKeyReleased(event);
		return true;
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		if (browser != null) browser.onCharTyped(event);
		return true;
	}
}
