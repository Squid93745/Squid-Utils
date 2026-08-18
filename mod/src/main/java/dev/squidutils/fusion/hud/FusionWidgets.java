package dev.squidutils.fusion.hud;

import dev.squidutils.config.FusionCategory;
import dev.squidutils.config.SquidUtilsConfig;
import dev.squidutils.config.WidgetPos;
import dev.squidutils.fusion.data.BazaarClient;
import dev.squidutils.fusion.data.Brain;
import dev.squidutils.fusion.data.FusionData;
import dev.squidutils.fusion.engine.FusionEngine;
import dev.squidutils.fusion.engine.Recommender;
import dev.squidutils.fusion.engine.RouteSolver;
import dev.squidutils.fusion.engine.Scorer;
import dev.squidutils.hud.Draw;
import dev.squidutils.hud.ShoppingList;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * The fusion overlays, drawn identically by the HUD and by the placement editor
 * so what you drag is exactly what you get.
 *
 * <p>Every figure is <em>per fuse</em> rather than per hour. Market volume is
 * the exception, because it describes the market rather than the fusion.
 *
 * <p>Stat lines are laid out as real columns, each as wide as its widest cell
 * across every row and its legend label, so the numbers line up down the panel
 * and each legend word sits under the column it names.
 */
public final class FusionWidgets {

    /** Recommended and XP per fuse each own three graphs; the four Profit
     *  Shards tables own none (see {@code FusionTablesCategory.ProfitVariant}).
     *  Table index 0 Recommended, 1-4 Profit Shards 1-4, 5 XP per fuse - the
     *  session tracker, shopping list and fuse-order breakdown share the "not
     *  a table" sentinel, so telling them apart is by identity, not field. */
    public enum Which {
        REC_TABLE(0, -1), REC_G1(0, 0), REC_G2(0, 1), REC_G3(0, 2),
        PROFIT_TABLE_1(1, -1),
        PROFIT_TABLE_2(2, -1),
        PROFIT_TABLE_3(3, -1),
        PROFIT_TABLE_4(4, -1),
        XP_TABLE(5, -1), XP_G1(5, 0), XP_G2(5, 1), XP_G3(5, 2),
        TRACKER(-1, -1),
        SHOPPING_LIST(-1, -1),
        FUSE_ORDER(-1, -1);

        public final int table;
        public final int graph;

        Which(int table, int graph) {
            this.table = table;
            this.graph = graph;
        }

        public boolean isGraph() { return graph >= 0; }
        public boolean isTracker() { return this == TRACKER; }
        public boolean isShoppingList() { return this == SHOPPING_LIST; }
        public boolean isFuseOrder() { return this == FUSE_ORDER; }
    }

    private static final int ICON = 9;
    private static final int COL_GAP = 6;
    private static final int STAT_INDENT = 14;

    /**
     * A shard name on screen, in screen-space pixels.
     *
     * <p>Collected while drawing so clicks and hovers can be resolved without
     * recomputing the layout. Rebuilt every frame; the panels move and resize.
     *
     * <p>{@code units}/{@code shardIndex} are only meaningful for a shopping
     * list row (-1 otherwise): a plain shard-name click elsewhere just opens
     * the bazaar, but a shopping list row also arms the sign fill and can be
     * right-clicked to remove, both of which need to know which shard and how
     * many - see the click handling in {@code SquidUtils}.
     */
    public record Hit(int x, int y, int w, int h, String shard, int units, int shardIndex) {
        public boolean contains(double mx, double my) {
            return mx >= x && mx <= x + w && my >= y && my <= y + h;
        }
    }

    /** A multi-step row's label, in screen-space pixels - click opens the route. */
    private record RowHit(int x, int y, int w, int h, int rootRecipe) {
        boolean contains(double mx, double my) {
            return mx >= x && mx <= x + w && my >= y && my <= y + h;
        }
    }

    /** A sortable legend cell, in screen-space pixels. */
    private record HeaderHit(int x, int y, int w, int h, int table, ColKey key) {
        boolean contains(double mx, double my) {
            return mx >= x && mx <= x + w && my >= y && my <= y + h;
        }
    }

    /** A clickable region backed by a plain callback, in screen-space pixels -
     *  the shopping list and fuse order rows' manual-delete "-" (right-click-
     *  to-remove on the shopping list had no visible affordance, and the fuse
     *  order panel had no removal gesture at all), and the tracker panel's
     *  hover-revealed pause/reset/view-mode controls. */
    private record ActionHit(int x, int y, int w, int h, Runnable action) {
        boolean contains(double mx, double my) {
            return mx >= x && mx <= x + w && my >= y && my <= y + h;
        }
    }

    private static final List<Hit> HITS = new ArrayList<>();
    private static final List<RowHit> ROW_HITS = new ArrayList<>();
    private static final List<HeaderHit> HEADER_HITS = new ArrayList<>();
    private static final List<ActionHit> ACTION_HITS = new ArrayList<>();

    /**
     * Which column each table is sorted by, and which direction - session-only
     * view state, not a setting worth persisting to config.json. Null means the
     * table's natural (engine-ranked) order, which is the default until a
     * legend cell is clicked.
     */
    private static final ColKey[] sortColumn = new ColKey[6];
    private static final boolean[] sortDescending = {true, true, true, true, true, true};

    /** Called once per frame before drawing; hit regions are per-frame. */
    public static void clearHits() {
        HITS.clear();
        ROW_HITS.clear();
        HEADER_HITS.clear();
        ACTION_HITS.clear();
    }

    public static List<Hit> hits() { return HITS; }

    /** Runs and consumes the action-region under the cursor, if any - checked
     *  first in the click handler, since e.g. a delete button can sit on top
     *  of a row that would otherwise open the bazaar. */
    public static boolean handleActionClick(double mx, double my) {
        for (int i = ACTION_HITS.size() - 1; i >= 0; i--) {
            ActionHit h = ACTION_HITS.get(i);
            if (h.contains(mx, my)) {
                h.action().run();
                return true;
            }
        }
        return false;
    }

    /** The shard name under the cursor, or null. */
    public static String shardAt(double mx, double my) {
        Hit h = hitAt(mx, my);
        return h == null ? null : h.shard();
    }

    /** The full hit under the cursor, or null - used where the caller needs
     *  more than just the name, e.g. the shopping list's units/shardIndex. */
    public static Hit hitAt(double mx, double my) {
        for (int i = HITS.size() - 1; i >= 0; i--) {
            if (HITS.get(i).contains(mx, my)) return HITS.get(i);
        }
        return null;
    }

    /**
     * The recipe a clicked multi-step row's route leads to, or -1.
     *
     * <p>This is the recipe {@link RouteSolver#explain} should expand - not
     * necessarily the row's own original recipe, since multi-step mode shows
     * whatever route {@link RouteSolver} found cheapest for that output.
     */
    public static int multiStepRowAt(double mx, double my) {
        for (int i = ROW_HITS.size() - 1; i >= 0; i--) {
            RowHit h = ROW_HITS.get(i);
            if (h.contains(mx, my)) return h.rootRecipe();
        }
        return -1;
    }

    /**
     * A sortable legend cell clicked: three states cycling on repeat clicks of
     * the same column, same as Task Manager - descending, then ascending, then
     * a third click clears back to the table's own ranking (recommendation
     * score, margin x volume, or XP per fuse, depending which table).
     *
     * @return true if a header was hit, so the caller can swallow the click.
     */
    public static boolean handleHeaderClick(double mx, double my) {
        for (int i = HEADER_HITS.size() - 1; i >= 0; i--) {
            HeaderHit h = HEADER_HITS.get(i);
            if (!h.contains(mx, my)) continue;
            int t = h.table();
            if (sortColumn[t] != h.key()) {
                sortColumn[t] = h.key();
                sortDescending[t] = true;
            } else if (sortDescending[t]) {
                sortDescending[t] = false;
            } else {
                sortColumn[t] = null;
            }
            return true;
        }
        return false;
    }

