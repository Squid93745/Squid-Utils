package dev.squidutils.hud;

import dev.squidutils.SquidUtils;
import dev.squidutils.fusion.data.FusionData;
import dev.squidutils.fusion.engine.RouteSolver;
import dev.squidutils.fusion.hud.ShardIcons;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * The shopping list's own "fuse, in order" view - every fusion step
 * accumulated across every route ever added to the list, combined and
 * dependency-ordered the same way {@link MultiStepScreen} orders one route,
 * styled identically to it since this is the same idea at a wider scope: not
 * "how do I make one shard", but "how do I work through everything I have
 * queued up".
 *
 * <p>Reads {@link ShoppingList} live rather than a snapshot taken at open
 * time, matching every other screen in the mod - removing a row from the
 * list while this is open updates it on the next frame.
 */
public class ShoppingRouteScreen extends Screen {

    private static final int ICON = 10;
    private static final int PAD = 20;

    private record Hit(int x, int y, int w, int h, String shard, int units) {
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
    private int scroll = 0;
    private int contentHeight = 0;
    private String flash;
    private long flashUntil;

    public ShoppingRouteScreen(Screen previous) {
        super(Component.literal("Shopping list route"));
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

        var engine = SquidUtils.engine();
        var costs = engine == null ? null : engine.routeCosts();
        var entries = ShoppingList.entries();
        var steps = ShoppingList.steps();

        if (engine == null || costs == null) {
            String msg = "Still calculating - try again in a moment";
            g.text(font, msg, (width - font.width(msg)) / 2, height / 2, Draw.DIM);
            return;
        }
        if (entries.isEmpty() && steps.isEmpty()) {
            String msg = "Shopping list is empty";
            g.text(font, msg, (width - font.width(msg)) / 2, height / 2, Draw.DIM);
            return;
        }

        FusionData data = engine.data();
        double total = 0;
        for (var e : entries) total += costs.cost()[e.shardIndex()] * e.units();

        String titleText = "Shopping list  (" + Draw.coins(total) + ")";
        String helpText = "Click a name for the bazaar, again to fill an order sign  ·  Esc to close";

        int contentW = Math.max(font.width(titleText), font.width(helpText));
        for (var e : entries) {
            contentW = Math.max(contentW, ICON + 6 + font.width(buyLineText(data, costs, e)));
        }
        for (var step : steps) {
            contentW = Math.max(contentW, font.width(fuseLineText(data, step)));
        }
        int panelW = Math.min(contentW + PAD * 2, width - 40);
        int panelX = (width - panelW) / 2;
        int contentX = panelX + PAD;

        int lineCount = 4 + entries.size() + steps.size();
        int panelH = Math.min(lineCount * (font.lineHeight + 3) + 70, height - 40);
        int panelY = 20;
        g.fill(panelX, panelY, panelX + panelW, panelY + panelH, Draw.BG);
        g.outline(panelX, panelY, panelW, panelH, Draw.TITLE);

        int lineH = font.lineHeight + 3;
        int y = panelY + PAD - scroll;
        drawCentered(g, titleText, panelX, panelW, y, Draw.TITLE);
        y += lineH + 8;

        y = drawActionRow(g, contentX, y);
        y += 10;

        if (!entries.isEmpty()) {
            for (var e : entries) {
                y = buyRow(g, contentX, y, data, costs, e, lineH);
            }
            y += 8;
        }

        if (!steps.isEmpty()) {
            g.text(font, "Fuse, in order", contentX, y, Draw.TITLE);
            y += lineH;
            for (var step : steps) {
                y = fuseRow(g, contentX, y, data, step, lineH);
            }
        }

        contentHeight = y + scroll - (panelY + PAD);

        drawCentered(g, helpText, panelX, panelW, panelY + panelH - font.lineHeight - 8, Draw.DIM);

        if (flash != null && System.currentTimeMillis() < flashUntil) {
            drawCentered(g, flash, panelX, panelW, panelY + panelH + 6, Draw.C_PROFIT);
        }
    }

    private int drawActionRow(GuiGraphicsExtractor g, int x, int y) {
        actionButton(g, x, y, "Clear list", 0xFFFF6666, () -> {
            ShoppingList.clear();
            flash("Cleared");
        });
        return y + font.lineHeight + 2;
    }

