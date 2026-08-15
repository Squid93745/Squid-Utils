package dev.squidutils.fusion.hud;

import dev.squidutils.config.FusionCategory;
import dev.squidutils.config.SquidUtilsConfig;
import dev.squidutils.config.WidgetPos;
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

    /** Three tables each owning three graphs, the session tracker, and the
     *  shopping list - the last two share the "not a table" sentinel, so
     *  telling them apart is by identity, not by field. */
    public enum Which {
        REC_TABLE(0, -1), REC_G1(0, 0), REC_G2(0, 1), REC_G3(0, 2),
        COIN_TABLE(1, -1), COIN_G1(1, 0), COIN_G2(1, 1), COIN_G3(1, 2),
        XP_TABLE(2, -1), XP_G1(2, 0), XP_G2(2, 1), XP_G3(2, 2),
        TRACKER(-1, -1),
        SHOPPING_LIST(-1, -1);

        public final int table;
        public final int graph;

        Which(int table, int graph) {
            this.table = table;
            this.graph = graph;
        }

        public boolean isGraph() { return graph >= 0; }
        public boolean isTracker() { return this == TRACKER; }
        public boolean isShoppingList() { return this == SHOPPING_LIST; }
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

    /** {@link RowHit#rootRecipe()} sentinel meaning "open the shopping list's
     *  aggregated fuse-order screen", not a route to a single recipe -
     *  reusing the same hit list rather than a second one just for this. */
    public static final int SHOPPING_ROUTE_HIT = -2;

    /** A sortable legend cell, in screen-space pixels. */
    private record HeaderHit(int x, int y, int w, int h, int table, ColKey key) {
        boolean contains(double mx, double my) {
            return mx >= x && mx <= x + w && my >= y && my <= y + h;
        }
    }

    private static final List<Hit> HITS = new ArrayList<>();
    private static final List<RowHit> ROW_HITS = new ArrayList<>();
    private static final List<HeaderHit> HEADER_HITS = new ArrayList<>();

    /**
     * Which column each table is sorted by, and which direction - session-only
     * view state, not a setting worth persisting to config.json. Null means the
     * table's natural (engine-ranked) order, which is the default until a
     * legend cell is clicked.
     */
    private static final ColKey[] sortColumn = new ColKey[3];
    private static final boolean[] sortDescending = {true, true, true};

    /** Called once per frame before drawing; hit regions are per-frame. */
    public static void clearHits() {
        HITS.clear();
        ROW_HITS.clear();
        HEADER_HITS.clear();
    }

    public static List<Hit> hits() { return HITS; }

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

    private static final String[] TABLE_NAME = {"Recommended", "Profit Shards", "XP per fuse"};

    private FusionWidgets() {}

    public static WidgetPos pos(SquidUtilsConfig cfg, Which which) {
        cfg.general.normalise();
        if (which.isTracker()) return cfg.general.trackerPos;
        if (which.isShoppingList()) return cfg.general.shoppingListPos;
        return which.isGraph()
                ? cfg.general.graphPos[which.table][which.graph]
                : cfg.general.tablePos[which.table];
    }

    private static final String[] METRIC_NAME = {"profit", "demand", "xp per coin"};

    public static String title(Which which) {
        if (which.isTracker()) return "Session tracker";
        if (which.isShoppingList()) return "Shopping list";
        return which.isGraph()
                ? TABLE_NAME[which.table] + " · " + METRIC_NAME[which.graph]
                : TABLE_NAME[which.table];
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
        if (which.isShoppingList()) return cfg.fusion.general.shoppingListShow;
        if (!cfg.fusion.tableShown(which.table)) return false;   // graphs follow their table
        return !which.isGraph() || cfg.fusion.graphOn(which.table, which.graph);
    }

    /** The table a graph belongs to, for the connector line. */
    public static Which tableOf(Which graph) {
        return switch (graph.table) {
            case 1 -> Which.COIN_TABLE;
            case 2 -> Which.XP_TABLE;
            default -> Which.REC_TABLE;
        };
    }

    public static int[] draw(GuiGraphicsExtractor g, Font font, SquidUtilsConfig cfg,
                             FusionEngine engine, Which which, boolean preview) {
        if (which.isTracker()) return tracker(g, font, cfg);
        if (which.isShoppingList()) return shoppingList(g, font, engine, pos(cfg, which));
        return which.isGraph()
                ? graph(g, font, cfg, engine, which, preview)
                : table(g, font, cfg, engine, which, preview);
    }

    // ------------------------------------------------------------------
    /** Session totals, laid out like the trackers players already know. */
    private static int[] tracker(GuiGraphicsExtractor g, Font font, SquidUtilsConfig cfg) {
        var t = dev.squidutils.SquidUtils.tracker();
        int lineH = font.lineHeight + 1;

        List<Cell> lines = new ArrayList<>();
        lines.add(new Cell("Fusion session tracker", Draw.TITLE));
        if (t.paused()) lines.add(new Cell("[Paused]", Draw.C_FILL));

        if (cfg.fusion.tracker.trackerCoins) {
            lines.add(new Cell("Spent: " + Draw.coins(t.coinsSpent())
                    + " (" + Draw.coins(t.perHour(t.coinsSpent())) + "/h)", Draw.C_COST));
            lines.add(new Cell("Earned: " + Draw.coins(t.coinsGained())
                    + " (" + Draw.coins(t.perHour(t.coinsGained())) + "/h)", Draw.C_PROFIT));
            double net = t.profit();
            lines.add(new Cell("Profit: " + (net >= 0 ? "+" : "") + Draw.coins(net)
                    + " (" + Draw.coins(t.perHour(net)) + "/h)",
                    net >= 0 ? Draw.C_PROFIT : 0xFFFF6666));
        }
        if (cfg.fusion.tracker.trackerXp) {
            lines.add(new Cell("Hunting XP: " + Draw.units(t.xpGained())
                    + " (" + Draw.units(t.perHour(t.xpGained())) + "/h)", Draw.C_XP));
        }
        if (cfg.fusion.tracker.trackerShards) {
            lines.add(new Cell("Fusions: " + t.fuses()
                    + " (" + Draw.units(t.perHour(t.fuses())) + "/h)"
                    + "  ·  " + t.shardsFused() + " shards out", Draw.C_FIT));
            lines.add(new Cell("Bought " + t.shardsBought() + " · sold " + t.shardsSold(),
                    Draw.C_VOLUME));
        }
        lines.add(new Cell("Elapsed: " + formatElapsed(t.elapsedSeconds()), Draw.DIM));

        int width = 150;
        for (Cell c : lines) width = Math.max(width, font.width(c.text()));

        int height = lineH * lines.size() + 8;
        begin(g, cfg.general.trackerPos);
        Draw.panel(g, width + 8, height, 0xC07FD4FF);

        int y = 4;
        for (Cell c : lines) {
            g.text(font, c.text(), 4, y, c.colour());
            y += lineH;
        }
        end(g);
        return new int[]{width + 8, height};
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
    private static int[] shoppingList(GuiGraphicsExtractor g, Font font, FusionEngine engine, WidgetPos p) {
        var entries = ShoppingList.entries();
        FusionData data = engine.data();
        var costs = engine.routeCosts();
        int lineH = font.lineHeight + 1;

        List<String> lineTexts = new ArrayList<>(entries.size());
        double total = 0;
        for (var e : entries) {
            var s = data.shard(e.shardIndex());
            double cost = costs != null ? costs.cost()[e.shardIndex()] * e.units() : 0;
            total += cost;
            lineTexts.add(s.name() + " x" + e.units() + "  (" + Draw.coins(cost) + ")");
        }
        String title = entries.isEmpty() ? "Shopping list" : "Shopping list  (" + Draw.coins(total) + ")";
        boolean hasSteps = ShoppingList.hasSteps();
        String routeLink = "▸ View fuse order (" + ShoppingList.steps().size() + " steps)";

        int width = font.width(title);
        for (String line : lineTexts) width = Math.max(width, ICON + 6 + font.width(line));
        String empty = "empty - \"Add to shopping list\" on a route screen";
        if (entries.isEmpty()) width = Math.max(width, font.width(empty));
        if (hasSteps) width = Math.max(width, font.width(routeLink));

        int height = lineH * (1 + Math.max(1, entries.size()) + (hasSteps ? 1 : 0)) + 8;
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
                g.text(font, lineTexts.get(i), 4 + ICON + 6, y, 0xFF7FD4FF);
                HITS.add(new Hit(
                        p.x + Math.round(4 * p.scale),
                        p.y + Math.round((y - 1) * p.scale),
                        Math.round((ICON + 6 + font.width(lineTexts.get(i))) * p.scale),
                        Math.round((font.lineHeight + 2) * p.scale),
                        s.name(), e.units(), e.shardIndex()));
                y += lineH;
            }
        }

        if (hasSteps) {
            g.text(font, routeLink, 4, y, 0xFFB86BFF);
            ROW_HITS.add(new RowHit(
                    p.x + Math.round(4 * p.scale),
                    p.y + Math.round((y - 1) * p.scale),
                    Math.round(font.width(routeLink) * p.scale),
                    Math.round((font.lineHeight + 2) * p.scale),
                    SHOPPING_ROUTE_HIT));
            y += lineH;
        }
        end(g);
        return new int[]{width + 8, height};
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

    /** Shards shown by one table, trimmed to its own row count. */
    private static List<Scorer.Opportunity> rowsOf(FusionEngine engine, SquidUtilsConfig cfg, int t) {
        List<Scorer.Opportunity> src = switch (t) {
            case 1 -> engine.byCoins();
            case 2 -> engine.byXp();
            default -> recommendedOps(engine);
        };
        int n = Math.min(cfg.fusion.rows(t), src.size());
        return src.subList(0, Math.max(0, n));
    }

    private static List<Scorer.Opportunity> recommendedOps(FusionEngine engine) {
        List<Scorer.Opportunity> out = new ArrayList<>();
        for (Recommender.Scored s : engine.recommended()) out.add(s.opportunity());
        return out;
    }

    // ------------------------------------------------------------------
    private record Cell(String text, int colour) {}

    /** Which column, keyed for sorting - not display order, which is table-specific. */
    private enum ColKey { COST, PROFIT, ROI, FILL_SEC, VOLUME, FIT, XP, XP_PER_K, STABILITY, FILL, BOTTLENECK }

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

    private static List<Column> columnsFor(int t, SquidUtilsConfig cfg) {
        List<Column> cols = new ArrayList<>();
        if (t == 2) {
            cols.add(new Column(ColKey.COST, "cost", Draw.C_COST));
            cols.add(new Column(ColKey.XP, "xp", Draw.C_XP));
            cols.add(new Column(ColKey.XP_PER_K, "xp/1k coins", Draw.C_XP));
            cols.add(new Column(ColKey.PROFIT, "profit", Draw.C_PROFIT));
            cols.add(new Column(ColKey.VOLUME, "sold/h", Draw.C_VOLUME));
        } else {
            boolean rec = t == 0;
            cols.add(new Column(ColKey.COST, "cost", Draw.C_COST));
            cols.add(new Column(ColKey.PROFIT, "profit", Draw.C_PROFIT));
            cols.add(new Column(ColKey.ROI, "roi", Draw.C_ROI));
            if (rec) cols.add(new Column(ColKey.FILL_SEC, "fill", Draw.C_FILL));
            cols.add(new Column(ColKey.VOLUME, "sold/h", Draw.C_VOLUME));
            if (rec) cols.add(new Column(ColKey.FIT, "fit", Draw.C_FIT));
        }
        // Shared trailing columns, same as every table's settings.
        if (cfg.fusion.tables.showStability) cols.add(new Column(ColKey.STABILITY, "stable", Draw.C_STABLE));
        boolean ownFill = t == 0;   // the recommended table already has fill, in seconds
        if (cfg.fusion.tables.showFillTimes && !ownFill) {
            cols.add(new Column(ColKey.FILL, "fill", Draw.C_FILL));
        }
        if (cfg.fusion.tables.showBottleneck) cols.add(new Column(ColKey.BOTTLENECK, "bottleneck", Draw.DIM));
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
            case VOLUME -> o.salesPerHour();
            case FIT -> d.fit();
            case XP -> o.xpPerFuse();
            case XP_PER_K -> d.xpPerK();
            case STABILITY -> d.stability();
            case FILL -> Recommender.fillSeconds(o);
            case BOTTLENECK -> 0;
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
            case VOLUME -> Draw.units(o.salesPerHour()) + "/h";
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
        };
    }

    /**
     * Resolve one row's numbers, swapping in {@link RouteSolver}'s route when
     * multi-step mode is on and a route actually exists.
     *
     * <p>A route can be missing even in multi-step mode: if buying the output
     * straight off the bazaar beats every fusion route to it, {@code via} is
     * {@link RouteSolver#BUY} and the row falls back to its normal one-hop
     * numbers rather than showing a nonsensical "0 steps".
     */
    private static RowData rowData(SquidUtilsConfig cfg, FusionEngine engine, int t,
                                   Scorer.Opportunity o, double fit) {
        double cost = o.cost(), profit = o.profit(), roi = o.roi();
        int rootRecipe = -1, steps = 0;

        if (cfg.fusion.multiStep(t)) {
            var costs = engine.routeCosts();
            var data = engine.data();
            int shardIndex = costs == null ? -1 : data.indexOfTag(o.resultTag());
            int via = shardIndex >= 0 ? costs.via()[shardIndex] : RouteSolver.BUY;
            if (via != RouteSolver.BUY) {
                var route = RouteSolver.explain(data, costs, via);
                double routeCost = RouteSolver.routeCost(data, costs, route);
                double revenue = o.profit() + o.cost();   // recover sell-side revenue
                cost = routeCost;
                profit = revenue - routeCost;
                roi = cost > 0 ? profit / cost : 0;
                rootRecipe = via;
                steps = route.steps().size();
            }
        }

        double xpPerK = cost > 0 ? o.xpPerFuse() / cost * 1000.0 : 0;
        double stability = engine.stabilityFor(o.resultTag());
        return new RowData(o, cost, profit, roi, fit, xpPerK, stability,
                rootRecipe >= 0, rootRecipe, steps);
    }

    private static List<Row> buildRows(SquidUtilsConfig cfg, FusionEngine engine, int t,
                                       List<Scorer.Opportunity> ops, List<Column> columns) {
        List<Row> rows = new ArrayList<>(ops.size());
        boolean rec = t == 0;
        List<Recommender.Scored> scored = rec ? engine.recommended() : List.of();

        for (int i = 0; i < ops.size(); i++) {
            Scorer.Opportunity o = ops.get(i);
            double fit = (rec && i < scored.size()) ? scored.get(i).score() : 0;
            RowData d = rowData(cfg, engine, t, o, fit);
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
        List<Scorer.Opportunity> ops = rowsOf(engine, cfg, t);

        List<Column> columns = columnsFor(t, cfg);
        List<Row> rows = buildRows(cfg, engine, t, ops, columns);
        applySort(t, rows, columns);

        List<Cell> legend = new ArrayList<>(columns.size());
        for (Column c : columns) legend.add(new Cell(c.legend(), c.colour()));

        boolean compact = cfg.fusion.general.compact;
        boolean showLegend = cfg.fusion.general.showLegend && !legend.isEmpty();
        int lineH = font.lineHeight + 1;
        int[] colX = columnOffsets(font, rows, legend);

        String heading = TABLE_NAME[t];
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
