package dev.squidutils.config;

import com.google.gson.annotations.Expose;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorBoolean;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorSlider;
import io.github.notenoughupdates.moulconfig.annotations.ConfigOption;
import io.github.notenoughupdates.moulconfig.annotations.SearchTag;

/** Master switches and shared display, under Shard Fusion in the sidebar. */
public class FusionGeneralCategory {

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
    @ConfigOption(name = "Read wisdom automatically",
            desc = "Pick up Hunting Wisdom from the SkyBlock stats menu whenever "
                    + "you open it. Turn off to set it by hand below.")
    @ConfigEditorBoolean
    @SearchTag("wisdom auto detect automatic stats read player")
    public boolean autoDetectWisdom = true;

    @Expose
    @ConfigOption(name = "Hunting Wisdom",
            desc = "Fusion XP scales as base x (1 + wisdom/100). Filled in "
                    + "automatically from the stats menu unless that is off.")
    @ConfigEditorSlider(minValue = 0, maxValue = 400, minStep = 0.5f)
    @SearchTag("wisdom hunting xp experience bonus stat multiplier")
    public float huntingWisdom = 41.5f;
}
