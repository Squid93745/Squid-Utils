package dev.squidutils.config;

import com.google.gson.annotations.Expose;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorBoolean;
import io.github.notenoughupdates.moulconfig.annotations.ConfigOption;
import io.github.notenoughupdates.moulconfig.annotations.SearchTag;

/** Extra lines added to shard tooltips, under Shard Fusion in the sidebar. */
public class FusionTooltipsCategory {

    @Expose
    @ConfigOption(name = "Show cheapest fusion",
            desc = "Add a line to a shard's tooltip showing the cheapest way to "
                    + "fuse it, next to the bazaar prices Hypixel already lists. "
                    + "Not yet implemented.")
    @ConfigEditorBoolean
    @SearchTag("tooltip cheapest craft fuse hover shard")
    public boolean tooltipCheapest = false;

    @Expose
    @ConfigOption(name = "Include multi-step routes",
            desc = "Also consider fusing the inputs when that comes out cheaper "
                    + "than buying them. Not yet implemented.")
    @ConfigEditorBoolean
    @SearchTag("tooltip multi step recursive chain cheaper")
    public boolean tooltipMultiStep = false;
}
