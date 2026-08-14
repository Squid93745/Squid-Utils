package dev.squidutils.config;

import com.google.gson.annotations.Expose;
import io.github.notenoughupdates.moulconfig.annotations.Accordion;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorBoolean;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorSlider;
import io.github.notenoughupdates.moulconfig.annotations.ConfigOption;
import io.github.notenoughupdates.moulconfig.annotations.SearchTag;

/**
 * Which lists to show, and what they contain, under Shard Fusion in the
 * sidebar. One accordion per table - each one's show/rows/multi-step toggles
 * grouped together, the same treatment {@link FusionGraphsCategory} gives its
 * per-table graph groups. The three shared column toggles at the bottom stay
 * flat: they apply to every table at once, not to any one of them.
 */
public class FusionTablesCategory {

    @Expose
    @ConfigOption(name = "Recommended", desc = "Profit (50%), fill speed (30%), volume (20%).")
    @Accordion
    public Recommended recommended = new Recommended();

    @Expose
    @ConfigOption(name = "Profit Shards", desc = "Ranked by margin x units sold per hour.")
    @Accordion
    public ProfitShards profitShards = new ProfitShards();

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
            desc = "Which flow limits throughput: an input, or output absorption - "
                    + "and roughly how much of it trades per hour.")
    @ConfigEditorBoolean
    @SearchTag("bottleneck limit throughput volume column")
    public boolean showBottleneck = true;

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

    public static class ProfitShards {
        @Expose
        @ConfigOption(name = "Show table",
                desc = "Ranked by profit per fuse multiplied by units sold per hour - "
                        + "a margin only counts if the market absorbs it. A 400k "
                        + "margin on a shard trading twice an hour loses to a 40k "
                        + "margin on one trading five hundred times.")
        @ConfigEditorBoolean
        @SearchTag("profit shards table money volume list show")
        public boolean show = false;

        @Expose
        @ConfigOption(name = "Rows", desc = "Fusions listed, and graph lines.")
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