    /**
     * The Profit Shards names carry their own buy/sell mode - the only thing
     * that tells the four tables apart on screen, since otherwise they would
     * all just say "Profit Shards".
     */
    private static String tableName(SquidUtilsConfig cfg, int t) {
        var tables = cfg.fusion.tables;
        return switch (t) {
            case 1 -> "Profit Shards 1" + tradeSuffix(tables.profit1.buyMode, tables.profit1.sellMode);
            case 2 -> "Profit Shards 2" + tradeSuffix(tables.profit2.buyMode, tables.profit2.sellMode);
            case 3 -> "Profit Shards 3" + tradeSuffix(tables.profit3.buyMode, tables.profit3.sellMode);
            case 4 -> "Profit Shards 4" + tradeSuffix(tables.profit4.buyMode, tables.profit4.sellMode);
            case 5 -> "XP per fuse";
            default -> "Recommended";
        };
    }

    private static String tradeSuffix(int buyMode, int sellMode) {
        String buy = buyMode == 0 ? "Instabuy" : "Buy order";
        String sell = sellMode == 0 ? "Sell offer" : "Instasell";
        return " (" + buy + " -> " + sell + ")";
    }

    private FusionWidgets() {}

    public static WidgetPos pos(SquidUtilsConfig cfg, Which which) {
        cfg.general.normalise();
        if (which.isTracker()) return cfg.general.trackerPos;
        if (which.isShoppingList()) return cfg.general.shoppingListPos;
        if (which.isFuseOrder()) return cfg.general.fuseOrderPos;
        return which.isGraph()
                ? cfg.general.graphPos[which.table][which.graph]
                : cfg.general.tablePos[which.table];
    }

    private static final String[] METRIC_NAME = {"profit", "demand", "xp per coin"};

    public static String title(SquidUtilsConfig cfg, Which which) {
        if (which.isTracker()) return "Session tracker";
        if (which.isShoppingList()) return "Shopping list";
        if (which.isFuseOrder()) return "Fuse order";
        return which.isGraph()
                ? tableName(cfg, which.table) + " · " + METRIC_NAME[which.graph]
                : tableName(cfg, which.table);
    }

    /**
     * Visibility comes from the settings screen alone.
     *
     * <p>{@code WidgetPos.enabled} is deliberately not consulted: an earlier
     * build let you right-click a panel away inside the editor, and once the
     * editor stopped drawing hidden panels there was no way to bring one back.
     */
    public static boolean enabled(SquidUtilsConfig cfg, Which which) {
        if (which.isTracker()) return cfg.fusion.tracker.trackerShow;
        // Gated purely by the toggle, same as every other panel - not by
        // whether the list currently has anything in it. Hiding an empty
        // panel would also hide it from the overlay editor, and then there
        // would be no way to position it before you have added anything.
        if (which.isShoppingList()) return cfg.fusion.shoppingListShow;
        if (which.isFuseOrder()) return cfg.fusion.fuseOrderShow;
        if (!cfg.fusion.tableShown(which.table)) return false;   // graphs follow their table
        return !which.isGraph() || cfg.fusion.graphOn(which.table, which.graph);
    }

    /**
     * Whether this panel currently has anything worth drawing on the real
     * HUD - separate from {@link #enabled}, which reflects only the settings
     * toggle and stays that way deliberately, since the overlay editor uses
     * it too and an empty panel still needs to be positionable there before
     * anything has been added to it.
     *
     * <p>Only the shopping list and fuse order panels can be empty in a way
     * worth hiding for outside the editor; every other panel already draws
     * its own inline empty state ("collecting history...", and so on)
     * instead of disappearing.
     */
    public static boolean hasContent(Which which) {
        if (which.isShoppingList()) return !ShoppingList.isEmpty();
        if (which.isFuseOrder()) return ShoppingList.hasSteps();
        return true;
    }

    /** The table a graph belongs to, for the connector line. Only Recommended
     *  and XP per fuse have graphs - the four Profit Shards tables never do. */
    public static Which tableOf(Which graph) {
        return switch (graph.table) {
            case 5 -> Which.XP_TABLE;
            default -> Which.REC_TABLE;
        };
    }

    /**
     * @param mouseX,mouseY screen-space cursor position, or -1,-1 when there
     *        is no cursor to speak of (drawing the plain HUD with no screen
     *        open) - only the tracker panel's hover-revealed controls consult
     *        this, so every other caller can pass -1,-1 freely.
     */
    public static int[] draw(GuiGraphicsExtractor g, Font font, SquidUtilsConfig cfg,
                             FusionEngine engine, Which which, boolean preview,
                             int mouseX, int mouseY) {
        if (which.isTracker()) return tracker(g, font, cfg, mouseX, mouseY);
        if (which.isShoppingList()) return shoppingList(g, font, engine, pos(cfg, which));
        if (which.isFuseOrder()) return fuseOrder(g, font, engine, pos(cfg, which));
        return which.isGraph()
                ? graph(g, font, cfg, engine, which, preview)
                : table(g, font, cfg, engine, which, preview);
    }

    // ------------------------------------------------------------------
    /**
     * Session totals, laid out like the trackers players already know - and,
     * borrowing the idea from Feesh's own trackers, its pause/reset/view-mode
     * controls only reveal themselves as clickable bracketed text when the
     * panel is hovered, rather than sitting as permanent buttons on the
     * settings screen.
     */
    private static int[] tracker(GuiGraphicsExtractor g, Font font, SquidUtilsConfig cfg,
                                 int mouseX, int mouseY) {
        var t = dev.squidutils.SquidUtils.tracker();
        int lineH = font.lineHeight + 1;
        boolean totalView = t.viewingTotal();

        double coinsSpentV = totalView ? t.totalCoinsSpent() : t.coinsSpent();
        double coinsGainedV = totalView ? t.totalCoinsGained() : t.coinsGained();
        double profitV = totalView ? t.totalProfit() : t.profit();
        double xpV = totalView ? t.totalXpGained() : t.xpGained();
        long fusesV = totalView ? t.totalFuses() : t.fuses();
        long shardsFusedV = totalView ? t.totalShardsFused() : t.shardsFused();
        long boughtV = totalView ? t.totalShardsBought() : t.shardsBought();
        long soldV = totalView ? t.totalShardsSold() : t.shardsSold();
        long elapsedV = totalView ? t.totalElapsedSeconds() : t.elapsedSeconds();

        List<Cell> lines = new ArrayList<>();
        lines.add(new Cell("Fusion session tracker  [" + (totalView ? "Total" : "Session") + "]", Draw.TITLE));
        if (t.paused()) lines.add(new Cell("[Paused]", Draw.C_FILL));

        if (cfg.fusion.tracker.trackerCoins) {
            lines.add(new Cell("Spent: " + Draw.coins(coinsSpentV)
                    + " (" + Draw.coins(t.perHour(coinsSpentV, elapsedV)) + "/h)", Draw.C_COST));
            lines.add(new Cell("Earned: " + Draw.coins(coinsGainedV)
                    + " (" + Draw.coins(t.perHour(coinsGainedV, elapsedV)) + "/h)", Draw.C_PROFIT));
            lines.add(new Cell("Profit: " + (profitV >= 0 ? "+" : "") + Draw.coins(profitV)
                    + " (" + Draw.coins(t.perHour(profitV, elapsedV)) + "/h)",
                    profitV >= 0 ? Draw.C_PROFIT : 0xFFFF6666));
        }
        if (cfg.fusion.tracker.trackerXp) {
            // "~" marks a figure that includes at least one fusion whose real
            // XP line never arrived and was estimated from Hunting Wisdom
            // instead - see SessionTracker.creditEstimatedXp.
            boolean estimated = totalView ? t.totalXpEstimated() : t.xpEstimated();
            lines.add(new Cell("Hunting XP: " + (estimated ? "~" : "") + Draw.units(xpV)
                    + " (" + Draw.units(t.perHour(xpV, elapsedV)) + "/h)", Draw.C_XP));
        }
        if (cfg.fusion.tracker.trackerShards) {
            lines.add(new Cell("Fusions: " + fusesV
                    + " (" + Draw.units(t.perHour(fusesV, elapsedV)) + "/h)"
                    + "  ·  " + shardsFusedV + " shards out", Draw.C_FIT));
            lines.add(new Cell("Bought " + boughtV + " · sold " + soldV, Draw.C_VOLUME));
        }
        // Session, not yet armed, reads its own explanation instead of a flat
        // 0s - see SessionTracker's class doc for why it waits to start.
        lines.add(new Cell(!totalView && !t.started()
                ? "Elapsed: not started - buy or fuse to begin"
                : "Elapsed: " + formatElapsed(elapsedV), Draw.DIM));

        int width = 150;
        for (Cell c : lines) width = Math.max(width, font.width(c.text()));
        String toggleText = totalView ? "[Click to view Session]" : "[Click to view Total]";
        String pauseText = t.paused() ? "[Click to resume]" : "[Click to pause]";
        String resetText = "[Click to reset]";
        width = Math.max(width, font.width(toggleText));
        width = Math.max(width, font.width(pauseText));
        width = Math.max(width, font.width(resetText));

        int collapsedHeight = lineH * lines.size() + 8;
        int expandedHeight = collapsedHeight + lineH * 3;
        WidgetPos p = cfg.general.trackerPos;

        // Hit-tested against the expanded footprint always, whether or not it
        // is currently drawn: same top-left anchor, strictly taller, so this
        // alone decides hover with no frame-lagged "was hovering" state
        // needed - the collapsed box is already a subset of it.
        boolean hovered = mouseX >= p.x && mouseY >= p.y
                && mouseX <= p.x + Math.round((width + 8) * p.scale)
                && mouseY <= p.y + Math.round(expandedHeight * p.scale);

        int height = hovered ? expandedHeight : collapsedHeight;
        begin(g, p);
        Draw.panel(g, width + 8, height, 0xC07FD4FF);

        int y = 4;
        if (hovered) {
            y = trackerControl(g, font, p, y, toggleText, t::toggleViewMode);
            y = trackerControl(g, font, p, y, pauseText, t::togglePause);
            y = trackerControl(g, font, p, y, resetText, t::reset);
        }
        for (Cell c : lines) {
            g.text(font, c.text(), 4, y, c.colour());
            y += lineH;
        }
        end(g);
        return new int[]{width + 8, height};
    }

