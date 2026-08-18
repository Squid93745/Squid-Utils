package dev.squidutils.config;

import com.google.gson.annotations.Expose;
import io.github.notenoughupdates.moulconfig.annotations.Accordion;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorBoolean;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorDropdown;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorSlider;
import io.github.notenoughupdates.moulconfig.annotations.ConfigOption;
import io.github.notenoughupdates.moulconfig.annotations.SearchTag;

/**
 * Which lists to show, and what they contain, under Shard Fusion in the
 * sidebar. One accordion per table - each one's show/rows/multi-step toggles
 * grouped together, the same treatment {@link FusionGraphsCategory} gives its
 * per-table graph groups. The three shared column toggles at the bottom stay
 * flat: they apply to every table at once, not to any one of them.
 *
 * <p>Profit Shards is four independent tables ({@link #profit1} through
 * {@link #profit4}), not one - each carries its own buy/sell mode, so you can
 * have "Instabuy into Instasell" open next to "Buy order into Sell offer"
 * instead of flipping the one global Trading setting back and forth to
 * compare them. There is deliberately no fifth or sixth: {@link
 * dev.squidutils.fusion.engine.Scorer.BuyMode} x {@link
 * dev.squidutils.fusion.engine.Scorer.SellMode} only has four combinations to
 * begin with, so four covers every one of them and there is nothing a fifth
 * slot could show that would not just repeat one of the first four.
 */
public class FusionTablesCategory {

    @Expose
    @ConfigOption(name = "Recommended", desc = "Profit (50%), fill speed (30%), volume (20%).")
    @Accordion
    public Recommended recommended = new Recommended();

    @Expose
    @ConfigOption(name = "Profit Shards 1",
            desc = "Ranked by margin x units sold per hour. Instabuy in, sell "
                    + "offer out, by default.")
    @Accordion
    public ProfitVariant profit1 = new ProfitVariant(true, 0, 0);

    @Expose
    @ConfigOption(name = "Profit Shards 2",
            desc = "A second, independent Profit Shards table, for comparing "
                    + "trading methods side by side rather than one at a time. "
                    + "Instabuy in, instasell out, by default.")
    @Accordion
    public ProfitVariant profit2 = new ProfitVariant(false, 0, 1);

    @Expose
    @ConfigOption(name = "Profit Shards 3",
            desc = "A third Profit Shards table. Buy order in, sell offer "
                    + "out, by default.")
    @Accordion
    public ProfitVariant profit3 = new ProfitVariant(false, 1, 0);

    @Expose
    @ConfigOption(name = "Profit Shards 4",
            desc = "A fourth Profit Shards table. Buy order in, instasell "
                    + "out, by default.")
    @Accordion
    public ProfitVariant profit4 = new ProfitVariant(false, 1, 1);

    @Expose
    @ConfigOption(name = "XP per fuse", desc = "Ranked by XP per fuse, ties broken by XP per coin.")
    @Accordion
    public XpTable xpTable = new XpTable();

    @Expose
    @ConfigOption(name = "Show stability",
            desc = "How steady each fusion's profit has been across refreshes.")
    @ConfigEditorBoolean
    @SearchTag("stability confidence variance steady column")
    public boolean showStability = true;

    @Expose
    @ConfigOption(name = "Show fill times",
            desc = "Estimated time for orders to fill, assuming nobody undercuts.")
    @ConfigEditorBoolean
    @SearchTag("fill time seconds wait order column")
    public boolean showFillTimes = true;

    @Expose
    @ConfigOption(name = "Show bottleneck",
            desc = "Why the batch column stops where it does: which leg - an "
                    + "input, or the output sale - runs out of cheap order-book "
                    + "room first, and by how much its price has moved.")
    @ConfigEditorBoolean
    @SearchTag("bottleneck limit batch depth column")
    public boolean showBottleneck = true;

    @Expose
    @ConfigOption(name = "Show batch limit",
            desc = "How many fuses of this recipe you could do at once before "
                    + "buying/selling deeper into the order book drags the "
                    + "average profit per fuse down past the Batch profit "
                    + "tolerance in Settings - Trading.")
    @ConfigEditorBoolean
    @SearchTag("batch limit depth quantity column")
    public boolean showDepthLimit = true;

