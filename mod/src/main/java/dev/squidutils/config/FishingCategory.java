package dev.squidutils.config;

import com.google.gson.annotations.Expose;
import io.github.notenoughupdates.moulconfig.annotations.Category;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorBoolean;
import io.github.notenoughupdates.moulconfig.annotations.ConfigOption;
import io.github.notenoughupdates.moulconfig.annotations.SearchTag;

/**
 * Fishing settings. The master switch renders directly on "Fishing"'s own
 * page rather than a separate "General" sub-page - see the class doc on
 * {@link FusionCategory} for why a {@code @Category} field's own {@code
 * @ConfigOption} fields can sit alongside further sub-page fields like that.
 *
 * <p>One sub-page still appears indented beneath "Fishing" in the sidebar:
 * {@link FrozenBlazeCategory}.
 */
public class FishingCategory {

    @Expose
    @ConfigOption(name = "Enable fishing features", desc = "Master switch for this section.")
    @ConfigEditorBoolean
    @SearchTag("fishing enable qol sea creature rod")
    public boolean enabled = true;

    @Expose
    @Category(name = "Frozen Blaze", desc = "Warn before Frozen Blaze stops dealing damage")
    public FrozenBlazeCategory frozenBlaze = new FrozenBlazeCategory();
}