    private int actionButton(GuiGraphicsExtractor g, int x, int y, String text, int colour, Runnable action) {
        int w = font.width(text) + 10;
        int h = font.lineHeight + 4;
        g.fill(x, y - 1, x + w, y - 1 + h, 0x30FFFFFF);
        g.outline(x, y - 1, w, h, colour);
        g.text(font, text, x + 5, y, colour);
        buttons.add(new Button(x, y - 1, w, h, action));
        return x + w;
    }

    private void flash(String message) {
        flash = message;
        flashUntil = System.currentTimeMillis() + 2000;
    }

    private void drawCentered(GuiGraphicsExtractor g, String text, int panelX, int panelW, int y, int colour) {
        g.text(font, text, panelX + (panelW - font.width(text)) / 2, y, colour);
    }

    private String buyLineText(FusionData data, RouteSolver.Costs costs, ShoppingList.Entry e) {
        var s = data.shard(e.shardIndex());
        double cost = costs.cost()[e.shardIndex()] * e.units();
        return s.name() + " x" + e.units() + "  (" + Draw.coins(cost) + ")";
    }

    private int buyRow(GuiGraphicsExtractor g, int x, int y, FusionData data, RouteSolver.Costs costs,
                       ShoppingList.Entry e, int lineH) {
        var s = data.shard(e.shardIndex());
        drawIcon(g, x, y - 1, ICON, s);

        String line = buyLineText(data, costs, e);
        g.text(font, line, x + ICON + 6, y, 0xFF7FD4FF);
        hits.add(new Hit(x, y - 1, ICON + 6 + font.width(line), font.lineHeight + 2, s.name(), e.units()));
        return y + lineH;
    }

    private String fuseLineText(FusionData data, ShoppingList.StepEntry step) {
        int r = step.recipeIndex();
        var sa = data.shard(data.inputA(r));
        var sb = data.shard(data.inputB(r));
        var sr = data.shard(data.result(r));
        boolean same = data.inputA(r) == data.inputB(r);
        String prefix = step.crafts() > 1 ? step.crafts() + "×  " : "";
        String line = same
                ? (sa.fuseAmount() + sb.fuseAmount()) + "x " + sa.name()
                : sa.fuseAmount() + "x " + sa.name() + " + " + sb.fuseAmount() + "x " + sb.name();
        return prefix + line + " → " + data.qty(r) + "x " + sr.name();
    }

    private int fuseRow(GuiGraphicsExtractor g, int x0, int y, FusionData data, ShoppingList.StepEntry step, int lineH) {
        int r = step.recipeIndex();
        var sa = data.shard(data.inputA(r));
        var sb = data.shard(data.inputB(r));
        var sr = data.shard(data.result(r));
        boolean same = data.inputA(r) == data.inputB(r);

        int x = x0;
        if (step.crafts() > 1) {
            String prefix = step.crafts() + "×  ";
            g.text(font, prefix, x, y, Draw.DIM);
            x += font.width(prefix);
        }
        if (same) {
            x = plain(g, x, y, (sa.fuseAmount() + sb.fuseAmount()) + "x ");
            x = name(g, x, y, sa);
        } else {
            x = plain(g, x, y, sa.fuseAmount() + "x ");
            x = name(g, x, y, sa);
            x = plain(g, x, y, " + " + sb.fuseAmount() + "x ");
            x = name(g, x, y, sb);
        }
        x = plain(g, x, y, " → " + data.qty(r) + "x ");
        name(g, x, y, sr);
        return y + lineH;
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

    private int plain(GuiGraphicsExtractor g, int x, int y, String s) {
        g.text(font, s, x, y, 0xFFFFFFFF);
        return x + font.width(s);
    }

    private int name(GuiGraphicsExtractor g, int x, int y, FusionData.Shard s) {
        g.text(font, s.name(), x, y, 0xFF7FD4FF);
        int w = font.width(s.name());
        hits.add(new Hit(x, y, w, font.lineHeight, s.name(), -1));
        return x + w;
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
            if (h.contains(event.x(), event.y())) {
                if (h.units() > 0) SignFill.remember(h.shard(), h.units());
                SquidUtils.openBazaar(h.shard());
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int maxScroll = Math.max(0, contentHeight - (height - 80));
        scroll = Math.max(0, Math.min(maxScroll, scroll - (int) (scrollY * 16)));
        return true;
    }

    @Override
    public void onClose() {
        if (minecraft != null) minecraft.setScreen(previous);
    }
}