    /** One clickable control line on the tracker panel. Returns the y for
     *  the line after it. */
    private static int trackerControl(GuiGraphicsExtractor g, Font font, WidgetPos p, int y,
                                      String text, Runnable action) {
        g.text(font, text, 4, y, 0xFF7FD4FF);
        ACTION_HITS.add(new ActionHit(
                p.x + Math.round(4 * p.scale),
                p.y + Math.round((y - 1) * p.scale),
                Math.round(font.width(text) * p.scale),
                Math.round((font.lineHeight + 2) * p.scale),
                action));
        return y + font.lineHeight + 1;
    }

    /**
     * The accumulated shopping list, styled like SkyHanni's visitor shopping
     * list - a small always-there panel rather than a screen you navigate to,
     * so it stays visible (and moveable, through the same overlay editor
     * every other panel uses) even while the bazaar's own sign prompt is open.
     *
     * <p>Left-click a row to open its bazaar page and arm the sign fill.
     * Right-click removes it - see the click handling in {@code SquidUtils}.
     */
    /**
     * Real order-book sweep cost per line, at the actual queued quantity -
     * not a per-unit price scaled up, which understates cost the moment a
     * queued quantity is large enough to walk past the cheapest book levels.
     * A line the book cannot currently fill shows "?" rather than a wrong
     * number, and taints the panel total with a trailing "+" so it reads as
     * a floor, not an exact figure - the same "say so rather than pretend"
     * rule the rest of the mod follows for an unmeasured number.
     *
     * <p>Each line also gets the same "how far can I buy before the book
     * bites back" check the tables' own batch column runs, via {@link
     * Scorer#buyDepthLimit} - queuing more than that turns the line a
     * warning colour and says how much actually stays within tolerance, so a
     * huge queued quantity that would only fill by paying steadily worse
     * prices is visible before you go buy it, not after.
     */
    private static int[] shoppingList(GuiGraphicsExtractor g, Font font, FusionEngine engine, WidgetPos p) {
        var entries = ShoppingList.entries();
        FusionData data = engine.data();
        Scorer.Settings cfg = engine.currentSettings();
        var products = engine.products();
        var brain = engine.brain();
        int lineH = font.lineHeight + 1;

        List<String> lineTexts = new ArrayList<>(entries.size());
        List<Boolean> overBudget = new ArrayList<>(entries.size());
        List<Long> safeAmounts = new ArrayList<>(entries.size());
        double total = 0;
        boolean anyUnknown = false;
        for (var e : entries) {
            var s = data.shard(e.shardIndex());
            var product = products.get(s.tag());
            var ref = brain.reference(s.tag());
            double cost = product != null ? Scorer.totalBuyCost(product, e.units(), cfg, ref) : -1;

            String costText;
            if (cost >= 0) {
                total += cost;
                costText = Draw.coins(cost);
            } else {
                anyUnknown = true;
                costText = "?";
            }

            // Recomputed fresh every render from engine.products()/
            // currentSettings(), the same live state the tables' own batch
            // column reads - so this tracks the engine's own refresh cadence
            // (Trading > refresh interval, 20s minimum) rather than being
            // frozen at the moment the line was added to the list.
            long safe = product != null ? Scorer.buyDepthLimit(product, cfg, ref) : Long.MAX_VALUE;
            boolean over = e.units() > safe;
            overBudget.add(over);
            safeAmounts.add(safe);
            String line = s.name() + " x" + e.units() + "  (" + costText + ")";
            if (over) line += " - only " + safe + "x within tolerance";
            lineTexts.add(line);
        }
        String title = entries.isEmpty() ? "Shopping list"
                : "Shopping list  (" + Draw.coins(total) + (anyUnknown ? "+" : "") + ")";

        int width = font.width(title);
        for (int i = 0; i < lineTexts.size(); i++) {
            int extra = DELETE_W + (overBudget.get(i) ? font.width(BATCH_LABEL) : 0);
            width = Math.max(width, ICON + 6 + font.width(lineTexts.get(i)) + extra);
        }
        String empty = "empty - \"Add to shopping list\" on a route screen";
        if (entries.isEmpty()) width = Math.max(width, font.width(empty));

        int height = lineH * (1 + Math.max(1, entries.size())) + 8;
        begin(g, p);
        Draw.panel(g, width + 8, height, 0xC0FF9E5E);

        int y = 4;
        g.text(font, title, 4, y, Draw.TITLE);
        y += lineH + 2;

        if (entries.isEmpty()) {
            g.text(font, empty, 4, y, Draw.DIM);
        } else {
            for (int i = 0; i < entries.size(); i++) {
                var e = entries.get(i);
                var s = data.shard(e.shardIndex());
                drawIcon(g, font, 4, y - 1, ICON, s);
                int colour = overBudget.get(i) ? 0xFFFFB020 : 0xFF7FD4FF;
                g.text(font, lineTexts.get(i), 4 + ICON + 6, y, colour);
                HITS.add(new Hit(
                        p.x + Math.round(4 * p.scale),
                        p.y + Math.round((y - 1) * p.scale),
                        Math.round((ICON + 6 + font.width(lineTexts.get(i))) * p.scale),
                        Math.round((font.lineHeight + 2) * p.scale),
                        s.name(), e.units(), e.shardIndex()));
                int shardIndex = e.shardIndex();
                int x = 4 + ICON + 6 + font.width(lineTexts.get(i));
                if (overBudget.get(i)) {
                    long safe = safeAmounts.get(i);
                    x = batchButton(g, font, x, y, p, shardIndex, safe);
                }
                deleteButton(g, font, x, y, p, () -> ShoppingList.remove(shardIndex));
                y += lineH;
            }
        }
        end(g);
        return new int[]{width + 8, height};
    }

