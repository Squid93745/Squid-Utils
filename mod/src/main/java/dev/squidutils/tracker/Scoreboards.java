package dev.squidutils.tracker;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Reads whatever the game's own SIDEBAR scoreboard currently shows, as plain
 * color-stripped text lines in visual top-to-bottom order - the same data
 * source every event tracker in {@code dev.squidutils.tracker} reads from,
 * since Hypixel's contest/event state (Miria's Contest chief among them) is
 * only ever exposed there, not through chat or any API.
 *
 * <p>{@link #displayText} took two wrong guesses before landing here, both
 * worth recording since the API is genuinely confusing on this point.
 * First it trusted {@link PlayerScoreEntry#display()} alone (the modern
 * per-entry protocol) - a live capture showed every line coming back empty,
 * meaning Hypixel never populates it here. Second it fell back to {@code
 * scoreboard.getPlayerTeam(entry.owner())} plus {@code
 * team.getFormattedName(name)} - a further live capture showed every entry
 * resolving to a null team even though the real screen plainly had text, so
 * that guess was wrong too. The actual answer, found by disassembling
 * vanilla's own {@code Gui.lambda$displayScoreboardSidebar$1} rather than
 * guessing a third time: {@link Scoreboard} has <em>two</em> similarly named
 * methods that do different things - {@code getPlayerTeam(String)} looks up
 * a team by a real player's name, while {@code getPlayersTeam(String)}
 * (plural "Players") looks up whichever team a scoreboard entry's own raw
 * name string happens to be registered under, real player or not - and the
 * name itself is reconstructed via the static {@code
 * PlayerTeam.formatNameForTeam(Team, Component)}, not an instance method,
 * which is what vanilla actually calls. {@link #displayText} now mirrors
 * that exactly rather than an assumption about how the classic prefix/suffix
 * trick "should" work.
 *
 * <p>{@link #SCORE_DISPLAY_ORDER} needed the same treatment: vanilla's own
 * {@code Gui.SCORE_DISPLAY_ORDER} sorts by score descending same as this
 * class already did, but breaks ties between equal scores by the entry's
 * own raw owner string, case-insensitively - a tiebreaker this class was
 * missing. Score ties are real: a real report of Miria's Contest's own tier
 * lines landing right after its header for the timer (found regardless of
 * order) but not for the tier text (found only by looking at the next few
 * entries in this class's own sort) pointed straight at two entries sharing
 * a score value sorting differently here than on the real screen.
 *
 * <p>{@link #strip} needed one more fix even after that: it stripped colour
 * codes but never trimmed whitespace, while every tier/threshold pattern in
 * {@link MiriaContest} matches a whole line anchored {@code ^...$}. The
 * timer line kept working throughout every prior fix (found by scanning all
 * lines regardless of position) while the tier lines stayed broken - the
 * timer's own header has no team-driven prefix to pad it, but the
 * rarity-coloured team behind each tier line plausibly does, for the same
 * visual-alignment reason Hypixel pads plenty of its other scoreboard rows.
 */
public final class Scoreboards {

    private Scoreboards() {}

    private static final Comparator<PlayerScoreEntry> SCORE_DISPLAY_ORDER =
            Comparator.comparingInt(PlayerScoreEntry::value).reversed()
                    .thenComparing(PlayerScoreEntry::owner, String.CASE_INSENSITIVE_ORDER);

    public static List<String> sidebarLines() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.level == null) return List.of();

        var scoreboard = mc.level.getScoreboard();
        var objective = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR);
        if (objective == null) return List.of();

        var entries = new ArrayList<>(scoreboard.listPlayerScores(objective));
        entries.sort(SCORE_DISPLAY_ORDER);

        List<String> lines = new ArrayList<>(entries.size());
        for (PlayerScoreEntry e : entries) {
            lines.add(strip(displayText(scoreboard, e).getString()));
        }
        return lines;
    }

    /** The real rendered text for one scoreboard entry - see the class doc
     *  for why this is not just {@link PlayerScoreEntry#display()} alone. */
    private static Component displayText(Scoreboard scoreboard, PlayerScoreEntry e) {
        if (e.display() != null) return e.display();
        PlayerTeam team = scoreboard.getPlayersTeam(e.owner());
        return PlayerTeam.formatNameForTeam(team, e.ownerName());
    }

    /** As {@link #sidebarLines}, but one diagnostic string per entry instead
     *  of just the resolved text - every intermediate value {@link
     *  #displayText} depends on, so a wrong guess about which one is
     *  actually empty does not cost another round trip to find out. Wired
     *  to {@code /squid debug scoreboard}. */
    public static List<String> debugLines() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.level == null) return List.of();

        var scoreboard = mc.level.getScoreboard();
        var objective = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR);
        if (objective == null) return List.of("(no objective displayed in the sidebar slot)");

        var entries = new ArrayList<>(scoreboard.listPlayerScores(objective));
        entries.sort(SCORE_DISPLAY_ORDER);

        List<String> out = new ArrayList<>();
        out.add("objective name=\"" + objective.getName() + "\" displayName=\"" + objective.getDisplayName().getString() + "\"");
        for (PlayerScoreEntry e : entries) {
            PlayerTeam team = scoreboard.getPlayersTeam(e.owner());
            String teamInfo = team == null ? "null" : "name=\"" + team.getName()
                    + "\" prefix=\"" + team.getPlayerPrefix().getString()
                    + "\" suffix=\"" + team.getPlayerSuffix().getString() + "\"";
            out.add(String.format(
                    "value=%d owner=\"%s\" display=%s ownerName=\"%s\" team=[%s] resolved=\"%s\"",
                    e.value(), e.owner(),
                    e.display() == null ? "null" : "\"" + e.display().getString() + "\"",
                    e.ownerName().getString(), teamInfo, strip(displayText(scoreboard, e).getString())));
        }
        return out;
    }

    /** The sidebar's own title line (e.g. "SKYBLOCK"), or null if no
     *  objective is currently displayed there at all. */
    public static String sidebarTitle() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.level == null) return null;
        var objective = mc.level.getScoreboard().getDisplayObjective(DisplaySlot.SIDEBAR);
        return objective == null ? null : strip(objective.getDisplayName().getString());
    }

    /** Strips legacy colour/style codes and trims surrounding whitespace -
     *  team prefixes/suffixes routinely pad with a literal space for visual
     *  alignment (Hypixel does this for exactly the rarity-coloured team
     *  each Miria's Contest tier line uses), and every consumer in {@link
     *  MiriaContest} matches these lines with a fully-anchored {@code
     *  ^...$} pattern - one stray leading or trailing space is enough to
     *  fail the whole match while looking identical on screen. {@link
     *  String#strip()} over {@link String#trim()} since a text component
     *  reconstructed this way is not guaranteed to only ever use plain
     *  ASCII spaces for that padding. */
    private static String strip(String s) {
        return s.replaceAll("§.", "").strip();
    }
}
