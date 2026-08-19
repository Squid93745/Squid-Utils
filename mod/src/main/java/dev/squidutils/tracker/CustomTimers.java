package dev.squidutils.tracker;

import com.google.gson.Gson;
import dev.squidutils.SquidUtils;
import dev.squidutils.config.CustomTimersCategory;
import dev.squidutils.hud.Sounds;
import dev.squidutils.hud.Splash;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Timers the player sets themselves - added from the {@code /squidtimer}
 * chat command or {@code TimerScreen}, shown on the custom timers panel, and
 * removed automatically (after alerting) once their time comes, unless it is
 * a repeating timer with firings left, in which case it reschedules instead
 * - see {@link #tick}. Persisted to {@code timers.json}, the same {@code new
 * Gson()} round-trip {@code AttributeDetector} already uses for {@code
 * attributes.json} - unlike an attribute level, a timer is explicitly meant
 * to survive a restart (a "next Kuudra key" reminder set before logging off
 * is pointless if it silently resets the moment the game closes).
 *
 * <p>No per-timer alert type override (chat/title/toast, one way or another
 * only) the way some other reminder mods offer alongside looping - {@code
 * CustomTimersCategory} already has one shared chat/sound/splash
 * configuration for every custom timer, so a second, per-timer copy of the
 * same choice would just be two ways to answer a question this mod already
 * only asks once.
 */
public final class CustomTimers {

    private CustomTimers() {}

    private static final List<CustomTimer> timers = new ArrayList<>();
    private static final Path STORE_PATH = resolveStorePath();

    static {
        load();
    }

    /** A defensive copy - callers (the HUD panel, the management screen)
     *  read this every frame and must never see it mutate mid-iteration. */
    public static List<CustomTimer> timers() {
        return List.copyOf(timers);
    }

    public static CustomTimer add(String name, long durationMillis) {
        return add(name, durationMillis, 0, 0);
    }

    /** As {@link #add(String, long)}, but repeating - {@code loopMillis} is
     *  the gap between firings (0 disables looping entirely, same as the
     *  other overload), {@code loopQuantity} the total number of times it
     *  should fire, or -1 for no limit. */
    public static CustomTimer add(String name, long durationMillis, long loopMillis, int loopQuantity) {
        CustomTimer t = new CustomTimer(UUID.randomUUID().toString(), name,
                System.currentTimeMillis() + durationMillis, loopMillis, loopQuantity);
        timers.add(t);
        save();
        return t;
    }

    public static boolean removeById(String id) {
        boolean removed = timers.removeIf(t -> t.id.equals(id));
        if (removed) save();
        return removed;
    }

    /** Removes the first timer whose name matches, case-insensitively - the
     *  {@code /squidtimer remove} command only has a typed name to go on,
     *  not a specific row the way the management screen's own buttons do. */
    public static boolean removeByName(String name) {
        for (int i = 0; i < timers.size(); i++) {
            if (timers.get(i).name.equalsIgnoreCase(name)) {
                timers.remove(i);
                save();
                return true;
            }
        }
        return false;
    }

    public static void clear() {
        if (timers.isEmpty()) return;
        timers.clear();
        save();
    }

    private static final java.util.regex.Pattern DURATION =
            java.util.regex.Pattern.compile("(?:(\\d+)h)?(?:(\\d+)m)?(?:(\\d+)s)?",
                    java.util.regex.Pattern.CASE_INSENSITIVE);

    /** "5m", "1h30m", "90s", "2h" - null if nothing recognisable was found.
     *  A bare number with no h/m/s suffix deliberately does not match: which
     *  unit it means is not obvious enough to guess at. */
    public static Long parseDuration(String s) {
        var m = DURATION.matcher(s.trim());
        if (!m.matches() || (m.group(1) == null && m.group(2) == null && m.group(3) == null)) return null;
        long h = m.group(1) != null ? Long.parseLong(m.group(1)) : 0;
        long min = m.group(2) != null ? Long.parseLong(m.group(2)) : 0;
        long sec = m.group(3) != null ? Long.parseLong(m.group(3)) : 0;
        return (h * 3600 + min * 60 + sec) * 1000;
    }