    private static final String BATCH_LABEL = " [batch]";

    /** Clamps an over-budget shopping list line down to the safe quantity
     *  {@link Scorer#buyDepthLimit} allows right now - only drawn once a
     *  line is already flagged over budget, since there is nothing to clamp
     *  otherwise. Reads the same live {@code safe} value the line's warning
     *  text already shows, so the amount it sets tracks the engine's own
     *  refresh cadence rather than a value frozen at add-time. */
    private static int batchButton(GuiGraphicsExtractor g, Font font, int x, int y, WidgetPos p,
                                    int shardIndex, long safe) {
        g.text(font, BATCH_LABEL, x, y, 0xFFFFB020);
        int clamped = (int) Math.min(safe, Integer.MAX_VALUE);
        ACTION_HITS.add(new ActionHit(
                p.x + Math.round((x + 1) * p.scale),
                p.y + Math.round((y - 1) * p.scale),
                Math.round((font.width(BATCH_LABEL) - 1) * p.scale),
                Math.round((font.lineHeight + 2) * p.scale),
                () -> ShoppingList.setUnits(shardIndex, clamped)));
        return x + font.width(BATCH_LABEL);
    }

    /**
     * Every fusion step queued across the whole shopping list, dependency-
     * ordered, styled like a route screen's own "Fuse, in order" section but
     * drawn under the current screen rather than replacing it - this is the
     * one place you actually want it next to the Fusion Box itself, so you
     * can read the next step while working through the menu, not navigate
     * away from it to see the list.
     */
    private static int[] fuseOrder(GuiGraphicsExtractor g, Font font, FusionEngine engine, WidgetPos p) {
        var steps = ShoppingList.steps();
        FusionData data = engine.data();
        Scorer.Settings cfg = engine.currentSettings();
        var products = engine.products();
        var brain = engine.brain();
        int lineH = font.lineHeight + 1;

        String empty = "empty - fusion steps appear once a route with steps is added";

        // Real profit per step - an order-book sweep for crafts x that step's
        // own input/output quantities, not a per-unit price scaled up. Same
        // "?" and trailing "+" convention the shopping list panel uses for a
        // line the book cannot currently fill.
        List<Double> profits = new ArrayList<>(steps.size());
        double totalProfit = 0;
        boolean anyUnknown = false;
        for (var step : steps) {
            Double profit = stepProfit(data, products, cfg, brain, step);
            profits.add(profit);
            if (profit != null) totalProfit += profit; else anyUnknown = true;
        }
        String title = steps.isEmpty() ? "Fuse order"
                : "Fuse order  (" + (totalProfit >= 0 ? "+" : "") + Draw.coins(totalProfit)
                        + (anyUnknown ? "+" : "") + ")";

        int width = font.width(title);
        for (int i = 0; i < steps.size(); i++) {
            width = Math.max(width, fuseOrderLineWidth(font, data, steps.get(i), profits.get(i)) + DELETE_W);
        }
        if (steps.isEmpty()) width = Math.max(width, font.width(empty));

        int height = lineH * (1 + Math.max(1, steps.size())) + 8;
        begin(g, p);
        Draw.panel(g, width + 8, height, 0xC0B86BFF);

        int y = 4;
        g.text(font, title, 4, y, Draw.TITLE);
        y += lineH + 2;

        if (steps.isEmpty()) {
            g.text(font, empty, 4, y, Draw.DIM);
        } else {
            for (int i = 0; i < steps.size(); i++) {
                drawFuseOrderRow(g, font, data, steps.get(i), profits.get(i), 4, y, p);
                y += lineH;
            }
        }
        end(g);
        return new int[]{width + 8, height};
    }

    /**
     * Real profit for {@code crafts} of one queued step - an order-book
     * sweep at the actual bulk quantity on every leg, the same primitive the
     * shopping list panel and each table's new "batch" column both use.
     * Null, not zero, when the book cannot currently fill it, so the caller
     * can tell "unprofitable" apart from "unknown".
     */
    private static Double stepProfit(FusionData data, Map<String, BazaarClient.Product> products,
                                     Scorer.Settings cfg, Brain brain, ShoppingList.StepEntry step) {
        int r = step.recipeIndex();
        var sa = data.shard(data.inputA(r));
        var sb = data.shard(data.inputB(r));
        var sr = data.shard(data.result(r));
        boolean same = data.inputA(r) == data.inputB(r);
        long crafts = step.crafts();

        var pa = products.get(sa.tag());
        var pr = products.get(sr.tag());
        if (pa == null || pr == null) return null;

        double cost;
        if (same) {
            long units = crafts * (sa.fuseAmount() + sb.fuseAmount());
            double c = Scorer.totalBuyCost(pa, units, cfg, brain.reference(sa.tag()));
            if (c < 0) return null;
            cost = c;
        } else {
            var pb = products.get(sb.tag());
            if (pb == null) return null;
            double ca = Scorer.totalBuyCost(pa, crafts * sa.fuseAmount(), cfg, brain.reference(sa.tag()));
            double cb = Scorer.totalBuyCost(pb, crafts * sb.fuseAmount(), cfg, brain.reference(sb.tag()));
            if (ca < 0 || cb < 0) return null;
            cost = ca + cb;
        }

        double revenue = Scorer.totalSellRevenue(pr, crafts * data.qty(r), cfg, brain.reference(sr.tag()));
        if (revenue < 0) return null;
        return revenue - cost;
    }

    private static String profitText(Double profit) {
        return profit == null ? "?" : (profit >= 0 ? "+" : "") + Draw.coins(profit);
    }

    private static int fuseOrderLineWidth(Font font, FusionData data, ShoppingList.StepEntry step, Double profit) {
        int r = step.recipeIndex();
        var sa = data.shard(data.inputA(r));
        var sb = data.shard(data.inputB(r));
        var sr = data.shard(data.result(r));
        boolean same = data.inputA(r) == data.inputB(r);
        String prefix = step.crafts() > 1 ? step.crafts() + "×  " : "";
        String line = same
                ? (sa.fuseAmount() + sb.fuseAmount()) + "x " + sa.name()
                : sa.fuseAmount() + "x " + sa.name() + " + " + sb.fuseAmount() + "x " + sb.name();
        return font.width(prefix + line + " → " + data.qty(r) + "x " + sr.name()
                + "  (" + profitText(profit) + ")");
    }

    /** One fuse-order row: crafts-count prefix, then a fusion label with each
     *  shard name clickable for the bazaar - same building blocks {@link
     *  #drawLabel} uses for a plain table row - the real batch profit, and a
     *  manual delete button. */
    private static void drawFuseOrderRow(GuiGraphicsExtractor g, Font font, FusionData data,
                                         ShoppingList.StepEntry step, Double profit, int x, int y, WidgetPos p) {
        int r = step.recipeIndex();
        var sa = data.shard(data.inputA(r));
        var sb = data.shard(data.inputB(r));
        var sr = data.shard(data.result(r));
        boolean same = data.inputA(r) == data.inputB(r);

        int cx = x;
        if (step.crafts() > 1) {
            cx = plain(g, font, cx, y, step.crafts() + "×  ");
        }
        if (same) {
            cx = plain(g, font, cx, y, (sa.fuseAmount() + sb.fuseAmount()) + "x ");
            cx = name(g, font, cx, y, sa.name(), p);
        } else {
            cx = plain(g, font, cx, y, sa.fuseAmount() + "x ");
            cx = name(g, font, cx, y, sa.name(), p);
            cx = plain(g, font, cx, y, " + " + sb.fuseAmount() + "x ");
            cx = name(g, font, cx, y, sb.name(), p);
        }
        cx = plain(g, font, cx, y, " → " + data.qty(r) + "x ");
        cx = name(g, font, cx, y, sr.name(), p);

        String profitStr = "  (" + profitText(profit) + ")";
        int profitColour = profit == null ? Draw.DIM : profit >= 0 ? Draw.C_PROFIT : 0xFFFF6666;
        g.text(font, profitStr, cx, y, profitColour);
        cx += font.width(profitStr);

        deleteButton(g, font, cx, y, p, () -> ShoppingList.removeStep(r));
    }

