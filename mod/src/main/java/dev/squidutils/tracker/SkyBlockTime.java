package dev.squidutils.tracker;

/**
 * SkyBlock's own calendar, computed purely from elapsed real time since a
 * fixed epoch rather than read from anywhere in-game - the same mechanism
 * SkyHanni's own {@code SkyBlockTime.kt} uses (itself "originally in NEU,
 * copied and modified with permission" - the epoch and day length below are
 * a real, external game constant both projects independently confirm, not
 * anything specific to either one's own code). Deterministic: the whole
 * point is that a calendar-aligned event's own schedule can be computed
 * from anywhere, in advance, rather than only detected once already
 * underway - which is what makes {@link MiriaContest} able to show a
 * countdown to the next contest even when nowhere near Torrhus Canyon.
 *
 * <p>{@link MiriaContest} is the only real caller so far, and only needs the
 * one SkyBlock day boundary - it runs exactly one SkyBlock day per contest
 * back-to-back with no gap (confirmed directly: every 20 minutes, the whole
 * time, no gaps - and one SkyBlock day is also exactly 20 real minutes, so
 * contests tile the calendar with no room between them). The full
 * year/month/day breakdown below exists for a different reason: Hypixel's
 * own scoreboard already states today's SkyBlock date in plain text ("Spring
 * 19th") - an independent, in-game-authoritative check on whether this
 * class's own epoch is actually aligned right, the same role SkyHanni's own
 * {@code SkyBlockTime} plays for its Jacob's Contest tracking (it reads the
 * real Calendar GUI rather than trusting a bare calculation for exactly this
 * reason - the exact date is too easy to get from the game to not check
 * against). {@link MiriaContest#validateCalendarDate} is that check; a
 * genuinely gapped calendar event like Spooky Festival, which only runs 3
 * days a SkyBlock year, would be the next real user of {@link #now}.
 */
public final class SkyBlockTime {

    private SkyBlockTime() {}

    /** Year 1, Day 1 - real Unix millis. */
    public static final long EPOCH_START_MILLIS = 1_559_829_300_000L;
    /** 124 real hours per SkyBlock year, 12 months per year, 31 days per
     *  month - 124h / 12 / 31 comes out to exactly 20 real minutes. */
    public static final long DAY_MILLIS = (124L * 60 * 60 * 1000) / 12 / 31;
    public static final long MONTH_MILLIS = DAY_MILLIS * 31;
    public static final long YEAR_MILLIS = MONTH_MILLIS * 12;

    /** Milliseconds elapsed into the current SkyBlock day, right now. */
    public static long intoCurrentDay() {
        return intoCurrentDay(System.currentTimeMillis());
    }

    /** As {@link #intoCurrentDay()}, but against an explicit timestamp
     *  rather than the live clock - what {@link MiriaContest} actually
     *  calls, passing in a clock reading already corrected for its own
     *  measured skew against Hypixel's server time (see its own class doc).
     *  That correction has to happen to the <em>input</em> here, not as an
     *  addition to this method's own output - shifting the output alone
     *  moves the displayed number but not when the day boundary (and so the
     *  modulo wraparound below) actually falls, which is exactly what
     *  produced a real, reported bug: a fresh contest displaying as already
     *  a partial cycle short the moment it started, since the boundary
     *  itself was still landing at the uncorrected moment. */
    public static long intoCurrentDay(long realMillis) {
        long elapsed = realMillis - EPOCH_START_MILLIS;
        long mod = elapsed % DAY_MILLIS;
        return mod < 0 ? mod + DAY_MILLIS : mod;   // defensive only - always positive in practice, the epoch is years past
    }

    /** Milliseconds remaining until the next SkyBlock day boundary - Miria's
     *  Contest resetting to a new one, given the two line up exactly. */
    public static long untilNextDay() {
        return untilNextDay(System.currentTimeMillis());
    }

    /** As {@link #untilNextDay()}, against an explicit (typically
     *  skew-corrected) timestamp - see {@link #intoCurrentDay(long)}. */
    public static long untilNextDay(long realMillis) {
        return DAY_MILLIS - intoCurrentDay(realMillis);
    }

    /** {@code year} is 0-indexed (Year 1 in-game is {@code year == 0}, same
     *  as SkyHanni's own struct); {@code month}/{@code day} are 1-indexed,
     *  matching how the game itself numbers them. */
    public record Date(int year, int month, int day) {}

    public static Date now() {
        return at(System.currentTimeMillis());
    }

    public static Date at(long realMillis) {
        long elapsed = Math.max(0, realMillis - EPOCH_START_MILLIS);
        int year = (int) (elapsed / YEAR_MILLIS);
        long intoYear = elapsed % YEAR_MILLIS;
        int month = (int) (intoYear / MONTH_MILLIS) + 1;
        long intoMonth = intoYear % MONTH_MILLIS;
        int day = (int) (intoMonth / DAY_MILLIS) + 1;
        return new Date(year, month, day);
    }

    /** "Early Spring", "Spring", "Late Spring", "Early Summer", ... -
     *  matches Hypixel's own wording exactly (confirmed against the real
     *  scoreboard, not guessed): each season is three months, prefixed
     *  Early/(nothing)/Late for its own first/second/third month. */
    public static String monthName(int month) {
        String prefix = switch (((month - 1) % 3 + 3) % 3) {
            case 0 -> "Early ";
            case 2 -> "Late ";
            default -> "";
        };
        String season = switch (((month - 1) / 3 % 4 + 4) % 4) {
            case 0 -> "Spring";
            case 1 -> "Summer";
            case 2 -> "Autumn";
            default -> "Winter";
        };
        return prefix + season;
    }
}
