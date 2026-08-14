package dev.squidutils.config;

import com.google.gson.annotations.Expose;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorBoolean;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorButton;
import io.github.notenoughupdates.moulconfig.annotations.ConfigOption;
import io.github.notenoughupdates.moulconfig.annotations.SearchTag;

/**
 * Session totals for a fusion run, under Shard Fusion in the sidebar.
 *
 * <p>The runnable ids for the two buttons below live on {@link FusionCategory}
 * (same package, so no import needed) and are dispatched by
 * {@link SquidUtilsConfig#executeRunnable}.
 */
public class FusionTrackerCategory {

    @Expose
    @ConfigOption(name = "Show tracker", desc = "Draw the session tracker panel.")
    @ConfigEditorBoolean
    @SearchTag("tracker session totals show")
    public boolean trackerShow = false;

    @Expose
    @ConfigOption(name = "Pause / resume", desc = "Stop counting without losing totals.")
    @ConfigEditorButton(runnableId = FusionCategory.RUN_PAUSE, buttonText = "Pause")
    @SearchTag("tracker pause resume stop")
    public boolean trackerPauseButton = false;

    @Expose
    @ConfigOption(name = "Reset", desc = "Clear all session totals.")
    @ConfigEditorButton(runnableId = FusionCategory.RUN_RESET, buttonText = "Reset")
    @SearchTag("tracker reset clear zero")
    public boolean trackerResetButton = false;

    @Expose
    @ConfigOption(name = "Show coins", desc = "Spent, earned and net profit.")
    @ConfigEditorBoolean
    @SearchTag("tracker coins spent earned profit")
    public boolean trackerCoins = true;

    @Expose
    @ConfigOption(name = "Show XP", desc = "Hunting XP gained, derived from fusions.")
    @ConfigEditorBoolean
    @SearchTag("tracker xp experience hunting")
    public boolean trackerXp = true;

    @Expose
    @ConfigOption(name = "Show shards", desc = "Fusions performed and shards traded.")
    @ConfigEditorBoolean
    @SearchTag("tracker shards fused bought sold")
    public boolean trackerShards = true;
}