    private static final int DELETE_W = 12;

    /** A small red "-" that removes the row it is drawn on - the shopping
     *  list and fuse order panels' own manual-delete affordance. */
    private static void deleteButton(GuiGraphicsExtractor g, Font font, int x, int y, WidgetPos p, Runnable action) {
        String glyph = " -";
        g.text(font, glyph, x, y, 0xFFFF6666);
        ACTION_HITS.add(new ActionHit(
                p.x + Math.round((x + 2) * p.scale),
                p.y + Math.round((y - 1) * p.scale),
                Math.round((DELETE_W - 2) * p.scale),
                Math.round((font.lineHeight + 2) * p.scale),
                action));
    }

    private static String formatElapsed(long secs) {
        if (secs < 60) return secs + "s";
        if (secs < 3600) return (secs / 60) + "m " + (secs % 60) + "s";
        return (secs / 3600) + "h " + ((secs % 3600) / 60) + "m";
    }

    private static void begin(GuiGraphicsExtractor g, WidgetPos p) {
        var pose = g.pose();
        pose.pushMatrix();
        pose.translate(p.x, p.y);
        pose.scale(p.scale, p.scale);
    }

    private static void end(GuiGraphicsExtractor g) {
        g.pose().popMatrix();
    }

    /** The full ranked list a table draws from, before trimming to its own
     *  row count - {@link #buildRows} needs the untrimmed list so a
     *  multi-step row filtered back out for no longer clearing the profit
     *  minimum can be backfilled from further down, rather than just
     *  shrinking the table.
     *
     *  <p>Takes an already-captured {@link FusionEngine.Snapshot} rather
     *  than the engine itself, so a caller building several things from the
     *  same table (this list and, say, {@link #rowData}'s route costs) reads
     *  them off one consistent refresh cycle instead of each call
     *  independently picking whichever cycle happens to be current at that
     *  exact instant - see the class doc on {@link FusionEngine.Snapshot}. */
    private static List<Scorer.Opportunity> fullSourceFor(FusionEngine.Snapshot snap, int t) {
        return switch (t) {
            case 1, 2, 3, 4 -> snap.profitVariants().get(t - 1);
            case 5 -> snap.byXp();
            default -> recommendedOps(snap);
        };
    }

    /** Shards shown by one table, trimmed to its own row count - for the
     *  graphs, which plot exactly what the table shows. */
    private static List<Scorer.Opportunity> rowsOf(FusionEngine engine, SquidUtilsConfig cfg, int t) {
        List<Scorer.Opportunity> src = fullSourceFor(engine.snapshot(), t);
        int n = Math.min(cfg.fusion.rows(t), src.size());
        return src.subList(0, Math.max(0, n));
    }

    private static List<Scorer.Opportunity> recommendedOps(FusionEngine.Snapshot snap) {
        List<Scorer.Opportunity> out = new ArrayList<>();
        for (Recommender.Scored s : snap.recommended()) out.add(s.opportunity());
        return out;
    }

    // ------------------------------------------------------------------
    private record Cell(String text, int colour) {}

    /** Which column, keyed for sorting - not display order, which is table-specific. */
    private enum ColKey { COST, PROFIT, ROI, FILL_SEC, VOLUME, FIT, XP, XP_PER_K, STABILITY, FILL, BOTTLENECK, DEPTH_LIMIT, BATCH_PROFIT }

    private record Column(ColKey key, String legend, int colour) {}

    /**
     * A row's numbers, resolved once per frame.
     *
     * <p>{@code cost}/{@code profit}/{@code roi} come straight from the
     * opportunity's own recipe normally. In multi-step mode they instead come
     * from {@link RouteSolver}'s cheapest full route to the same output, which
     * may use a different recipe entirely - that route is what {@code steps}
     * and {@code rootRecipe} describe.
     */
    private record RowData(Scorer.Opportunity op, double cost, double profit, double roi,
                           double fit, double xpPerK, double stability,
                           boolean multiStep, int rootRecipe, int steps) {}

    private record Row(RowData data, List<Cell> cells) {}

    /** Whether a table's own buy/sell mode makes each leg instant (crossing
     *  the spread) or a resting order that genuinely waits to fill. */
    private record Legs(boolean buyInstant, boolean sellInstant) {}

    /** Recommended and XP per fuse both trade under the global Settings -
     *  Trading mode; each of the four Profit Shards tables has its own. */
    private static Legs legsFor(SquidUtilsConfig cfg, int t) {
        int buyMode, sellMode;
        switch (t) {
            case 1 -> { buyMode = cfg.fusion.tables.profit1.buyMode; sellMode = cfg.fusion.tables.profit1.sellMode; }
            case 2 -> { buyMode = cfg.fusion.tables.profit2.buyMode; sellMode = cfg.fusion.tables.profit2.sellMode; }
            case 3 -> { buyMode = cfg.fusion.tables.profit3.buyMode; sellMode = cfg.fusion.tables.profit3.sellMode; }
            case 4 -> { buyMode = cfg.fusion.tables.profit4.buyMode; sellMode = cfg.fusion.tables.profit4.sellMode; }
            default -> {
                buyMode = cfg.fusion.settings.trading.buyMode;
                sellMode = cfg.fusion.settings.trading.sellMode;
            }
        }
        return new Legs(buyMode == 0, sellMode == 1);   // 0 Instabuy, 1 Instasell
    }

    /**
     * The "how long to fill" column, mode-aware rather than a flat "fill"
     * label everywhere: names the one leg that actually waits when only one
     * of the two does - "sell" under Instabuy -> Sell offer, since buying is
     * instant there and selling is the only real wait; "buy" under Buy
     * order -> Instasell, the mirror case - and swaps to total batch profit
     * entirely once neither leg waits at all (Instabuy -> Instasell), where
     * "fill" would just read "instant" on every single row and say nothing
     * a player could act on.
     */
    private static Column fillColumn(Legs legs, boolean recommendedFlavor) {
        ColKey timeKey = recommendedFlavor ? ColKey.FILL_SEC : ColKey.FILL;
        if (legs.buyInstant() && legs.sellInstant()) {
            return new Column(ColKey.BATCH_PROFIT, "batch profit", Draw.C_BATCH);
        }
        if (legs.buyInstant()) return new Column(timeKey, "sell", Draw.C_FILL);
        if (legs.sellInstant()) return new Column(timeKey, "buy", Draw.C_FILL);
        return new Column(timeKey, "fill", Draw.C_FILL);
    }

    private static List<Column> columnsFor(int t, SquidUtilsConfig cfg) {
        List<Column> cols = new ArrayList<>();
        Legs legs = legsFor(cfg, t);

        if (t == 5) {
            cols.add(new Column(ColKey.COST, "cost", Draw.C_COST));
            cols.add(new Column(ColKey.XP, "xp", Draw.C_XP));
            cols.add(new Column(ColKey.XP_PER_K, "xp/1k coins", Draw.C_XP));
            cols.add(new Column(ColKey.PROFIT, "profit", Draw.C_PROFIT));
            cols.add(new Column(ColKey.VOLUME, "bought/h", Draw.C_VOLUME));
        } else {
            boolean rec = t == 0;
            cols.add(new Column(ColKey.COST, "cost", Draw.C_COST));
            cols.add(new Column(ColKey.PROFIT, "profit", Draw.C_PROFIT));
            cols.add(new Column(ColKey.ROI, "roi", Draw.C_ROI));
            if (rec) cols.add(fillColumn(legs, true));
            cols.add(new Column(ColKey.VOLUME, "bought/h", Draw.C_VOLUME));
            if (rec) cols.add(new Column(ColKey.FIT, "fit", Draw.C_FIT));
        }
        // Shared trailing columns, same as every table's settings.
        if (cfg.fusion.tables.showStability) cols.add(new Column(ColKey.STABILITY, "stable", Draw.C_STABLE));
        boolean ownFill = t == 0;   // the recommended table already has fill, in seconds
        if (cfg.fusion.tables.showFillTimes && !ownFill) {
            cols.add(fillColumn(legs, false));
        }
        if (cfg.fusion.tables.showBottleneck) cols.add(new Column(ColKey.BOTTLENECK, "bottleneck", Draw.DIM));
        if (cfg.fusion.tables.showDepthLimit) cols.add(new Column(ColKey.DEPTH_LIMIT, "batch", Draw.C_BATCH));
        return cols;
    }