    /** "3", "10", "inf"/"infinite" (case-insensitive) - null if not
     *  recognisable. -1 represents no limit, matching how {@link
     *  CustomTimer#loopQuantity} stores it. */
    public static Integer parseLoopQuantity(String s) {
        String t = s.trim();
        if (t.equalsIgnoreCase("inf") || t.equalsIgnoreCase("infinite")) return -1;
        try {
            int n = Integer.parseInt(t);
            return n > 0 ? n : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** The mirror of {@link #parseDuration} - "1h30m", "5m", "45s" - for
     *  displaying a countdown or confirming what a typed duration parsed to. */
    public static String formatDuration(long millis) {
        long totalSeconds = Math.max(0, millis) / 1000;
        long h = totalSeconds / 3600;
        long m = (totalSeconds % 3600) / 60;
        long s = totalSeconds % 60;
        StringBuilder sb = new StringBuilder();
        if (h > 0) sb.append(h).append('h');
        if (m > 0 || h > 0) sb.append(m).append('m');
        sb.append(s).append('s');
        return sb.toString();
    }

    /** Called once a client tick: fires (splash/chat/sound) and removes any
     *  timer whose time has come. */
    public static void tick() {
        var cfg = SquidUtils.config();
        if (cfg == null || !cfg.tracker.enabled || !cfg.tracker.customTimers.enabled || timers.isEmpty()) return;

        long now = System.currentTimeMillis();
        List<CustomTimer> due = new ArrayList<>();
        for (CustomTimer t : timers) {
            if (now >= t.endAtMillis) due.add(t);
        }
        if (due.isEmpty()) return;

        List<CustomTimer> finished = new ArrayList<>();
        for (CustomTimer t : due) {
            fire(cfg.tracker.customTimers, t);
            t.firedCount++;
            if (t.loopMillis > 0 && (t.loopQuantity < 0 || t.firedCount < t.loopQuantity)) {
                // Catch up to at least "now" in one go rather than a single
                // += loopMillis - the client can sit closed far longer than
                // one loop interval, and leaving endAtMillis still in the
                // past would just re-trigger it again next tick, spamming
                // every missed occurrence in a burst the moment the game
                // reopens instead of quietly resyncing to the same cadence.
                while (t.endAtMillis <= now) {
                    t.endAtMillis += t.loopMillis;
                }
            } else {
                finished.add(t);
            }
        }
        timers.removeAll(finished);
        save();
    }

    private static void fire(CustomTimersCategory cfg, CustomTimer t) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        String message = t.name + " is up!";
        if (cfg.chatEnabled && mc.player != null) {
            mc.player.sendSystemMessage(Component.literal("")
                    .append(Component.literal("§b§lTIMER §7» "))
                    .append(Component.literal("§f" + message)));
        }
        if (cfg.sound.enabled) Sounds.play(cfg.sound.id, cfg.sound.pitch);
        if (cfg.splash.enabled) Splash.show(message, cfg.splash.scale, cfg.splash.seconds);
    }

    // ------------------------------------------------------------------
    private static Path resolveStorePath() {
        try {
            Path dir = FabricLoader.getInstance().getConfigDir().resolve(SquidUtils.MOD_ID);
            Files.createDirectories(dir);
            return dir.resolve("timers.json");
        } catch (Exception e) {
            SquidUtils.LOG.warn("[squidutils] could not resolve timers store path", e);
            return null;
        }
    }

    private static void load() {
        if (STORE_PATH == null || !Files.exists(STORE_PATH)) return;
        try (var reader = Files.newBufferedReader(STORE_PATH, StandardCharsets.UTF_8)) {
            var type = new com.google.gson.reflect.TypeToken<List<CustomTimer>>() {}.getType();
            List<CustomTimer> loaded = new Gson().fromJson(reader, type);
            if (loaded != null) timers.addAll(loaded);
        } catch (Exception e) {
            SquidUtils.LOG.warn("[squidutils] could not read timers.json", e);
        }
    }

    private static void save() {
        if (STORE_PATH == null) return;
        try {
            Files.writeString(STORE_PATH, new Gson().toJson(timers), StandardCharsets.UTF_8);
        } catch (Exception e) {
            SquidUtils.LOG.warn("[squidutils] could not write timers.json", e);
        }
    }
}
