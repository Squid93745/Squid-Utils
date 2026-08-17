package dev.squidutils.config;

import com.google.gson.annotations.Expose;
import io.github.notenoughupdates.moulconfig.annotations.Accordion;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorBoolean;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorSlider;
import io.github.notenoughupdates.moulconfig.annotations.ConfigOption;
import io.github.notenoughupdates.moulconfig.annotations.SearchTag;

/**
 * Plots attached to Recommended and XP per fuse, under Shard Fusion in the
 * sidebar - not the four Profit Shards tables, which have no graphs by
 * design; see {@link FusionTablesCategory.ProfitVariant}.
 *
 * <p>One accordion per table rather than six flat checkboxes in a row: each
 * table's three metrics (profit / demand / XP per coin) now live together
 * instead of being told apart only by a name prefix.
 *
 * <p>{@code @Accordion} fields are a plain nested object, not the id-based
 * {@code @ConfigEditorAccordion}/{@code @ConfigAccordionId} pair - so unlike
 * those, there is no "must directly follow its header" ordering trap. Do not
 * mix the two styles in one class.
 */
public class FusionGraphsCategory {

    @Expose
    @ConfigOption(name = "Graph window", desc = "Minutes of history the graphs cover.")
    @ConfigEditorSlider(minValue = 5, maxValue = 240, minStep = 5)
    @SearchTag("graph window history minutes duration")
    public int graphWindow = 60;

    @Expose
    @ConfigOption(name = "Recommended", desc = "Graphs for the Recommended table.")
    @Accordion
    public RecommendedGraphs recommended = new RecommendedGraphs();

    @Expose
    @ConfigOption(name = "XP table", desc = "Graphs for the XP per fuse table.")
    @Accordion
    public XpTableGraphs xpTable = new XpTableGraphs();

    public static class RecommendedGraphs {
        @Expose
        @ConfigOption(name = "Profit graph", desc = "Profit per fuse over time.")
        @ConfigEditorBoolean
        @SearchTag("graph profit recommended")
        public boolean profitGraph = false;

        @Expose
        @ConfigOption(name = "Demand graph", desc = "Units sold per hour.")
        @ConfigEditorBoolean
        @SearchTag("graph demand recommended")
        public boolean demandGraph = false;

        @Expose
        @ConfigOption(name = "XP per coin graph", desc = "XP per 1,000 coins.")
        @ConfigEditorBoolean
        @SearchTag("graph xp coin recommended")
        public boolean xpGraph = false;
    }

    public static class XpTableGraphs {
        @Expose
        @ConfigOption(name = "Profit graph", desc = "Profit per fuse over time.")
        @ConfigEditorBoolean
        @SearchTag("graph profit xp table")
        public boolean profitGraph = false;

        @Expose
        @ConfigOption(name = "Demand graph", desc = "Units sold per hour.")
        @ConfigEditorBoolean
        @SearchTag("graph demand xp table")
        public boolean demandGraph = false;

        @Expose
        @ConfigOption(name = "XP per coin graph", desc = "XP per 1,000 coins.")
        @ConfigEditorBoolean
        @SearchTag("graph xp coin efficiency")
        public boolean xpGraph = false;
    }
}
