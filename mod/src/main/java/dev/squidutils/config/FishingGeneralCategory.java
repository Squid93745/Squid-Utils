package dev.squidutils.config;

import com.google.gson.annotations.Expose;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorBoolean;
import io.github.notenoughupdates.moulconfig.annotations.ConfigOption;
import io.github.notenoughupdates.moulconfig.annotations.SearchTag;

/** Master switch, under Fishing in the sidebar. */
public class FishingGeneralCategory {

    @Expose
    @ConfigOption(name = "Enable fishing features", desc = "Master switch for this section.")
    @ConfigEditorBoolean
    @SearchTag("fishing enable qol sea creature rod")
    public boolean enabled = false;
}
