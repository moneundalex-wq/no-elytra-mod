package dev.tiktokinventory;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.dimaskama.mcef.api.MCEFApi;
import net.dimaskama.mcef.api.MCEFBrowser;
import net.minecraft.client.gui.GuiGraphics;
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

	public TikTokScreen(Screen parent) {
		super(Component.literal("TikTok"));
		this.parent = parent;
	}

	private boolean ready() {
		return MCEFApi.initialize().isDone();
	}

	private void ensureBrowser() {
		if (browser == null && ready()) {
			browser = MCEFApi.getInstance().createBrowser(URL, false);
			browser.resize(width, height);
			browser.setFocus(true);
		}
	}

	@Override
	protected void init() {
		ensureBrowser();
		if (browser != null) {
			browser.resize(width, height);
		}
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		ensureBrowser();
		g.fill(0, 0, width, height, 0xFF000000);
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
					0, 0, width, height,
					0.0F, 1.0F, 0.0F, 1.0F,
					0xFFFFFFFF,
					g.scissorStack.peek()));
		}
		g.requestCursor(browser.getCursorType());
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

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
		if (browser != null) browser.onMouseClicked(event, doubled);
		return true;
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (browser != null) browser.onMouseReleased(event);
		return true;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		if (browser != null) browser.onMouseScrolled((int) mouseX, (int) mouseY, verticalAmount);
		return true;
	}

	@Override
	public void mouseMoved(double x, double y) {
		if (browser != null) browser.onMouseMoved((int) x, (int) y);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.key() == 256) { // ESC
			onClose();
			return true;
		}
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
