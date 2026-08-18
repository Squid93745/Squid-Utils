package dev.squidutils.hud;

import dev.squidutils.SquidUtils;
import dev.squidutils.fusion.data.FusionData;
import dev.squidutils.fusion.engine.FusionEngine;
import dev.squidutils.fusion.engine.RouteSolver;
import dev.squidutils.fusion.engine.Scorer;
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
 * <p>Laid out as one centred, bordered panel sized to its own content rather
 * than sprawled across the full window - the earlier full-width version put
 * a centred title over left-anchored rows, which read as broken alignment
 * more than deliberate layout, especially on a wide monitor.
 *
 * <p>The route is recomputed on every render rather than cached at open time,
 * the same way every other screen in the mod reads live state. That is cheap
 * here: {@link RouteSolver#explain} only walks the one small subtree this
 * route touches, not the full recipe graph.
 */
public class MultiStepScreen extends Screen {

    private static final int[] MULTIPLIERS = {1, 8, 16, 32, 64};
    /** Ceiling on what "Max" will ever pick, so a leg with no real
     *  depth-limit risk (a resting buy order, or every leg unconstrained)
     *  still lands on a sane number instead of an effectively-infinite one. */
    private static final int MAX_SAFE_MULTIPLIER = 999;
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
    private final int rootRecipe;
    private final List<Hit> hits = new ArrayList<>();
    private final List<Button> buttons = new ArrayList<>();
    private int multiplier = 1;
    private int scroll = 0;
    private int contentHeight = 0;
    private String flash;
    private long flashUntil;

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
        g.fill(0, 0, width, height, 0x90000000);
        hits.clear();
        buttons.clear();

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

        String titleText = qty + "x " + result.name() + "  (" + Draw.coins(total) + ")";
        String multRowText = "Buy for:  1x  8x  16x  32x  64x  Max  ";
        String helpText = "Click a name for the bazaar, again to fill an order sign  ·  Esc to close";

        // Measure first, so the panel fits its own content instead of a
        // guessed fixed width - the reference this is modelled on sizes
        // itself the same way.
        int contentW = Math.max(font.width(titleText), font.width(multRowText));
        contentW = Math.max(contentW, font.width(helpText));
        for (var buy : route.buys()) {
            contentW = Math.max(contentW, ICON + 6 + font.width(buyLineText(data, costs, buy)));
        }
        for (var step : route.steps()) {
            contentW = Math.max(contentW, font.width(fuseLineText(data, step)));
        }
        int panelW = Math.min(contentW + PAD * 2, width - 40);
        int panelX = (width - panelW) / 2;
        int contentX = panelX + PAD;

        int lineCount = 4 + route.buys().size() + route.steps().size();
        int panelH = Math.min(lineCount * lineH + 70, height - 40);
        int panelY = 20;
        // Draw.panel always draws at the origin, relying on a translated pose
        // matrix the HUD panels set up beforehand - this screen draws in raw
        // screen coordinates instead, so the fill/outline pair is inlined here.
        g.fill(panelX, panelY, panelX + panelW, panelY + panelH, Draw.BG);
        g.outline(panelX, panelY, panelW, panelH, Draw.TITLE);

        int y = panelY + PAD - scroll;
        drawCentered(g, titleText, panelX, panelW, y, Draw.TITLE);
        y += lineH + 8;

        // Quantity presets scale every number below at once, the same idea as
        // holding a number key to pick a stack size - useful when stocking up
        // rather than doing the fusion exactly once. "Max" is a fifth, dynamic
        // preset alongside the fixed ones - see safeMaxMultiplier().
        int safeMax = safeMaxMultiplier(engine, data, costs, rootRecipe);
        y = drawMultiplierRow(g, contentX, y, safeMax);
        y += 6;
        y = drawActionRow(g, contentX, y, route);
        y += 10;

        for (var buy : route.buys()) {
            y = buyRow(g, contentX, y, data, costs, buy, lineH);
        }
        y += 8;

        g.text(font, "Fuse, in order", contentX, y, Draw.TITLE);
        y += lineH;
        for (var step : route.steps()) {
            y = fuseRow(g, contentX, y, data, step, lineH);
        }

        contentHeight = y + scroll - (panelY + PAD);

        if (route.truncated()) {
            y += 6;
            g.text(font, "(route runs deep - showing as much as fits safely)", contentX, y, Draw.DIM);
        }

        drawCentered(g, helpText, panelX, panelW, panelY + panelH - font.lineHeight - 8, Draw.DIM);

        if (flash != null && System.currentTimeMillis() < flashUntil) {
            drawCentered(g, flash, panelX, panelW, panelY + panelH + 6, Draw.C_PROFIT);
        }
    }

    private int drawMultiplierRow(GuiGraphicsExtractor g, int x, int y, int safeMax) {
        String label = "Buy for: ";
        g.text(font, label, x, y, Draw.DIM);
        x += font.width(label) + 4;

        for (int m : MULTIPLIERS) {
            String text = m + "x";
            int w = font.width(text) + 6;
            boolean active = m == multiplier;
            g.fill(x, y - 1, x + w, y + font.lineHeight + 1, active ? 0x80B86BFF : 0x30FFFFFF);
            g.text(font, text, x + 3, y, active ? 0xFFFFFFFF : Draw.DIM);
            buttons.add(new Button(x, y - 1, w, font.lineHeight + 2, () -> multiplier = m));
            x += w + 4;
        }

        // A fifth, dynamic preset: however many crafts' worth of raw shards
        // the order book can actually absorb right now without the average
        // price paid sliding past Settings.depthLimitThreshold - the same
        // safety margin the shopping list panel's own [batch] button clamps
        // an over-budget line down to, just picked proactively here instead
        // of reactively after the fact. Coloured like the "batch" table
        // column (Draw.C_BATCH) rather than the fixed presets' purple, since
        // it is not a fixed quantity the way they are.
        String maxText = "Max";
        int maxW = font.width(maxText) + 6;
        boolean maxActive = multiplier == safeMax;
        g.fill(x, y - 1, x + maxW, y + font.lineHeight + 1, maxActive ? 0x806BE8C8 : 0x306BE8C8);
        g.text(font, maxText, x + 3, y, maxActive ? 0xFFFFFFFF : Draw.C_BATCH);
        buttons.add(new Button(x, y - 1, maxW, font.lineHeight + 2, () -> multiplier = safeMax));

        return y + font.lineHeight + 2;
    }

    /**
     * The largest multiplier such that every raw shard this route needs to
     * buy stays within its own {@link Scorer#buyDepthLimit} - "Max"'s whole
     * point, so clicking it (then "Add to shopping list") queues exactly as
     * much as the book can absorb right now without walking deep enough into
     * it to pay noticeably worse prices, instead of a fixed preset that might
     * be too much and only warn about it after the fact.
     *
     * <p>Solved from the multiplier-1 route rather than whichever multiplier
     * is currently selected, so picking a different preset first does not
     * change what "Max" itself resolves to. A leg with no real depth-limit
     * risk - a resting buy order, or a shard with no live price at all -
     * reports {@link Long#MAX_VALUE} from {@code buyDepthLimit} and so never
     * constrains the result, the same convention the shopping list panel's
     * own batch clamp already uses for an unpriced product.
     */
    private static int safeMaxMultiplier(FusionEngine engine, FusionData data, RouteSolver.Costs costs,
                                          int rootRecipe) {
        Scorer.Settings cfg = engine.currentSettings();
        var products = engine.products();
        var brain = engine.brain();
        var base = RouteSolver.explain(data, costs, rootRecipe, 1);

        long max = MAX_SAFE_MULTIPLIER;
        for (var buy : base.buys()) {
            if (buy.units() <= 0) continue;
            var s = data.shard(buy.shardIndex());
            var product = products.get(s.tag());
            long safe = product != null
                    ? Scorer.buyDepthLimit(product, cfg, brain.reference(s.tag()))
                    : Long.MAX_VALUE;
            if (safe == Long.MAX_VALUE) continue;
            max = Math.min(max, safe / buy.units());
        }
        return (int) Math.max(1, Math.min(max, MAX_SAFE_MULTIPLIER));
    }

    /** Adding is the only action left here - viewing the list is now the
     *  persistent shopping list panel itself, moveable like every other
     *  panel, rather than a screen you navigate to see it. */
    private int drawActionRow(GuiGraphicsExtractor g, int x, int y, RouteSolver.Route route) {
        actionButton(g, x, y, "+ Add to shopping list", Draw.C_PROFIT, () -> {
            ShoppingList.addRoute(route);
            flash("Added to shopping list");
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

    private String buyLineText(FusionData data, RouteSolver.Costs costs, RouteSolver.Buy buy) {
        var s = data.shard(buy.shardIndex());
        double cost = costs.cost()[buy.shardIndex()] * buy.units();
        return s.name() + " x" + buy.units() + "  (" + Draw.coins(cost) + ")";
    }

    /** A shopping-list line: icon, name, quantity and cost - the whole row is
     *  one click target, both to open the bazaar and to arm the sign fill. */
    private int buyRow(GuiGraphicsExtractor g, int x, int y, FusionData data, RouteSolver.Costs costs,
                       RouteSolver.Buy buy, int lineH) {
        var s = data.shard(buy.shardIndex());
        double cost = costs.cost()[buy.shardIndex()] * buy.units();
        drawIcon(g, x, y - 1, ICON, s);

        String line = buyLineText(data, costs, buy);
        g.text(font, line, x + ICON + 6, y, 0xFF7FD4FF);
        hits.add(new Hit(x, y - 1, ICON + 6 + font.width(line), font.lineHeight + 2, s.name(), buy.units()));
        return y + lineH;
    }

    private String fuseLineText(FusionData data, RouteSolver.Step step) {
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

    private int fuseRow(GuiGraphicsExtractor g, int x0, int y, FusionData data, RouteSolver.Step step, int lineH) {
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

    /** Closing returns to whatever menu this was opened over, matching the
     *  overlay editor - landing at the desktop would mean reopening the whole
     *  menu just to look at a different row. */
    @Override
    public void onClose() {
        if (minecraft != null) minecraft.setScreen(previous);
    }
}
