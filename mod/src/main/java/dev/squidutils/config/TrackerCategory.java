package dev.squidutils.config;

import com.google.gson.annotations.Expose;
import io.github.notenoughupdates.moulconfig.annotations.Category;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorBoolean;
import io.github.notenoughupdates.moulconfig.annotations.ConfigOption;
import io.github.notenoughupdates.moulconfig.annotations.SearchTag;

/**
 * Live HUD trackers for in-game events - a countdown, a score, a tier, or
 * whatever else a given event exposes - plus custom timers the player sets
 * themselves. "Tracker" itself is just a master switch, the same shape
 * {@link FishingCategory}/{@link BazaarCategory} use, with one sub-page per
 * specific tracker beneath it: {@link MiriaContestCategory} first, {@link
 * CustomTimersCategory} for anything a fixed built-in tracker cannot cover.
 *
 * <p>Deliberately not the same thing as {@code FusionTrackerCategory} ({@code
 * cfg.fusion.tracker}), the fusion session profit/loss panel - that stays
 * fusion-specific; this is the general "any in-game event" system.
 */
public class TrackerCategory {

    @Expose
    @ConfigOption(name = "Enable trackers", desc = "Master switch for this section.")
    @ConfigEditorBoolean
    @SearchTag("tracker enable master switch event timer")
    public boolean enabled = true;

    @Expose
    @Category(name = "Miria's Contest",
            desc = "Countdown, current tier and score for the active contest, read off the scoreboard")
    public MiriaContestCategory miriaContest = new MiriaContestCategory();

    @Expose
    @Category(name = "Custom Timers",
            desc = "Timers you set yourself, with splash/chat/sound alerts when they fire")
    public CustomTimersCategory customTimers = new CustomTimersCategory();
}
