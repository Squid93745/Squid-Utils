package dev.squidutils.config;

import com.google.gson.annotations.Expose;
import io.github.notenoughupdates.moulconfig.annotations.Category;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Shard fusion settings - purely organisational now. This class holds no
 * options of its own, only the six sub-pages that appear indented beneath
 * "Shard Fusion" in the sidebar once it is selected: {@link
 * FusionGeneralCategory}, {@link FusionTablesCategory}, {@link
 * FusionGraphsCategory}, {@link FusionTooltipsCategory}, {@link
 * FusionTrackerCategory} and {@link FusionSettingsCategory}.
 *
 * <p>A {@code @Category} field nests one level by calling {@code
 * ConfigStructureReader.setCategoryParent(Field)} on the field that declares
 * it - {@link SquidUtilsConfig#fusion} here - which is why this class can
 * stay a plain container. MoulConfig only resolves one level of nesting: a
 * {@code @Category} declared inside one of the six children would warn
 * "Found double recursive sub category" and be dropped, so none of them may
 * themselves hold a further {@code @Category} field.
 *
 * <p>The per-table accessors below ({@code tableShown}, {@code rows}, {@code
 * graphOn}, ...) are kept here rather than moved onto {@code
 * FusionTablesCategory}/{@code FusionGraphsCategory}, so the table-index loops
 * in {@code FusionWidgets} (e.g. {@code cfg.fusion.rows(t)}) did not need to
 * learn the new nested field paths.
 */
public class FusionCategory {

    /** Runnable ids for buttons, dispatched by SquidUtilsConfig. */
    public static final int RUN_PAUSE = 1;
    public static final int RUN_RESET = 2;

    /** Metric ids, matching the graph dropdown order. */
    public static final int M_PROFIT = 0, M_DEMAND = 1, M_XP_PER_K = 2;

    @Expose
    @Category(name = "General", desc = "Master switches and shared display")
    public FusionGeneralCategory general = new FusionGeneralCategory();

    @Expose
    @Category(name = "Tables", desc = "Which lists to show, and what they contain")
    public FusionTablesCategory tables = new FusionTablesCategory();

    @Expose
    @Category(name = "Graphs", desc = "Plots attached to each table")
    public FusionGraphsCategory graphs = new FusionGraphsCategory();

    @Expose
    @Category(name = "Tooltips", desc = "Extra lines added to shard tooltips")
    public FusionTooltipsCategory tooltips = new FusionTooltipsCategory();

    @Expose
    @Category(name = "Tracker", desc = "Session totals for a fusion run")
    public FusionTrackerCategory tracker = new FusionTrackerCategory();

    @Expose
    @Category(name = "Settings", desc = "Trading, filters and scoring internals")
    public FusionSettingsCategory settings = new FusionSettingsCategory();

    // ==================================================================
    // Per-table accessors, so rendering stays index-driven.

    public boolean tableShown(int t) {
        return switch (t) { case 1 -> tables.coinsShow; case 2 -> tables.xpShow; default -> tables.recShow; };
    }

    public int rows(int t) {
        return switch (t) { case 1 -> tables.coinsRows; case 2 -> tables.xpRows; default -> tables.recRows; };
    }

    public boolean multiStep(int t) {
        return switch (t) { case 1 -> tables.coinsMultiStep; case 2 -> tables.xpMultiStep; default -> tables.recMultiStep; };
    }

    /** One window for every graph now; kept per-table for the call sites. */
    public int window(int t) {
        return graphs.graphWindow;
    }

    public int maxWindow() {
        return graphs.graphWindow;
    }

    public boolean graphOn(int t, int g) {
        return switch (t) {
            case 1 -> switch (g) {
                case 1 -> graphs.profitShards.demandGraph;
                case 2 -> graphs.profitShards.xpGraph;
                default -> graphs.profitShards.profitGraph;
            };
            case 2 -> switch (g) {
                case 1 -> graphs.xpTable.demandGraph;
                case 2 -> graphs.xpTable.xpGraph;
                default -> graphs.xpTable.profitGraph;
            };
            default -> switch (g) {
                case 1 -> graphs.recommended.demandGraph;
                case 2 -> graphs.recommended.xpGraph;
                default -> graphs.recommended.profitGraph;
            };
        };
    }

    /** Slot index is the metric id: profit, demand, xp per 1,000 coins. */
    public int graphMetric(int t, int g) {
        return g;
    }

    public int maxRows() {
        int m = 3;
        for (int t = 0; t < 3; t++) m = Math.max(m, rows(t));
        return m;
    }

    // ------------------------------------------------------------------
    public static Set<String> parseList(String raw) {
        Set<String> out = new LinkedHashSet<>();
        if (raw == null || raw.isBlank()) return out;
        Arrays.stream(raw.split(","))
                .map(s -> s.trim().toLowerCase(Locale.ROOT))
                .filter(s -> !s.isEmpty())
                .forEach(out::add);
        return out;
    }

    public static double parseNumber(String raw, double fallback) {
        try {
            return Double.parseDouble(raw.trim().replace("_", "").replace(",", ""));
        } catch (RuntimeException e) {
            return fallback;
        }
    }
}
