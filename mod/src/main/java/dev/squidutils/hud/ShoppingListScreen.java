package dev.squidutils.hud;

import dev.squidutils.SquidUtils;
import dev.squidutils.fusion.data.FusionData;
import dev.squidutils.fusion.hud.ShardIcons;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * The running shopping list built up from one or more route screens - a
 * centred, bordered panel in the same style as {@link MultiStepScreen},
 * listing every shard added so far with its total quantity and cost.
 *
 * <p>Left-click a line to open its bazaar page and arm the sign fill, same as
 * a route screen's own buy list. Right-click removes that line, for whenever
 * you have already bought something or added it by mistake.
 */
public class ShoppingListScreen extends Screen {

    private static final int ICON = 10;
    private static final int PAD = 20;

    private record Hit(int x, int y, int w, int h, int shardIndex, String shardName, int units) {
        boolean contains(double mx, double my) {
            return mx >= x && mx <= x + w && my >= y && my <= y + h;
        }
    }

    private record Button(int x, int y, int w, int h, Runnable action) {
        boolean contains(double mx, double my) {
            return mx >= x && mx <= x + w && my >= y && my <= y + h;
        }
    }

    private final Screen previous;
    private final List<Hit> hits = new ArrayList<>();
    private final List<Button> buttons = new ArrayList<>();

    public ShoppingListScreen(Screen previous) {
        super(Component.literal("Shopping list"));
        this.previous = previous;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
        g.fill(0, 0, width, height, 0x90000000);
        hits.clear();
        buttons.clear();

        var entries = ShoppingList.entries();
        var engine = SquidUtils.engine();
        FusionData data = engine == null ? null : engine.data();
        var costs = engine == null ? null : engine.routeCosts();
        int lineH = font.lineHeight + 3;

        String helpText = entries.isEmpty()
                ? "Nothing here yet - add items from a route screen's \"Add to shopping list\" button"
                : "Click a name for the bazaar, again to fill an order sign  ·  right-click to remove  ·  Esc to close";

        double total = 0;
        int contentW = Math.max(font.width("Shopping list"), font.width(helpText));
        contentW = Math.max(contentW, font.width("Clear list") + 20);
        List<String> lineTexts = new ArrayList<>();
        for (var e : entries) {
            String line = "?";
            if (data != null) {
                var s = data.shard(e.shardIndex());
                double cost = (costs != null) ? costs.cost()[e.shardIndex()] * e.units() : 0;
                total += cost;
                line = s.name() + " x" + e.units() + "  (" + Draw.coins(cost) + ")";
            }
            lineTexts.add(line);
            contentW = Math.max(contentW, ICON + 6 + font.width(line));
        }

        int panelW = Math.min(contentW + PAD * 2, width - 40);
        int panelX = (width - panelW) / 2;
        int contentX = panelX + PAD;

        int lineCount = 3 + Math.max(1, entries.size());
        int panelH = Math.min(lineCount * lineH + 60, height - 40);
        int panelY = 20;
        g.fill(panelX, panelY, panelX + panelW, panelY + panelH, Draw.BG);
        g.outline(panelX, panelY, panelW, panelH, Draw.TITLE);

        int y = panelY + PAD;
        String title = entries.isEmpty() ? "Shopping list" : "Shopping list  (" + Draw.coins(total) + ")";
        g.text(font, title, contentX + (panelW - PAD * 2 - font.width(title)) / 2, y, Draw.TITLE);
        y += lineH + 8;

        if (data != null) {
            for (int i = 0; i < entries.size(); i++) {
                var e = entries.get(i);
                var s = data.shard(e.shardIndex());
                drawIcon(g, contentX, y - 1, ICON, s);
                g.text(font, lineTexts.get(i), contentX + ICON + 6, y, 0xFF7FD4FF);
                hits.add(new Hit(contentX, y - 1, ICON + 6 + font.width(lineTexts.get(i)), font.lineHeight + 2,
                        e.shardIndex(), s.name(), e.units()));
                y += lineH;
            }
        }
        y += entries.isEmpty() ? lineH : 8;

        if (!entries.isEmpty()) {
            String clear = "Clear list";
            int w = font.width(clear) + 10;
            int h = font.lineHeight + 4;
            g.fill(contentX, y - 1, contentX + w, y - 1 + h, 0x30FFFFFF);
            g.outline(contentX, y - 1, w, h, 0xFFFF6666);
            g.text(font, clear, contentX + 5, y, 0xFFFF6666);
            buttons.add(new Button(contentX, y - 1, w, h, ShoppingList::clear));
        }

        g.text(font, helpText,
                panelX + Math.max(PAD, (panelW - font.width(helpText)) / 2),
                panelY + panelH - font.lineHeight - 8, Draw.DIM);
    }

    private void drawIcon(GuiGraphicsExtractor g, int x, int y, int size, FusionData.Shard s) {
        int idx = ShardIcons.indexOf(s.tag());
        if (idx >= 0) {
            g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED,
                    ShardIcons.atlas(), x, y,
                    ShardIcons.cellX(idx), ShardIcons.cellY(idx),
                    size, size,
                    ShardIcons.cell(), ShardIcons.cell(),
                    ShardIcons.atlasWidth(), ShardIcons.atlasHeight());
            return;
        }
        int fill = Draw.rarity(s.rarity());
        g.fill(x, y, x + size, y + size, fill);
        g.outline(x, y, size, size, 0x80000000);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        for (Button b : buttons) {
            if (b.contains(event.x(), event.y())) {
                b.action().run();
                return true;
            }
        }
        for (Hit h : hits) {
            if (!h.contains(event.x(), event.y())) continue;
            if (event.button() == 1) {
                ShoppingList.remove(h.shardIndex());
            } else {
                SignFill.remember(h.shardName(), h.units());
                SquidUtils.openBazaar(h.shardName());
            }
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public void onClose() {
        if (minecraft != null) minecraft.setScreen(previous);
    }
}