    /** Bottleneck names a shard, not a number - nothing sensible to sort by. */
    private static boolean sortable(ColKey key) {
        return key != ColKey.BOTTLENECK;
    }

    private static double valueOf(ColKey key, RowData d) {
        Scorer.Opportunity o = d.op();
        return switch (key) {
            case COST -> d.cost();
            case PROFIT -> d.profit();
            case ROI -> d.roi();
            case FILL_SEC -> Recommender.fillSeconds(o);
            case VOLUME -> o.boughtPerHour();
            case FIT -> d.fit();
            case XP -> o.xpPerFuse();
            case XP_PER_K -> d.xpPerK();
            case STABILITY -> d.stability();
            case FILL -> Recommender.fillSeconds(o);
            case BOTTLENECK -> 0;
            case DEPTH_LIMIT -> o.depthLimitFuses();
            case BATCH_PROFIT -> o.depthLimitFuses() * d.profit();
        };
    }

    private static String textOf(ColKey key, RowData d) {
        Scorer.Opportunity o = d.op();
        return switch (key) {
            case COST -> Draw.coins(d.cost());
            case PROFIT -> (d.profit() >= 0 ? "+" : "") + Draw.coins(d.profit());
            case ROI -> Math.round(d.roi() * 100) + "%";
            case FILL_SEC -> {
                double secs = Recommender.fillSeconds(o);
                yield secs < 1 ? "instant" : Math.round(secs) + "s";
            }
            case VOLUME -> Draw.units(o.boughtPerHour()) + "/h";
            case FIT -> Math.round(d.fit() * 100) + "%";
            case XP -> Math.round(o.xpPerFuse()) + " xp";
            case XP_PER_K -> String.format("%.1f/1k", d.xpPerK());
            case STABILITY -> {
                double s = d.stability();
                yield s < 0 ? "—" : Math.round(s * 100) + "%";
            }
            case FILL -> {
                double secs = Recommender.fillSeconds(o);
                yield secs < 1 ? "instant" : Math.round(secs) + "s";
            }
            case BOTTLENECK -> o.limiter() + "  (" + Draw.units(o.limiterVolume()) + "/h)";
            case DEPTH_LIMIT -> {
                long n = o.depthLimitFuses();
                yield n >= 1_000_000 ? "1M+" : n + "x";
            }
            case BATCH_PROFIT -> {
                double total = o.depthLimitFuses() * d.profit();
                yield (total >= 0 ? "+" : "") + Draw.coins(total);
            }
        };
    }

    /** Which Settings a table's own numbers were scored under - Recommended
     *  and XP per fuse both use the global settings; only the four Profit
     *  Shards tables have their own, via {@link FusionEngine#variantSettings}. */
    private static Scorer.Settings settingsFor(FusionEngine engine, int t) {
        return switch (t) {
            case 1, 2, 3, 4 -> engine.variantSettings(t - 1);
            default -> engine.currentSettings();
        };
    }

    /**
     * A multi-step route's real cost under a specific table's own settings -
     * a live order-book sweep per buy, the same {@link Scorer#totalBuyCost}
     * the shopping list and fuse order panels use, rather than {@link
     * RouteSolver#routeCost}, which multiplies a per-unit price computed
     * once under the global settings by each buy's quantity. For a Profit
     * Shards variant whose own buy/sell mode differs from the global one,
     * that mismatch showed a cost figure that did not correspond to any one
     * coherent set of trading assumptions - confirmed from a real report of
     * negative profit on a "Buy order -> Instasell" table while the rest of
     * the mod defaulted to Instabuy -> Sell offer.
     *
     * @return the real total, or -1 if this table's own settings cannot fill
     *         every leg of the route right now.
     */
    private static double liveRouteCost(FusionEngine engine, FusionEngine.Snapshot snap,
                                        Scorer.Settings settings, RouteSolver.Route route) {
        var products = engine.products();
        var brain = snap.brain();
        var data = engine.data();
        double total = 0;
        for (RouteSolver.Buy b : route.buys()) {
            var shard = data.shard(b.shardIndex());
            var product = products.get(shard.tag());
            double cost = product == null ? -1
                    : Scorer.totalBuyCost(product, b.units(), settings, brain.reference(shard.tag()));
            if (cost < 0) return -1;
            total += cost;
        }
        return total;
    }

    /**
     * Resolve one row's numbers, swapping in {@link RouteSolver}'s route when
     * multi-step mode is on and a route actually exists.
     *
     * <p>A route can be missing even in multi-step mode: if buying the output
     * straight off the bazaar beats every fusion route to it, {@code via} is
     * {@link RouteSolver#BUY} and the row falls back to its normal one-hop
     * numbers rather than showing a nonsensical "0 steps". The route's own
     * settings can also simply fail to fill right now ({@link
     * #liveRouteCost} returning -1) - same fallback, for the same reason.
     *
     * <p>{@code snap} is one table-render's single captured {@link
     * FusionEngine.Snapshot} (see {@link #buildRows}), not a fresh {@code
     * engine.routeCosts()} call per row - a table can have dozens of rows,
     * each doing real order-book math, and a background refresh completing
     * midway through that loop used to mean the rows before it and the rows
     * after it were each individually correct but computed under two
     * different refresh cycles' costs, which is exactly the shape of a row
     * disagreeing with its neighbours for no visible reason.
     */
    private static RowData rowData(SquidUtilsConfig cfg, FusionEngine engine, FusionEngine.Snapshot snap, int t,
                                   Scorer.Opportunity o, double fit) {
        double cost = o.cost(), profit = o.profit(), roi = o.roi();
        int rootRecipe = -1, steps = 0;

        if (cfg.fusion.multiStep(t)) {
            var costs = snap.routeCosts();
            var data = engine.data();
            int shardIndex = costs == null ? -1 : data.indexOfTag(o.resultTag());
            int via = shardIndex >= 0 ? costs.via()[shardIndex] : RouteSolver.BUY;
            if (via != RouteSolver.BUY) {
                var route = RouteSolver.explain(data, costs, via);
                double routeCost = liveRouteCost(engine, snap, settingsFor(engine, t), route);
                if (routeCost >= 0) {
                    double revenue = o.profit() + o.cost();   // recover sell-side revenue
                    cost = routeCost;
                    profit = revenue - routeCost;
                    roi = cost > 0 ? profit / cost : 0;
                    rootRecipe = via;
                    steps = route.steps().size();
                }
            }
        }

        double xpPerK = cost > 0 ? o.xpPerFuse() / cost * 1000.0 : 0;
        double stability = engine.stabilityFor(o.resultTag());
        return new RowData(o, cost, profit, roi, fit, xpPerK, stability,
                rootRecipe >= 0, rootRecipe, steps);
    }