    public static class Recommended {
        @Expose
        @ConfigOption(name = "Show table",
                desc = "Weighted pick: profit (50%), fill speed (30%), volume (20%). "
                        + "The easiest table to just trust.")
        @ConfigEditorBoolean
        @SearchTag("recommended beginner easy suggested table show")
        public boolean show = true;

        @Expose
        @ConfigOption(name = "Rows", desc = "Fusions listed, and graph lines.")
        @ConfigEditorSlider(minValue = 3, maxValue = 20, minStep = 1)
        @SearchTag("rows count recommended")
        public int rows = 5;

        @Expose
        @ConfigOption(name = "Multi-step routes",
                desc = "Rows show the cheapest full fusion chain to the output "
                        + "instead of one direct fusion - \"Etherdrake (5 steps)\". "
                        + "Click a row to see the chain.")
        @ConfigEditorBoolean
        @SearchTag("multi step recursive chain route recommended")
        public boolean multiStep = false;
    }

    /**
     * One instance per Profit Shards slot - {@link #profit1} through {@link
     * #profit4} each hold one of these, differing only in their starting
     * {@link #show}/{@link #buyMode}/{@link #sellMode} defaults (set via the
     * constructor below); every field past that is independently editable
     * per slot regardless of how it started out.
     *
     * <p>No graphs here unlike Recommended and XP per fuse: four tables times
     * three metrics each would be twelve more graph toggles for a feature
     * about comparing trading methods on one screen, which a graph plotted
     * against wall-clock time does not help with - the table numbers already
     * update every refresh.
     */
    public static class ProfitVariant {
        @Expose
        @ConfigOption(name = "Show table",
                desc = "Ranked by profit per fuse multiplied by units sold per hour - "
                        + "a margin only counts if the market absorbs it. A 400k "
                        + "margin on a shard trading twice an hour loses to a 40k "
                        + "margin on one trading five hundred times.")
        @ConfigEditorBoolean
        @SearchTag("profit shards table money volume list show")
        public boolean show;

        @Expose
        @ConfigOption(name = "Rows", desc = "Fusions listed.")
        @ConfigEditorSlider(minValue = 3, maxValue = 20, minStep = 1)
        @SearchTag("rows count profit shards")
        public int rows = 5;

        @Expose
        @ConfigOption(name = "Multi-step routes",
                desc = "Rows show the cheapest full fusion chain to the output "
                        + "instead of one direct fusion - \"Etherdrake (5 steps)\". "
                        + "Click a row to see the chain.")
        @ConfigEditorBoolean
        @SearchTag("multi step recursive chain route profit shards")
        public boolean multiStep = false;

        @Expose
        @ConfigOption(name = "Buying inputs",
                desc = "Instabuy pays the ask and starts immediately. A buy order "
                        + "is cheaper but only fills as fast as people insta-sell "
                        + "into it.")
        @ConfigEditorDropdown(values = {"Instabuy", "Buy order"})
        @SearchTag("buy instabuy order acquire input profit shards")
        public int buyMode;

        @Expose
        @ConfigOption(name = "Selling output",
                desc = "A sell offer earns the spread but waits for insta-buyers.")
        @ConfigEditorDropdown(values = {"Sell offer", "Instasell"})
        @SearchTag("sell offer instasell dispose output profit shards")
        public int sellMode;

        /** No-arg constructor for Gson - see {@code WidgetPos} for the same note. */
        public ProfitVariant() {}

        public ProfitVariant(boolean show, int buyMode, int sellMode) {
            this.show = show;
            this.buyMode = buyMode;
            this.sellMode = sellMode;
        }
    }

    public static class XpTable {
        @Expose
        @ConfigOption(name = "Show table",
                desc = "Ranked by XP per fuse, ties broken by XP per coin spent.")
        @ConfigEditorBoolean
        @SearchTag("xp experience hunting table list show")
        public boolean show = false;

        @Expose
        @ConfigOption(name = "Rows", desc = "Fusions listed, and graph lines.")
        @ConfigEditorSlider(minValue = 3, maxValue = 20, minStep = 1)
        @SearchTag("rows count xp")
        public int rows = 5;

        @Expose
        @ConfigOption(name = "Multi-step routes",
                desc = "Rows show the cheapest full fusion chain to the output "
                        + "instead of one direct fusion - \"Etherdrake (5 steps)\". "
                        + "Click a row to see the chain.")
        @ConfigEditorBoolean
        @SearchTag("multi step recursive chain route xp")
        public boolean multiStep = false;
    }
}
