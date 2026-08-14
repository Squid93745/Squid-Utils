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
 * The full chain behind one multi-step table row, styled like SkyHanni's
 * visitor shopping list: everything bought raw at the top with icon, name,
 * quantity and cost, then every fusion needed, dependency-first, with
 * clickable shard names exactly like the overlay panels use.
 *
 * <p>The route is recomputed on every render rather than cached at open time,
 * the same way every other screen in the mod reads live state. That is cheap
 * here: {@link RouteSolver#explain} only walks the one small subtree this
 * route touches, not the full recipe graph.
 */
public class MultiStepScreen extends Screen {

    private static final int[] MULTIPLIERS = {1, 8, 16, 32, 64};
    private static final int ICON = 9;

    private record Hit(int x, int y, int w, int h, String shard, int units) {
        boolean contains(double mx, double my) {
            return mx >= x && mx <= x + w && my >= y && my <= y + h;
        }
    }

    private record MultButton(int x, int y, int w, int h, int value) {
        boolean contains(double mx, double my) {
            return mx >= x && mx <= x + w && my >= y && my <= y + h;
        }
    }

    private final Screen previous;
    private final int rootRecipe;
    private final List<Hit> hits = new ArrayList<>();
    private final List<MultButton> multButtons = new ArrayList<>();
    private int multiplier = 1;
    private int scroll = 0;
    private int contentHeight = 0;

    public MultiStepScreen(Screen previous, int rootRecipe) {
        super(Component.literal("Fusion route"));
        this.previous = previous;
        this.rootRecipe = rootRecipe;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
        g.fill(0, 0, width, height, 0xE0101018);
        hits.clear();
        multButtons.clear();

        var engine = SquidUtils.engine();
        var costs = engine == null ? null : engine.routeCosts();
        if (engine == null || costs == null) {
            String msg = "Still calculating - try again in a moment";
            g.text(font, msg, (width - font.width(msg)) / 2, height / 2, Draw.DIM);
            return;
        }

        FusionData data = engine.data();
        var route = RouteSolver.explain(data, costs, rootRecipe, multiplier);
        var result = data.shard(data.result(rootRecipe));
        int qty = data.qty(rootRecipe) * multiplier;
        double total = RouteSolver.routeCost(data, costs, route);
        int lineH = font.lineHeight + 3;

        int y = 20 - scroll;
        drawCentered(g, qty + "x " + result.name() + "  (" + Draw.coins(total) + ")", y, Draw.TITLE);
        y += lineH + 8;

        // Quantity presets scale every number below at once, the same idea as
        // holding a number key to pick a stack size - useful when you are
        // stocking up rather than doing the fusion exactly once.
        y = drawMultiplierRow(g, y);
        y += 8;

        for (var buy : route.buys()) {
            var s = data.shard(buy.shardIndex());
            double cost = costs.cost()[buy.shardIndex()] * buy.units();
            y = buyRow(g, y, s, buy.units(), cost, lineH);
        }
        y += 10;

        g.text(font, "Fuse, in order", 20, y, Draw.TITLE);
        y += lineH;
        for (var step : route.steps()) {
            int r = step.recipeIndex();
            var sa = data.shard(data.inputA(r));
            var sb = data.shard(data.inputB(r));
            var sr = data.shard(data.result(r));
            boolean same = data.inputA(r) == data.inputB(r);

            int x = 20;
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
            y += lineH;
        }

        contentHeight = y + scroll - 20;

        if (route.truncated()) {
            String warn = "(route runs deep - showing as much as fits safely)";
            drawCentered(g, warn, height - font.lineHeight * 2 - 8, Draw.DIM);
        }
        String help = "Click a name to view it on the bazaar, or again to fill a bazaar order sign  ·  Esc to close";
        g.text(font, help, (width - font.width(help)) / 2, height - font.lineHeight - 6, Draw.DIM);
    }

    private int drawMultiplierRow(GuiGraphicsExtractor g, int y) {
        String label = "Buy for: ";
        int x = 20;
        g.text(font, label, x, y, Draw.DIM);
        x += font.width(label) + 4;

        for (int m : MULTIPLIERS) {
            String text = m + "x";
            int w = font.width(text) + 6;
            boolean active = m == multiplier;
            g.fill(x, y - 1, x + w, y + font.lineHeight + 1, active ? 0x80B86BFF : 0x30FFFFFF);
            g.text(font, text, x + 3, y, active ? 0xFFFFFFFF : Draw.DIM);
            multButtons.add(new MultButton(x, y - 1, w, font.lineHeight + 2, m));
            x += w + 4;
        }
        return y + font.lineHeight + 2;
    }

    private void drawCentered(GuiGraphicsExtractor g, String text, int y, int colour) {
        g.text(font, text, (width - font.width(text)) / 2, y, colour);
    }

    /** A shopping-list line: icon, name, quantity and cost - the whole row is
     *  one click target, both to open the bazaar and to arm the sign fill. */
    private int buyRow(GuiGraphicsExtractor g, int y, FusionData.Shard s, int units, double cost, int lineH) {
        int x = 20;
        drawIcon(g, x, y - 1, ICON, s);
        x += ICON + 4;

        String line = s.name() + " x" + units + "  (" + Draw.coins(cost) + ")";
        g.text(font, line, x, y, 0xFF7FD4FF);
        hits.add(new Hit(20, y - 1, ICON + 4 + font.width(line), font.lineHeight + 2, s.name(), units));
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

    /** A fuse-step shard name: clickable for the bazaar, same as before -
     *  these are made, not bought, so they do not arm the sign fill. */
    private int name(GuiGraphicsExtractor g, int x, int y, FusionData.Shard s) {
        g.text(font, s.name(), x, y, 0xFF7FD4FF);
        int w = font.width(s.name());
        hits.add(new Hit(x, y, w, font.lineHeight, s.name(), -1));
        return x + w;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        for (MultButton b : multButtons) {
            if (b.contains(event.x(), event.y())) {
                multiplier = b.value();
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
        int maxScroll = Math.max(0, contentHeight - (height - 40));
        scroll = Math.max(0, Math.min(maxScroll, scroll - (int) (scrollY * 16)));
        return true;
    }

    /** Closing returns to whatever menu this was opened over, matching the
     *  overlay editor - landing at the desktop would mean reopening the whole
     *  menu just to look at a different row. */
    @Override
    public void onClose() {
        if (minecraft != null) minecraft.setScreen(previous);
    }
}