    /**
     * Builds a table's rows straight from its full ranked list, not a
     * pre-trimmed slice, so a row whose final profit (after any multi-step
     * recompute above) no longer clears the configured minimum is simply
     * skipped and backfilled from further down - the table still ends up
     * with the row count you asked for instead of quietly showing fewer,
     * and never shows a fusion its own numbers say is not worth doing.
     *
     * <p>Captures one {@link FusionEngine.Snapshot} up front and threads it
     * through every row - see {@link #rowData}'s doc for why that matters:
     * without it, each row's own {@code routeCosts} lookup could land on
     * whichever refresh cycle happened to be current at that row's specific
     * moment in the loop, not necessarily the same one the row list itself
     * (and every other row) was built from.
     */
    private static List<Row> buildRows(SquidUtilsConfig cfg, FusionEngine engine, int t, List<Column> columns) {
        FusionEngine.Snapshot snap = engine.snapshot();
        List<Scorer.Opportunity> src = fullSourceFor(snap, t);
        int wanted = cfg.fusion.rows(t);
        double minProfit = FusionCategory.parseNumber(
                cfg.fusion.settings.filters.minProfitPerFuse, 1000);

        boolean rec = t == 0;
        List<Recommender.Scored> scored = rec ? snap.recommended() : List.of();

        List<Row> rows = new ArrayList<>(Math.min(wanted, src.size()));
        for (int i = 0; i < src.size() && rows.size() < wanted; i++) {
            Scorer.Opportunity o = src.get(i);
            double fit = (rec && i < scored.size()) ? scored.get(i).score() : 0;
            RowData d = rowData(cfg, engine, snap, t, o, fit);
            if (d.profit() < minProfit) continue;

            List<Cell> cells = new ArrayList<>(columns.size());
            for (Column c : columns) cells.add(new Cell(textOf(c.key(), d), c.colour()));
            rows.add(new Row(d, cells));
        }
        return rows;
    }

    /** Apply the table's chosen sort, if any - default is the engine's own order. */
    private static void applySort(int t, List<Row> rows, List<Column> columns) {
        ColKey key = sortColumn[t];
        if (key == null) return;
        if (columns.stream().noneMatch(c -> c.key() == key)) return;   // column not shown right now

        Comparator<Row> cmp = Comparator.comparingDouble(r -> valueOf(key, r.data()));
        if (sortDescending[t]) cmp = cmp.reversed();
        rows.sort(cmp);
    }

    private static int[] columnOffsets(Font font, List<Row> rows, List<Cell> legend) {
        int cols = legend.size();
        for (Row r : rows) cols = Math.max(cols, r.cells().size());
        int[] width = new int[cols];
        for (Row r : rows) {
            for (int i = 0; i < r.cells().size(); i++) {
                width[i] = Math.max(width[i], font.width(r.cells().get(i).text()));
            }
        }
        for (int i = 0; i < legend.size(); i++) {
            width[i] = Math.max(width[i], font.width(legend.get(i).text()));
        }
        int[] x = new int[cols];
        int run = 0;
        for (int i = 0; i < cols; i++) {
            x[i] = run;
            run += width[i] + COL_GAP;
        }
        return x;
    }

    private static int rowWidth(Font font, List<Cell> cells, int[] colX) {
        if (cells.isEmpty()) return 0;
        int last = cells.size() - 1;
        return colX[last] + font.width(cells.get(last).text());
    }

    /** The text a row's label draws as - real content for width measurement,
     *  since multi-step and normal rows are wildly different lengths. */
    private static String displayLabel(FusionEngine engine, RowData d) {
        if (!d.multiStep()) return d.op().label();
        var data = engine.data();
        var sr = data.shard(data.result(d.rootRecipe()));
        return sr.name() + " (" + d.steps() + (d.steps() == 1 ? " step)" : " steps)");
    }

    /**
     * Draw a fusion label with each shard name registered as a click target.
     *
     * <p>All three names are clickable, not just the result: when a recipe is
     * unprofitable it is usually an input that has moved, and that is the one
     * you want to look at.
     */
    private static void drawLabel(GuiGraphicsExtractor g, Font font, FusionEngine engine,
                                  Scorer.Opportunity o, int x, int y, WidgetPos p) {
        var data = engine.data();
        int r = o.recipeIndex();
        var sa = data.shard(data.inputA(r));
        var sb = data.shard(data.inputB(r));
        var sr = data.shard(data.result(r));
        boolean same = data.inputA(r) == data.inputB(r);

        int cx = x;
        if (same) {
            cx = plain(g, font, cx, y, (sa.fuseAmount() + sb.fuseAmount()) + "x ");
            cx = name(g, font, cx, y, sa.name(), p);
        } else {
            cx = plain(g, font, cx, y, sa.fuseAmount() + "x ");
            cx = name(g, font, cx, y, sa.name(), p);
            cx = plain(g, font, cx, y, " + " + sb.fuseAmount() + "x ");
            cx = name(g, font, cx, y, sb.name(), p);
        }
        cx = plain(g, font, cx, y, " → " + data.qty(r) + "x ");
        name(g, font, cx, y, sr.name(), p);
    }

    /** Multi-step mode's row label - one clickable run that opens the route. */
    private static void drawMultiStepLabel(GuiGraphicsExtractor g, Font font, FusionEngine engine,
                                           RowData d, int x, int y, WidgetPos p) {
        String label = displayLabel(engine, d);
        g.text(font, label, x, y, 0xFFFFFFFF);
        int w = font.width(label);
        ROW_HITS.add(new RowHit(
                p.x + Math.round(x * p.scale),
                p.y + Math.round(y * p.scale),
                Math.round(w * p.scale),
                Math.round(font.lineHeight * p.scale),
                d.rootRecipe()));
    }

    private static int plain(GuiGraphicsExtractor g, Font font, int x, int y, String s) {
        g.text(font, s, x, y, 0xFFFFFFFF);
        return x + font.width(s);
    }

    /** Draw a shard name and record its screen-space box as a click target. */
    private static int name(GuiGraphicsExtractor g, Font font, int x, int y,
                            String shard, WidgetPos p) {
        g.text(font, shard, x, y, 0xFFFFFFFF);
        int w = font.width(shard);
        // The panel is drawn inside a translate+scale, so convert to screen
        // space for hit testing against raw mouse coordinates.
        HITS.add(new Hit(
                p.x + Math.round(x * p.scale),
                p.y + Math.round(y * p.scale),
                Math.round(w * p.scale),
                Math.round(font.lineHeight * p.scale),
                shard, -1, -1));
        return x + w;
    }

    private static void drawCells(GuiGraphicsExtractor g, Font font, int x, int y,
                                  List<Cell> cells, int[] colX) {
        for (int i = 0; i < cells.size(); i++) {
            g.text(font, cells.get(i).text(), x + colX[i], y, cells.get(i).colour());
        }
    }

    /** Legend row, doubling as clickable sort headers - same idea as Task
     *  Manager: click a column to sort by it, click again to flip direction. */
    private static void drawLegendHeaders(GuiGraphicsExtractor g, Font font, int x, int y,
                                          List<Column> columns, List<Cell> legend, int[] colX,
                                          int table, WidgetPos p) {
        for (int i = 0; i < legend.size(); i++) {
            Column col = columns.get(i);
            Cell cell = legend.get(i);
            String text = cell.text();
            if (sortColumn[table] == col.key()) {
                text += sortDescending[table] ? " ▼" : " ▲";
            }
            g.text(font, text, x + colX[i], y, cell.colour());
            if (sortable(col.key())) {
                int w = font.width(text);
                HEADER_HITS.add(new HeaderHit(
                        p.x + Math.round((x + colX[i]) * p.scale),
                        p.y + Math.round(y * p.scale),
                        Math.round(w * p.scale),
                        Math.round(font.lineHeight * p.scale),
                        table, col.key()));
            }
        }
    }

