package dev.squidutils.config;

import com.google.gson.annotations.Expose;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorBoolean;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorSlider;
import io.github.notenoughupdates.moulconfig.annotations.ConfigOption;
import io.github.notenoughupdates.moulconfig.annotations.SearchTag;

/** Which lists to show, and what they contain, under Shard Fusion in the sidebar. */
public class FusionTablesCategory {

    @Expose
    @ConfigOption(name = "Recommended table",
            desc = "Profit per fuse (50%), fill speed (30%), market volume (20%).")
    @ConfigEditorBoolean
    @SearchTag("recommended beginner easy suggested table")
    public boolean recShow = true;

    @Expose
    @ConfigOption(name = "Recommended rows", desc = "Fusions listed, and graph lines.")
    @ConfigEditorSlider(minValue = 3, maxValue = 20, minStep = 1)
    @SearchTag("rows count recommended")
    public int recRows = 5;

    @Expose
    @ConfigOption(name = "Profit Shards table",
            desc = "Ranked by profit per fuse multiplied by units sold per hour - "
                    + "a margin only counts if the market absorbs it. A 400k "
                    + "margin on a shard trading twice an hour loses to a 40k "
                    + "margin on one trading five hundred times.")
    @ConfigEditorBoolean
    @SearchTag("profit shards table money volume list")
    public boolean coinsShow = false;

    @Expose
    @ConfigOption(name = "Profit Shards rows", desc = "Fusions listed, and graph lines.")
    @ConfigEditorSlider(minValue = 3, maxValue = 20, minStep = 1)
    @SearchTag("rows count profit shards")
    public int coinsRows = 5;

    @Expose
    @ConfigOption(name = "XP per fuse table",
            desc = "Ranked by XP per fuse, ties broken by XP per coin spent.")
    @ConfigEditorBoolean
    @SearchTag("xp experience hunting table list")
    public boolean xpShow = false;

    @Expose
    @ConfigOption(name = "XP rows", desc = "Fusions listed, and graph lines.")
    @ConfigEditorSlider(minValue = 3, maxValue = 20, minStep = 1)
    @SearchTag("rows count xp")
    public int xpRows = 5;

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
    @SearchTag("fill time minutes wait order column")
    public boolean showFillTimes = true;

    @Expose
    @ConfigOption(name = "Show bottleneck",
            desc = "Which flow limits throughput: an input, or output absorption.")
    @ConfigEditorBoolean
    @SearchTag("bottleneck limit throughput column")
    public boolean showBottleneck = true;

    @Expose
    @ConfigOption(name = "Recommended: multi-step routes",
            desc = "Rows show the cheapest full fusion chain to the output "
                    + "instead of one direct fusion - \"Etherdrake (5 steps)\". "
                    + "Click a row to see the chain.")
    @ConfigEditorBoolean
    @SearchTag("multi step recursive chain route recommended")
    public boolean recMultiStep = false;

    @Expose
    @ConfigOption(name = "Profit Shards: multi-step routes",
            desc = "Rows show the cheapest full fusion chain to the output "
                    + "instead of one direct fusion - \"Etherdrake (5 steps)\". "
                    + "Click a row to see the chain.")
    @ConfigEditorBoolean
    @SearchTag("multi step recursive chain route profit shards")
    public boolean coinsMultiStep = false;

    @Expose
    @ConfigOption(name = "XP per fuse: multi-step routes",
            desc = "Rows show the cheapest full fusion chain to the output "
                    + "instead of one direct fusion - \"Etherdrake (5 steps)\". "
                    + "Click a row to see the chain.")
    @ConfigEditorBoolean
    @SearchTag("multi step recursive chain route xp")
    public boolean xpMultiStep = false;
}
