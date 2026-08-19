package dev.squidutils.tracker;

import com.google.gson.Gson;
import dev.squidutils.SquidUtils;
import dev.squidutils.config.MiriaContestCategory;
import dev.squidutils.hud.Sounds;
import dev.squidutils.hud.Splash;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Miria's Contest's own countdown, current tier and score, and how much more
 * score the next tier (or, past the top tier, how far over it) needs.
 *
 * <p>The countdown is calculated from {@link SkyBlockTime} - Miria's Contest
 * runs every 20 minutes with no gaps between contests, and one SkyBlock day
 * is also exactly 20 real minutes, so it is fully determined by the
 * real-world clock alone, computable from anywhere. But a pure calculation
 * drifted from the real, server-authoritative countdown in a live report -
 * about 27 seconds off, most likely years of accumulated clock skew between
 * this client's own system clock and Hypixel's, or a small imprecision in
 * the epoch itself; either way not worth chasing down to the millisecond.
 * Instead {@link #findLiveMillis} reads the exact live countdown the first
 * time each session the scoreboard happens to show it, and {@link
 * #calibrationOffsetMillis} remembers the gap between that and an
 * uncorrected calculation for that same instant - self-correcting once per
 * session rather than trusting one static formula forever, so the
 * predictive value (works from anywhere) and the accuracy (matches the real
 * thing whenever checkable) are not actually in tension. Deliberately not
 * "recalibrate on every live update" - an earlier version did that and a
 * real report ("two seconds in 1.5 seconds") turned out to be Hypixel's own
 * whole-second-resolution countdown never giving a perfectly stable
 * rounding to compare against, so re-measuring every second just kept
 * re-discovering the same ~1s of noise and visibly snapping the displayed
 * countdown around by it. The skew itself is a real system-clock offset
 * against Hypixel's server clock, which does not meaningfully change within
 * one running session, so one sample is enough - see {@link #tick}'s own
 * comment on the calibration step for the full account. That correction is
 * applied to the real-time clock reading {@link
 * SkyBlockTime}'s own math runs on, not added to its output afterward - an
 * earlier version did the latter, which shifts the displayed number but not
 * when the day boundary itself (and so the modulo wraparound inside {@link
 * SkyBlockTime#intoCurrentDay(long)}) actually falls, and a real report
 * caught exactly that: a fresh contest displaying as already a partial
 * cycle short (19m30s, not a full 20m0s) the instant it started, since the
 * boundary was still landing at the uncorrected moment even though the
 * number after it was being corrected. Persisted to {@code
 * miria-contest-calibration.json} (the same {@code new Gson()} round-trip
 * {@code AttributeDetector}/{@code CustomTimers} already use) so a
 * correction learned once does not need re-learning every launch, and every
 * genuine change is logged (real timestamp, calculated value, live value,
 * derived offset) - rounded to the nearest second first, since the
 * scoreboard's own text never carries more precision than that and logging
 * every sub-second wobble in the calculated side alone would just be noise.
 * That log line is what actually answers whether 27 seconds was a one-off
 * or a stable, reproducible gap: readable straight out of {@code
 * logs/latest.log} without needing to launch anything to check.
 *
 * <p>Tier and score are pure player-state Hypixel decides server-side, with
 * no formula to compute them from - always read straight off the SIDEBAR
 * scoreboard (see {@link Scoreboards}) whenever visible, blank otherwise.
 * Confirmed from two live captures, three lines together:
 * <pre>
 *   Miria's Contest 1m33s          Miria's Contest 0m45s
 *   EPIC with 3.8k                 MYTHIC with 10.6k
 *   Legendary requires +1.2k       +649 over Mythic
 * </pre>
 * The second capture is what {@link #NEXT_TIER_LINE} alone missed: once you
 * are already at the top bracket the wiki's own rarity list shows, the
 * third line flips from "how much more until the next tier" to "how far
 * past this one you already are" - {@link #OVER_TOP_TIER_LINE} covers that
 * second shape. {@link #findTierBlock} no longer assumes the tier/threshold
 * lines sit at a fixed offset below the header either, after the same
 * report showed a real reading landing on "Tier unknown" despite standing
 * at Torrhus Canyon with the lines plainly visible - it now checks each of
 * the next few lines against every known pattern instead of trusting one
 * exact position, so a stray blank line or reordering degrades to a partial
 * reading rather than an empty one.
 */
public final class MiriaContest {

    private MiriaContest() {}

    private static final Pattern LIVE_TIME_LINE =
            Pattern.compile("^Miria's Contest\\s+(?:(\\d+)m)?(\\d+)s\\s*$");
    private static final Pattern TIER_SCORE_LINE = Pattern.compile("^(\\w+)\\s+with\\s+(.+)$");
    private static final Pattern NEXT_TIER_LINE = Pattern.compile("^(\\w+)\\s+requires\\s+\\+(.+)$");
    private static final Pattern OVER_TOP_TIER_LINE = Pattern.compile("^\\+(.+)\\s+over\\s+(\\w+)$");
    /** "Spring 19th" - the sidebar's own date line, confirmed from a live
     *  capture and always visible (unlike the contest's own lines, which
     *  need Torrhus Canyon specifically) - see {@link #validateCalendarDate}. */
    private static final Pattern DATE_LINE =
            Pattern.compile("^((?:Early |Late )?(?:Spring|Summer|Autumn|Winter))\\s+(\\d+)(?:st|nd|rd|th)$");
    /** How many lines below the header to still consider part of its own
     *  block - generous on purpose, see the class doc on why this stopped
     *  assuming an exact offset. */
    private static final int BLOCK_LOOKAHEAD = 4;

    /** One reading of the contest: {@code msUntilReset} is always known
     *  (calculated, calibrated against the scoreboard whenever possible -
     *  see the class doc); {@code tier}/{@code score} are null only if
     *  neither this reading nor any earlier one this contest ever found the
     *  scoreboard's own lines - once found, {@link #tick} carries them
     *  forward through readings that do not (see its own comment), so they
     *  do not go blank just because the player walked away from Torrhus
     *  Canyon. {@code thresholdLine} is the already-formatted third line
     *  ("Legendary requires +1.2k" or "+649 over Mythic"), and {@code
     *  thresholdTier} just the tier name in it, for colouring - both null on
     *  the same terms as {@code tier}/{@code score}. */
    public record State(long msUntilReset, String tier, String score, String thresholdLine, String thresholdTier) {}

    private static State current;
    /** The SkyBlock-day index (real millis since the epoch, divided into
     *  whole days) as of the last tick - comparing this, not the scoreboard,
     *  is what makes "a new contest just began" detectable from anywhere,
     *  not just while standing at Torrhus Canyon watching it happen. -1
     *  means "not yet observed", so the very first tick (or the first after
     *  re-enabling) never alerts on a day that had simply already begun
     *  before this tracker was watching. */
    private static long lastDayIndex = -1;
    /** Logged once per session, the first time the sidebar's own date line
     *  is visible - see {@link #validateCalendarDate}. The month/day
     *  relationship to elapsed time does not meaningfully change moment to
     *  moment, so there is nothing more a second check would reveal. */
    private static boolean dateValidated;
    /** Set once the first live reading this session has been used to
     *  (re)calibrate - see {@link #tick}'s own calibration step for why
     *  calibration only ever happens once per session rather than on every
     *  live update. Reset alongside {@link #lastDayIndex} whenever the
     *  tracker is disabled, so re-enabling calibrates fresh rather than
     *  trusting a value learned an arbitrary amount of time ago. */
    private static boolean calibratedThisSession;

    private static final Path CALIBRATION_PATH = resolveCalibrationPath();
    /** The gap between the last live-read countdown and what {@link
     *  SkyBlockTime} calculated for that same instant, rounded to the
     *  nearest second - see the class doc. 0 until the first live reading
     *  ever arrives (or on a fresh install), i.e. trust the raw calculation
     *  until there is real data to correct it with.
     *
     * <p>Loaded from disk in the {@code static} block below rather than
     * this field's own initializer - {@link #loadCalibration} reads {@link
     * #CALIBRATION_PATH}, which needs to already be assigned by the time it
     * runs, and field initializers only run in declaration order within one
     * single pass; a {@code static {}} block placed after every field
     * declaration is guaranteed to run once all of them already have, the
     * same ordering {@code AttributeDetector} relies on for its own load(). */
    private static long calibrationOffsetMillis;

    static {
        calibrationOffsetMillis = loadCalibration();
    }

    public static State current() {
        return current;
    }

    public static void tick() {
        var cfg = SquidUtils.config();
        if (cfg == null || !cfg.tracker.enabled || !cfg.tracker.miriaContest.enabled) {
            current = null;
            lastDayIndex = -1;
            calibratedThisSession = false;
            return;
        }

        long now = System.currentTimeMillis();
        List<String> lines = Scoreboards.sidebarLines();
        validateCalendarDate(lines);

        // Measured against the raw, uncorrected clock - this is what
        // calibrationOffsetMillis itself actually means (the live reading's
        // own gap from an uncorrected calculation), so measuring it against
        // an already-corrected value would be comparing the correction
        // against itself.
        Long liveMillis = findLiveMillis(lines);
        long rawCalculatedMillis = SkyBlockTime.untilNextDay(now);
        // Calibrates once per session, on the first live reading available,
        // and never again after - not "once per second, debounced". An
        // earlier version recalibrated on every live-text update (roughly
        // once a second); a real capture showed the derived offset itself
        // bouncing between adjacent whole seconds on nearly every single one
        // of those updates (-27000, -28000, -29000, -28000, -29000, ...),
        // because Hypixel's own countdown only ever has whole-second
        // resolution, so comparing against it always carries up to ~1s of
        // rounding noise - and the true skew apparently sits close enough to
        // a rounding boundary that consecutive samples kept landing on
        // different sides of it. Recalibrating on every one of those samples
        // meant every flip instantly shifted the displayed countdown by up
        // to two seconds, a real report ("two seconds in 1.5 seconds"), not
        // a hypothetical one. The skew itself is a real system-clock offset
        // against Hypixel's own server clock, which does not meaningfully
        // change tick to tick, or even second to second, within one running
        // client - so re-deriving it from every fresh sample was chasing
        // noise around a value that was not actually moving. One sample is
        // still enough: the same one-off inaccuracy this class already
        // accepts (a single reading might land up to ~1s off the true
        // rounding) is far less noticeable once than it was continuously.
        if (liveMillis != null && !calibratedThisSession) {
            long roundedOffset = Math.round((liveMillis - rawCalculatedMillis) / 1000.0) * 1000;
            if (roundedOffset != calibrationOffsetMillis) {
                SquidUtils.LOG.info(
                        "[squidutils] miria contest calibration: real={} calculated={}ms live={}ms "
                                + "offset={}ms (was {}ms)",
                        Instant.now(), rawCalculatedMillis, liveMillis, roundedOffset, calibrationOffsetMillis);
                calibrationOffsetMillis = roundedOffset;
                saveCalibration();
            }
            calibratedThisSession = true;
        }

        // The correction is applied to the clock reading itself, not added
        // to the countdown afterward - it has to shift when the day
        // boundary (and so the modulo wraparound inside untilNextDay/
        // dayIndex) actually falls, not just the displayed number, or a
        // fresh contest starts already a partial cycle short the instant it
        // begins - a real, reported bug, not a hypothetical one. See
        // SkyBlockTime.intoCurrentDay(long)'s own doc for the full reasoning.
        long adjustedNow = now - calibrationOffsetMillis;
        long dayIndex = (adjustedNow - SkyBlockTime.EPOCH_START_MILLIS) / SkyBlockTime.DAY_MILLIS;
        long msUntilReset = SkyBlockTime.untilNextDay(adjustedNow);

        // Tier/score and the threshold line each carry forward from the
        // last reading that actually found them rather than going blank the
        // instant the scoreboard's own lines scroll out of view (walking
        // away from Torrhus Canyon does not erase the player's real score,
        // so the panel should not act like it did either) - only a fresh
        // contest starting actually invalidates them, since that is the one
        // moment the real score genuinely resets to none. The two pairs
        // carry independently because {@link #findTierBlock} already finds
        // them independently - one can be visible in a given reading without
        // the other.
        boolean freshContest = lastDayIndex != -1 && dayIndex != lastDayIndex;
        State carry = freshContest ? null : current;
        String[] block = findTierBlock(lines);
        String tier = block[0] != null ? block[0] : (carry != null ? carry.tier() : null);
        String score = block[0] != null ? block[1] : (carry != null ? carry.score() : null);
        String thresholdLine = block[2] != null ? block[2] : (carry != null ? carry.thresholdLine() : null);
        String thresholdTier = block[2] != null ? block[3] : (carry != null ? carry.thresholdTier() : null);
        State next = new State(msUntilReset, tier, score, thresholdLine, thresholdTier);

        handleTransition(cfg.tracker.miriaContest, current, next, lastDayIndex, dayIndex);
        current = next;
        lastDayIndex = dayIndex;
    }

    /**
     * Cross-checks {@link SkyBlockTime}'s own calculated date against the
     * sidebar's own date line ("Spring 19th") the first time it is visible
     * each session - the same idea as SkyHanni reading the real Calendar
     * GUI for its Jacob's Contest tracking rather than trusting a bare
     * calculation alone, applied here at the smaller scale this class
     * actually needs: a day-level sanity check on the epoch itself, logged
     * once so it is checkable in {@code logs/latest.log} without needing to
     * be watching for it live. Independent of {@link #calibrationOffsetMillis}
     * above - that one is Miria's Contest's own sub-day correction; this is
     * whether the calendar the whole class is built on is aligned at all.
     */
    private static void validateCalendarDate(List<String> lines) {
        if (dateValidated) return;
        for (String line : lines) {
            Matcher m = DATE_LINE.matcher(line);
            if (!m.matches()) continue;

            String scoreboardMonth = m.group(1);
            int scoreboardDay = Integer.parseInt(m.group(2));
            var calculated = SkyBlockTime.now();
            String calculatedMonth = SkyBlockTime.monthName(calculated.month());
            boolean matches = calculatedMonth.equals(scoreboardMonth) && calculated.day() == scoreboardDay;

            SquidUtils.LOG.info(
                    "[squidutils] skyblock calendar check: scoreboard=\"{} {}\" calculated=\"{} {}\" match={}",
                    scoreboardMonth, scoreboardDay, calculatedMonth, calculated.day(), matches);
            dateValidated = true;
            return;
        }
    }

    /** The exact live countdown off the header line itself ("Miria's
     *  Contest 0m45s"), in milliseconds - null if that line is not
     *  currently visible. */
    private static Long findLiveMillis(List<String> lines) {
        for (String line : lines) {
            Matcher m = LIVE_TIME_LINE.matcher(line);
            if (!m.matches()) continue;
            long minutes = m.group(1) != null ? Long.parseLong(m.group(1)) : 0;
            long seconds = Long.parseLong(m.group(2));
            return (minutes * 60 + seconds) * 1000;
        }
        return null;
    }

    /** Logged at most once per session, the first time the header is found
     *  but neither tier nor threshold matched anything in its lookahead
     *  window - the exact lines examined, quoted so stray whitespace or an
     *  unanticipated character shows up directly instead of needing another
     *  guess. If {@link Scoreboards#strip} trimming whitespace does not turn
     *  out to be the whole story, this is what proves it next time rather
     *  than another round of speculation. */
    private static boolean blockMissLogged;

    /** @return {tier, score, thresholdLine, thresholdTier}, each possibly null. */
    private static String[] findTierBlock(List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            if (!lines.get(i).startsWith("Miria's Contest")) continue;

            String tier = null, score = null, thresholdLine = null, thresholdTier = null;
            int end = Math.min(lines.size(), i + 1 + BLOCK_LOOKAHEAD);
            for (int j = i + 1; j < end; j++) {
                String line = lines.get(j);
                if (tier == null) {
                    Matcher tm = TIER_SCORE_LINE.matcher(line);
                    if (tm.matches()) {
                        tier = tm.group(1);
                        score = tm.group(2).trim();
                        continue;
                    }
                }
                if (thresholdLine == null) {
                    Matcher nm = NEXT_TIER_LINE.matcher(line);
                    if (nm.matches()) {
                        thresholdTier = nm.group(1);
                        thresholdLine = line;
                        continue;
                    }
                    Matcher om = OVER_TOP_TIER_LINE.matcher(line);
                    if (om.matches()) {
                        thresholdTier = om.group(2);
                        thresholdLine = line;
                    }
                }
            }
            if (tier == null && thresholdLine == null && !blockMissLogged) {
                blockMissLogged = true;
                SquidUtils.LOG.info(
                        "[squidutils] miria contest: header found but no tier/threshold line matched in {}",
                        lines.subList(i, end).stream().map(l -> "\"" + l + "\"").toList());
            }
            return new String[]{tier, score, thresholdLine, thresholdTier};
        }
        return new String[4];
    }

    private static void handleTransition(MiriaContestCategory cfg, State prev, State next,
                                         long prevDayIndex, long dayIndex) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;

        if (prevDayIndex != -1 && dayIndex != prevDayIndex && cfg.notifyStart) {
            alert(mc, cfg, "Miria's Contest has started!");
        }
        // Only a genuine change within the same contest counts - tier simply
        // coming into view for the first time (walking up to Torrhus Canyon
        // mid-contest) is not a change, just newly-observed state that was
        // already true, and a fresh contest starting is excluded too: since
        // tier now carries forward across the scoreboard going out of view
        // (see tick()'s own comment), prev.tier() can still hold the
        // previous contest's own last tier the moment a new one begins,
        // which would otherwise misread as a same-tick "change".
        if (prev != null && prev.tier() != null && next.tier() != null && dayIndex == prevDayIndex
                && !next.tier().equalsIgnoreCase(prev.tier()) && cfg.notifyTierChange) {
            alert(mc, cfg, "Miria's Contest tier: " + next.tier());
        }
    }

    private static void alert(Minecraft mc, MiriaContestCategory cfg, String message) {
        if (cfg.chatEnabled && mc.player != null) {
            mc.player.sendSystemMessage(Component.literal("")
                    .append(Component.literal("§6§lCONTEST §7» "))
                    .append(Component.literal("§f" + message)));
        }
        if (cfg.sound.enabled) Sounds.play(cfg.sound.id, cfg.sound.pitch);
        if (cfg.splash.enabled) Splash.show(message, cfg.splash.scale, cfg.splash.seconds);
    }

    // ------------------------------------------------------------------
    private static Path resolveCalibrationPath() {
        try {
            Path dir = FabricLoader.getInstance().getConfigDir().resolve(SquidUtils.MOD_ID);
            Files.createDirectories(dir);
            return dir.resolve("miria-contest-calibration.json");
        } catch (Exception e) {
            SquidUtils.LOG.warn("[squidutils] could not resolve Miria's Contest calibration store path", e);
            return null;
        }
    }

    private static long loadCalibration() {
        if (CALIBRATION_PATH == null || !Files.exists(CALIBRATION_PATH)) return 0;
        try (var reader = Files.newBufferedReader(CALIBRATION_PATH, StandardCharsets.UTF_8)) {
            Long stored = new Gson().fromJson(reader, Long.class);
            return stored != null ? stored : 0;
        } catch (Exception e) {
            SquidUtils.LOG.warn("[squidutils] could not read Miria's Contest calibration", e);
            return 0;
        }
    }

    private static void saveCalibration() {
        if (CALIBRATION_PATH == null) return;
        try {
            Files.writeString(CALIBRATION_PATH, new Gson().toJson(calibrationOffsetMillis), StandardCharsets.UTF_8);
        } catch (Exception e) {
            SquidUtils.LOG.warn("[squidutils] could not write Miria's Contest calibration", e);
        }
    }
}
