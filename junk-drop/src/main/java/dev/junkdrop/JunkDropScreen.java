package dev.junkdrop;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Einstellungs-Menü für Junk Drop.
 *
 * Links: deine Junk-Liste mit Item-Symbol, Name, Behalte-Anzahl (0 = alles droppen)
 * und einem X zum Entfernen. Rechts: Suchfeld, um beliebige Items hinzuzufügen.
 * Änderungen werden beim Schließen in config/junkdrop.txt gespeichert.
 */
public class JunkDropScreen extends Screen {

	private static final int ROWS = 6;
	private static final int SEARCH_RESULTS = 7;
	private static final int TOP = 48;

	private record IconSpot(ItemStack stack, int x, int y) {
	}

	private record TextSpot(String text, int x, int y) {
	}

	private final Screen parent;
	private final JunkDropClient mod;

	private int page = 0;
	private String searchText = "";

	private final List<IconSpot> icons = new ArrayList<>();
	private final List<TextSpot> texts = new ArrayList<>();
	private final List<Button> resultButtons = new ArrayList<>();
	private final List<IconSpot> resultIcons = new ArrayList<>();

	public JunkDropScreen(Screen parent, JunkDropClient mod) {
		super(Component.literal("Junk Drop - Einstellungen"));
		this.parent = parent;
		this.mod = mod;
	}

	@Override
	protected void init() {
		icons.clear();
		texts.clear();
		resultButtons.clear();
		resultIcons.clear();

		int left = this.width / 2 - 200;
		int right = this.width / 2 + 10;

		// ---------- Linke Spalte: Junk-Liste ----------
		List<Map.Entry<String, Integer>> entries = new ArrayList<>(mod.junkMap().entrySet());
		int pages = Math.max(1, (entries.size() + ROWS - 1) / ROWS);
		page = Math.max(0, Math.min(page, pages - 1));

		for (int i = 0; i < ROWS; i++) {
			int index = page * ROWS + i;
			if (index >= entries.size()) {
				break;
			}
			String id = entries.get(index).getKey();
			int keep = entries.get(index).getValue();
			int y = TOP + i * 22;

			Item item = itemFor(id);
			ItemStack stack = item != null ? new ItemStack(item) : ItemStack.EMPTY;
			String name = item != null ? stack.getHoverName().getString() : id;
			icons.add(new IconSpot(stack, left, y + 2));
			texts.add(new TextSpot(this.font.plainSubstrByWidth(name, 100), left + 20, y + 6));

			EditBox keepBox = new EditBox(this.font, left + 124, y, 38, 20, Component.literal("Behalten"));
			keepBox.setMaxLength(4);
			keepBox.setValue(Integer.toString(keep));
			keepBox.setFilter(text -> text.isEmpty() || text.matches("\\d{1,4}"));
			keepBox.setResponder(text -> {
				int value = text.isEmpty() ? 0 : Integer.parseInt(text);
				mod.junkMap().put(id, value);
			});
			this.addRenderableWidget(keepBox);

			this.addRenderableWidget(Button.builder(Component.literal("X"), button -> {
				mod.junkMap().remove(id);
				mod.saveConfig();
				this.rebuildWidgets();
			}).bounds(left + 166, y, 20, 20).build());
		}

		int navY = TOP + ROWS * 22 + 4;
		this.addRenderableWidget(Button.builder(Component.literal("<"), button -> {
			page--;
			this.rebuildWidgets();
		}).bounds(left, navY, 20, 20).build());
		this.addRenderableWidget(Button.builder(Component.literal(">"), button -> {
			page++;
			this.rebuildWidgets();
		}).bounds(left + 166, navY, 20, 20).build());
		texts.add(new TextSpot("Seite " + (page + 1) + " / " + pages, left + 62, navY + 6));

		// ---------- Rechte Spalte: Item suchen ----------
		EditBox searchBox = new EditBox(this.font, right, TOP, 190, 20, Component.literal("Suche"));
		searchBox.setHint(Component.literal("Item suchen, z. B. totem"));
		searchBox.setValue(searchText);
		searchBox.setResponder(text -> {
			searchText = text;
			rebuildResults(right);
		});
		this.addRenderableWidget(searchBox);
		this.setInitialFocus(searchBox);
		rebuildResults(right);

		// ---------- Fertig ----------
		this.addRenderableWidget(Button.builder(Component.literal("Fertig"), button -> this.onClose())
				.bounds(this.width / 2 - 100, this.height - 28, 200, 20).build());
	}

	/** Baut die Trefferliste der Suche neu auf, ohne das Suchfeld zu verlieren. */
	private void rebuildResults(int right) {
		for (Button button : resultButtons) {
			this.removeWidget(button);
		}
		resultButtons.clear();
		resultIcons.clear();

		String query = searchText.strip().toLowerCase(Locale.ROOT);
		if (query.isEmpty()) {
			return;
		}

		int y = TOP + 26;
		int shown = 0;
		for (Item item : BuiltInRegistries.ITEM) {
			if (shown >= SEARCH_RESULTS) {
				break;
			}
			String id = BuiltInRegistries.ITEM.getKey(item).toString();
			if (id.equals("minecraft:air")) {
				continue;
			}
			ItemStack stack = new ItemStack(item);
			String name = stack.getHoverName().getString();
			if (!id.contains(query) && !name.toLowerCase(Locale.ROOT).contains(query)) {
				continue;
			}

			boolean already = mod.junkMap().containsKey(id);
			String label = this.font.plainSubstrByWidth(name, 130) + (already ? " (in Liste)" : "");
			Button button = Button.builder(Component.literal(label), b -> {
				if (!mod.junkMap().containsKey(id)) {
					mod.junkMap().put(id, 0);
					mod.saveConfig();
					searchText = "";
					this.rebuildWidgets();
				}
			}).bounds(right + 20, y, 170, 20).build();

			this.addRenderableWidget(button);
			resultButtons.add(button);
			resultIcons.add(new IconSpot(stack, right, y + 2));
			y += 22;
			shown++;
		}
	}

	private static Item itemFor(String id) {
		for (Item item : BuiltInRegistries.ITEM) {
			if (BuiltInRegistries.ITEM.getKey(item).toString().equals(id)) {
				return item;
			}
		}
		return null;
	}

	@Override
	public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
		guiGraphics.fill(0, 0, this.width, this.height, 0xC0101010);
		super.render(guiGraphics, mouseX, mouseY, partialTick);

		int left = this.width / 2 - 200;
		int right = this.width / 2 + 10;

		guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, 14, 0xFFFFFFFF);
		guiGraphics.drawString(this.font, "Junk-Liste", left, 34, 0xFFFFFFFF);
		guiGraphics.drawString(this.font, "Behalten", left + 124, 34, 0xFFAAAAAA);
		guiGraphics.drawString(this.font, "Item hinzufügen", right, 34, 0xFFFFFFFF);

		for (TextSpot text : texts) {
			guiGraphics.drawString(this.font, text.text(), text.x(), text.y(), 0xFFFFFFFF);
		}
		for (IconSpot icon : icons) {
			guiGraphics.renderItem(icon.stack(), icon.x(), icon.y());
		}
		for (IconSpot icon : resultIcons) {
			guiGraphics.renderItem(icon.stack(), icon.x(), icon.y());
		}

		guiGraphics.drawCenteredString(this.font,
				"Behalten: 0 = alles droppen. Gezählt wird: Offhand, dann Hotbar, dann Inventar.",
				this.width / 2, this.height - 42, 0xFFAAAAAA);
	}

	@Override
	public void onClose() {
		mod.saveConfig();
		this.minecraft.setScreen(parent);
	}
}
