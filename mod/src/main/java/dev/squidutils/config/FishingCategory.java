package dev.squidutils.config;

import com.google.gson.annotations.Expose;
import io.github.notenoughupdates.moulconfig.annotations.Category;

/**
 * Fishing settings - purely organisational, like {@link FusionCategory}. Two
 * sub-pages appear indented beneath "Fishing" in the sidebar: {@link
 * FishingGeneralCategory} and {@link FrozenBlazeCategory}.
 */
public class FishingCategory {

    @Expose
    @Category(name = "General", desc = "Master switch for fishing features")
    public FishingGeneralCategory general = new FishingGeneralCategory();

    @Expose
    @Category(name = "Frozen Blaze", desc = "Warn before Frozen Blaze stops dealing damage")
    public FrozenBlazeCategory frozenBlaze = new FrozenBlazeCategory();
}
