package dev.squidutils.config;

import com.google.gson.annotations.Expose;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorBoolean;
import io.github.notenoughupdates.moulconfig.annotations.ConfigOption;
import io.github.notenoughupdates.moulconfig.annotations.SearchTag;

/**
 * Session totals for a fusion run, under Shard Fusion in the sidebar.
 *
 * <p>Pause, reset and the Session/Total view toggle live on the panel itself
 * now (hover it while a menu is open), Feesh-style, rather than as buttons
 * here - see {@code FusionWidgets.tracker()}.
 */
public class FusionTrackerCategory {

    @Expose
    @ConfigOption(name = "Show tracker", desc = "Draw the session tracker panel.")
    @ConfigEditorBoolean
    @SearchTag("tracker session totals show")
    public boolean trackerShow = false;

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