    private static int[] table(GuiGraphicsExtractor g, Font font, SquidUtilsConfig cfg,
                               FusionEngine engine, Which which, boolean preview) {
        int t = which.table;
        WidgetPos p = pos(cfg, which);

        List<Column> columns = columnsFor(t, cfg);
        List<Row> rows = buildRows(cfg, engine, t, columns);
        applySort(t, rows, columns);

        List<Cell> legend = new ArrayList<>(columns.size());
        for (Column c : columns) legend.add(new Cell(c.legend(), c.colour()));

        boolean compact = cfg.fusion.compact;
        boolean showLegend = cfg.fusion.showLegend && !legend.isEmpty();
        int lineH = font.lineHeight + 1;
        int[] colX = columnOffsets(font, rows, legend);

        String heading = tableName(cfg, t);
        String sub = subtitle(engine);
        int width = Math.max(font.width(heading) + 10 + font.width(sub), 150);
        for (Row r : rows) {
            width = Math.max(width, 16 + ICON + font.width(displayLabel(engine, r.data())));
            if (!compact) width = Math.max(width, STAT_INDENT + rowWidth(font, r.cells(), colX));
        }
        if (showLegend) width = Math.max(width, STAT_INDENT + rowWidth(font, legend, colX));

        int perRow = compact ? 1 : 2;
        int height = lineH * (1 + Math.max(1, rows.size()) * perRow) + 8
                + (showLegend ? lineH + 3 : 0);

        begin(g, p);
        // Border tinted by group: a table and its graphs share a colour, which
        // says what belongs with what without lines crossing the screen.
        Draw.panel(g, width + 8, height, Draw.groupColour(t));

        int y = 4;
        g.text(font, heading, 4, y, Draw.TITLE);
        g.text(font, sub, width + 4 - font.width(sub), y, Draw.DIM);
        y += lineH + 2;

        if (rows.isEmpty()) {
            g.text(font, preview ? "(nothing to show right now)"
                            : "waiting for price data...", 4, y, Draw.DIM);
            end(g);
            return new int[]{width + 8, height};
        }

        int rank = 1;
        for (Row r : rows) {
            String pre = rank + ".";
            g.text(font, pre, 4, y, Draw.SERIES[(rank - 1) % Draw.SERIES.length]);
            int cx = 4 + font.width(pre) + 3;
            drawIcon(g, font, cx, y - 1, ICON, r.data().op());
            if (r.data().multiStep()) {
                drawMultiStepLabel(g, font, engine, r.data(), cx + ICON + 4, y, p);
            } else {
                drawLabel(g, font, engine, r.data().op(), cx + ICON + 4, y, p);
            }
            y += lineH;
            if (!compact) {
                drawCells(g, font, STAT_INDENT, y, r.cells(), colX);
                y += lineH;
            }
            rank++;
        }

        if (showLegend) {
            g.fill(4, y + 1, width + 4, y + 2, Draw.GRID);
            drawLegendHeaders(g, font, STAT_INDENT, y + 3, columns, legend, colX, t, p);
        }

        end(g);
        return new int[]{width + 8, height};
    }

    // ------------------------------------------------------------------
    private static void drawIcon(GuiGraphicsExtractor g, Font font,
                                 int x, int y, int size, Scorer.Opportunity o) {
        drawIcon(g, font, x, y, size, o.resultTag(), o.rarity(), o.resultName());
    }

    private static void drawIcon(GuiGraphicsExtractor g, Font font,
                                 int x, int y, int size, FusionData.Shard s) {
        drawIcon(g, font, x, y, size, s.tag(), s.rarity(), s.name());
    }

    private static void drawIcon(GuiGraphicsExtractor g, Font font, int x, int y, int size,
                                 String tag, String rarity, String name) {
        int idx = ShardIcons.indexOf(tag);
        if (idx >= 0) {
            g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED,
                    ShardIcons.atlas(), x, y,
                    ShardIcons.cellX(idx), ShardIcons.cellY(idx),
                    size, size,
                    ShardIcons.cell(), ShardIcons.cell(),
                    ShardIcons.atlasWidth(), ShardIcons.atlasHeight());
            return;
        }
        int fill = Draw.rarity(rarity);
        g.fill(x, y, x + size, y + size, fill);
        g.outline(x, y, size, size, 0x80000000);   // (x, y, width, height)
        if (name != null && !name.isEmpty()) {
            String ch = name.substring(0, 1).toUpperCase(java.util.Locale.ROOT);
            boolean lightBg = fill == 0xFFFFFFFF || fill == 0xFFFFAA00 || fill == 0xFF55FF55;
            g.text(font, ch, x + (size - font.width(ch)) / 2, y,
                    lightBg ? 0xFF101010 : 0xFFFFFFFF);
        }
    }

    private static String subtitle(FusionEngine engine) {
        if (engine.lastRefresh() == 0) return "loading";
        long age = (System.currentTimeMillis() - engine.lastRefresh()) / 1000;
        return engine.status() + " · " + age + "s";
    }

    // ------------------------------------------------------------------
    private static int[] graph(GuiGraphicsExtractor g, Font font, SquidUtilsConfig cfg,
                               FusionEngine engine, Which which, boolean preview) {
        int t = which.table;
        WidgetPos p = pos(cfg, which);
        int metric = cfg.fusion.graphMetric(t, which.graph);
        int windowMin = cfg.fusion.window(t);

        String heading = switch (metric) {
            case FusionCategory.M_DEMAND -> "Demand";
            case FusionCategory.M_XP_PER_K -> "XP per 1,000 coins";
            default -> "Profit per fuse";
        };

        // One line per row of the table it belongs to - no separate setting,
        // because a graph showing shards the table does not list makes no sense.
        List<Scorer.Opportunity> source = rowsOf(engine, cfg, t);
        int n = source.size();

        int lineH = font.lineHeight + 1;
        int legendW = 0;
        for (int i = 0; i < n; i++) {
            legendW = Math.max(legendW, ICON + 3 + font.width(source.get(i).resultName()));
        }
        int w = Math.max(Math.max(190, legendW + 8), font.width(heading) + 8);
        int plotH = 60;
        int h = 3 + lineH + plotH + (n > 0 ? n * lineH + 3 : 0) + 4;

        begin(g, p);
        Draw.panel(g, w, h, Draw.groupColour(t));
        g.text(font, heading, 4, 3, Draw.TITLE);

        long now = System.currentTimeMillis() / 1000;
        long windowStart = now - (long) windowMin * 60;

        List<double[][]> series = new ArrayList<>(n);
        int[] colours = new int[Math.max(1, n)];
        for (int i = 0; i < n; i++) {
            var pts = engine.historyFor(source.get(i).resultTag(), windowMin);
            // A shard that only just entered the table has no history before it
            // appeared. Anchoring it to zero at that moment makes the steep rise
            // mark it as new, rather than starting mid-air as if it had been
            // there all along.
            boolean isNew = !pts.isEmpty() && pts.get(0).epochSeconds() > windowStart + 5;
            int extra = isNew ? 1 : 0;

            double[][] arr = new double[pts.size() + extra][2];
            if (isNew) {
                arr[0][0] = pts.get(0).epochSeconds();
                arr[0][1] = 0;
            }
            for (int j = 0; j < pts.size(); j++) {
                var pt = pts.get(j);
                arr[j + extra][0] = pt.epochSeconds();
                arr[j + extra][1] = switch (metric) {
                    case FusionCategory.M_DEMAND -> pt.salesPerHour();
                    case FusionCategory.M_XP_PER_K -> pt.xpPerCoin() * 1000.0;
                    default -> pt.profit();
                };
            }
            series.add(arr);
            colours[i] = Draw.SERIES[i % Draw.SERIES.length];
        }

        int plotTop = 3 + lineH;
        Draw.plot(g, font, 3, plotTop, w - 6, plotH, series, colours,
                metric == FusionCategory.M_PROFIT ? Draw::coins : Draw::units,
                preview ? "(graph preview)" : "collecting history...",
                windowStart, now);

        int ly = plotTop + plotH + 3;
        for (int i = 0; i < n; i++) {
            drawIcon(g, font, 4, ly, ICON, source.get(i));
            g.text(font, source.get(i).resultName(), 4 + ICON + 3, ly + 1, colours[i]);
            ly += lineH;
        }

        end(g);
        return new int[]{w, h};
    }
}
