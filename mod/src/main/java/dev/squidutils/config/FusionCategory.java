package dev.squidutils.config;

import com.google.gson.annotations.Expose;
import io.github.notenoughupdates.moulconfig.annotations.Category;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorBoolean;
import io.github.notenoughupdates.moulconfig.annotations.ConfigOption;
import io.github.notenoughupdates.moulconfig.annotations.SearchTag;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Shard fusion settings. The master switches and shared display options
 * below render directly on "Shard Fusion"'s own page - a {@code @Category}
 * field's directly-declared {@code @ConfigOption} fields are their own page,
 * entirely independent of whichever further sub-pages that same class also
 * declares (confirmed from MoulConfig's own {@code ConfigProcessorDriver}:
 * {@code processCategory} emits options into the category currently open
 * between its {@code beginCategory}/{@code endCategory} regardless of
 * whether that category also queues {@code @Category} children to be
 * processed afterward as separate, indented pages) - so there is no need for
 * a dedicated "General" sub-page just to hold them, the way there used to be.
 *
 * <p>Everything else is still purely organisational: the five further
 * sub-pages that appear indented beneath "Shard Fusion" in the sidebar once
 * it is selected are {@link FusionTablesCategory}, {@link
 * FusionGraphsCategory}, {@link FusionTooltipsCategory}, {@link
 * FusionTrackerCategory} and {@link FusionSettingsCategory}.
 *
 * <p>A {@code @Category} field nests one level by calling {@code
 * ConfigStructureReader.setCategoryParent(Field)} on the field that declares
 * it - {@link SquidUtilsConfig#fusion} here. MoulConfig only resolves one
 * level of nesting: a {@code @Category} declared inside one of the five
 * children would warn "Found double recursive sub category" and be dropped,
 * so none of them may themselves hold a further {@code @Category} field.
 *
 * <p>The per-table accessors below ({@code tableShown}, {@code rows}, {@code
 * graphOn}, ...) are kept here rather than moved onto {@code
 * FusionTablesCategory}/{@code FusionGraphsCategory}, so the table-index loops
 * in {@code FusionWidgets} (e.g. {@code cfg.fusion.rows(t)}) did not need to
 * learn the new nested field paths.
 */
public class FusionCategory {

    /** Metric ids, matching the graph dropdown order. */
    public static final int M_PROFIT = 0, M_DEMAND = 1, M_XP_PER_K = 2;

    @Expose
    @ConfigOption(name = "Enable shard fusion", desc = "Rank fusions and draw the panels.")
    @ConfigEditorBoolean
    @SearchTag("fusion shard enable bazaar profit")
    public boolean enabled = true;

    @Expose
    @ConfigOption(name = "Color legend",
            desc = "A key along the bottom of each table showing what each color "
                    + "of number means.")
    @ConfigEditorBoolean
    @SearchTag("legend key color colour explain numbers")
    public boolean showLegend = true;

    @Expose
    @ConfigOption(name = "Compact rows",
            desc = "Drop to one line per entry. Useful at small GUI scales.")
    @ConfigEditorBoolean
    @SearchTag("compact dense small lines")
    public boolean compact = false;

    @Expose
    @ConfigOption(name = "Show shopping list",
            desc = "Draw the accumulated shopping list panel, moveable in the "
                    + "overlay editor like every other panel. Add to it from a "
                    + "route screen's \"Add to shopping list\" button.")
    @ConfigEditorBoolean
    @SearchTag("shopping list visitor panel show")
    public boolean shoppingListShow = false;

    @Expose
    @ConfigOption(name = "Show fuse order",
            desc = "Draw a panel listing every fusion step queued up in the "
                    + "shopping list, dependency-ordered. Stays visible under the "
                    + "Fusion Box like every other panel, so you can read it while "
                    + "actually working through the fusions.")
    @ConfigEditorBoolean
    @SearchTag("fuse order shopping list panel show fusion box")
    public boolean fuseOrderShow = false;

    @Expose
    @ConfigOption(name = "Read wisdom automatically",
            desc = "Pick up Hunting Wisdom from the SkyBlock stats menu whenever "
                    + "you open it, and refine it further from every fusion's "
                    + "actual XP gain. Off freezes it at its last detected value.")
    @ConfigEditorBoolean
    @SearchTag("wisdom auto detect automatic stats read player")
    public boolean autoDetectWisdom = true;

    /**
     * Fusion XP scales as {@code base x (1 + wisdom / 100)}. No longer a
     * config editor field - it was a manual slider before auto-detection
     * existed, and a value the player can accidentally overwrite defeats
     * the point of detecting it automatically. {@link
     * dev.squidutils.fusion.WisdomDetector} and {@link
     * dev.squidutils.fusion.SessionTracker#reverseEngineerWisdom} both write
     * this field directly, and it is still {@code @Expose}d so its detected
     * value survives a restart.
     */
    @Expose
    public float huntingWisdom = 41.5f;

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

    /**
     * Table index space: 0 Recommended, 1-4 the four Profit Shards variants
     * ({@link FusionTablesCategory#profit1} through {@code profit4}), 5 XP
     * per fuse - not 0/1/2 the way this used to be, now that Profit Shards is
     * four independently-configured tables instead of one.
     */
    public boolean tableShown(int t) {
        return switch (t) {
            case 1 -> tables.profit1.show;
            case 2 -> tables.profit2.show;
            case 3 -> tables.profit3.show;
            case 4 -> tables.profit4.show;
            case 5 -> tables.xpTable.show;
            default -> tables.recommended.show;
        };
    }

    public int rows(int t) {
        return switch (t) {
            case 1 -> tables.profit1.rows;
            case 2 -> tables.profit2.rows;
            case 3 -> tables.profit3.rows;
            case 4 -> tables.profit4.rows;
            case 5 -> tables.xpTable.rows;
            default -> tables.recommended.rows;
        };
    }

    public boolean multiStep(int t) {
        return switch (t) {
            case 1 -> tables.profit1.multiStep;
            case 2 -> tables.profit2.multiStep;
            case 3 -> tables.profit3.multiStep;
            case 4 -> tables.profit4.multiStep;
            case 5 -> tables.xpTable.multiStep;
            default -> tables.recommended.multiStep;
        };
    }

    /**
     * Buy/sell mode for one of the four Profit Shards tables, read by {@code
     * SquidUtils} to derive that table's own {@code Scorer.Settings} via
     * {@code Settings#withMode}. {@code variant} is 1-4, matching {@link
     * #tableShown} et al.'s table-index numbering for those slots, not a
     * separate 0-based index.
     */
    public int profitVariantBuyMode(int variant) {
        return switch (variant) {
            case 2 -> tables.profit2.buyMode;
            case 3 -> tables.profit3.buyMode;
            case 4 -> tables.profit4.buyMode;
            default -> tables.profit1.buyMode;
        };
    }

    public int profitVariantSellMode(int variant) {
        return switch (variant) {
            case 2 -> tables.profit2.sellMode;
            case 3 -> tables.profit3.sellMode;
            case 4 -> tables.profit4.sellMode;
            default -> tables.profit1.sellMode;
        };
    }

    /** One window for every graph now; kept per-table for the call sites. */
    public int window(int t) {
        return graphs.graphWindow;
    }

    public int maxWindow() {
        return graphs.graphWindow;
    }

    /**
     * The four Profit Shards tables (1-4) have no graphs of their own - see
     * the class doc on {@code FusionTablesCategory.ProfitVariant} for why -
     * so every graph index in that range is simply off.
     */
    public boolean graphOn(int t, int g) {
        return switch (t) {
            case 1, 2, 3, 4 -> false;
            case 5 -> switch (g) {
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
        for (int t = 0; t < 6; t++) m = Math.max(m, rows(t));
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
