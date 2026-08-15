package dev.squidutils.fusion;

import com.google.gson.Gson;
import dev.squidutils.SquidUtils;
import dev.squidutils.hud.ShoppingList;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Session totals for a fusion run: coins in, coins out, XP and fuses - plus
 * a lifetime "Total" view alongside it, the way Feesh's own trackers work.
 *
 * <p>Only shard trades count. The bazaar messages are identical in shape for
 * every item, so the item name is checked against the known shard roster -
 * otherwise buying enchanted cod for a completely unrelated reason lands in
 * your fusion profit.
 *
 * <p>Orders that fill in pieces are handled by counting each claim as it
 * arrives rather than assuming an order fills whole; a large buy order
 * routinely arrives as a dozen partial fills.
 *
 * <p>The session clock does not start at construction or at {@link #reset()}
 * - it arms on the first bazaar shard purchase or fusion, whichever comes
 * first, via {@link #start()}. Sitting in menus or hunting mobs before that
 * point would otherwise count as "elapsed" against a session with nothing in
 * it yet, understating every per-hour figure.
 *
 * <p>{@code total*} fields are the session fields' lifetime counterparts:
 * updated alongside them on every event, but never touched by {@link
 * #reset()}, and persisted to {@code tracker-stats.json} in the mod's config
 * directory so they survive a restart. Elapsed time is the one figure that
 * cannot just be summed the same way - {@link #priorElapsedSeconds} is the
 * total from every session before this one (loaded once, frozen for the rest
 * of this run except the fold-in {@link #reset()} does), and total elapsed is
 * always computed as that plus the current session's own live elapsed.
 */
public final class SessionTracker {

    // Confirmed working against the live server.
    private static final Pattern BOUGHT = Pattern.compile(
            "Bought\\s+([\\d,]+)x?\\s+(.+?)\\s+for\\s+([\\d,.]+)\\s+coins");

    // The remaining patterns are informed guesses. Anything that does not match
    // is captured verbatim by the diagnostic below rather than silently ignored.
    private static final Pattern SOLD = Pattern.compile(
            "Sold\\s+([\\d,]+)x?\\s+(.+?)\\s+for\\s+([\\d,.]+)\\s+coins");

    private static final Pattern CLAIMED_ITEMS = Pattern.compile(
            "Claimed\\s+([\\d,]+)x?\\s+(.+?)\\s+worth\\s+([\\d,.]+)\\s+coins");

    private static final Pattern CLAIMED_COINS = Pattern.compile(
            "Claimed\\s+([\\d,.]+)\\s+coins?\\s+from\\s+selling\\s+([\\d,]+)x?\\s+(.+?)\\s+at");

    /**
     * The fusion result line, confirmed from a live capture:
     * {@code §5§lFUSION! §7You obtained a §fHoneyhog Shard§7! §d§lNEW!}
     * (single output) and {@code §5§lFUSION! §7You obtained §9Wild Hog Shard
     * §8x2§7!} (multi-output, no article) - also seen with a trailing streak
     * counter on repeated identical fusions, {@code §8(§7x§r2§8)} and so on,
     * which the optional count group harmlessly leaves unconsumed rather than
     * misreading as an output quantity. Drives {@code fuses}, {@code
     * shardsFused} and the shopping list's completion tracking - not XP, see
     * {@link #HUNTING_XP} for that, though this result's rarity is still
     * useful paired with that real number - see {@link
     * #reverseEngineerWisdom}.
     *
     * <p>{@code §} formatting is baked directly into Hypixel's own message
     * text rather than carried as separate style data, so this can assume
     * plain text only because {@link #onChat} strips it first - the original
     * version of this pattern required {@code FUSION!} to be immediately
     * followed by {@code You obtained}, which never matched because a colour
     * code always sat in between. A single-output result is also worded
     * "a Honeyhog Shard", with an article the multi-output wording drops -
     * optional here for the same reason the {@code x<count>} suffix is.
     */
    private static final Pattern FUSION = Pattern.compile(
            "FUSION!\\s*You obtained\\s+(?:an?\\s+)?(.+?)(?:\\s*x\\s*(\\d+))?!");

    /**
     * The live Hunting skill XP gain, shown on the action bar - confirmed
     * from SkyHanni's own skill tracker (a mod this same player already
     * runs), which reads every SkyBlock skill this same way, Hunting
     * included: {@code +207.2 Hunting (5,183,244/0)}. Some skills show a
     * percentage instead of a raw fraction, so the parenthesised part is
     * matched loosely - only the number right after {@code +} matters here.
     *
     * <p>Reading this directly replaces computing XP from rarity + Hunting
     * Wisdom entirely: Wisdom is easy to have stale (see {@link
     * WisdomDetector}, which only refreshes it once the right menu is
     * opened), and this is the exact number Hypixel granted, not a
     * recomputation of it that inherits whatever the stored Wisdom happens
     * to be at the time.
     */
    private static final Pattern HUNTING_XP = Pattern.compile(
            "\\+([\\d.,]+)\\s+Hunting\\s*\\([^)]*\\)");

    /** Lines worth capturing when nothing matched, for pattern refinement. */
    private static final Pattern INTERESTING = Pattern.compile(
            "(?i)bazaar|hunting|fusion|fuse|claim|sold|sell|bought|buy|order|coins|xp");

    private static final int MAX_CAPTURES = 40;

    /** Capture everything, not just lines that look relevant. */
    private boolean captureAll;
    private int seenChat;
    private int seenOverlay;

    /** Shard display names, lowercased and stripped, mapped to their rarity. */
    private static final java.util.Map<String, String> SHARDS = new java.util.HashMap<>();

    // Session clock - armed by start(), not by construction or reset().
    private boolean started;
    private long startedAt;
    private long pausedTotal;
    private long pausedAt;
    private boolean paused;

    // Session totals - cleared by reset().
    private double coinsSpent;
    private double coinsGained;
    private double xpGained;
    private long shardsBought;
    private long shardsSold;
    private long fuses;
    private long shardsFused;   // shards produced, which is not one per fusion

    // Lifetime totals - persisted, never cleared by reset(). See the class doc
    // for how priorElapsedSeconds combines with the live session clock.
    private double totalCoinsSpent;
    private double totalCoinsGained;
    private double totalXpGained;
    private long totalShardsBought;
    private long totalShardsSold;
    private long totalFuses;
    private long totalShardsFused;
    private long priorElapsedSeconds;

    /** Session vs Total - HUD view state only, not persisted. */
    private boolean viewingTotal;

    private final Path statsPath;

    private final Set<String> captured = new LinkedHashSet<>();
    private String lastOverlay = "";
    // Deduplicated separately from lastOverlay: the action bar can pack
    // several stats into one line ("+50 Bits  +207.2 Hunting (...)"), and if
    // some other part of that line keeps changing frame to frame while the
    // Hunting portion does not, lastOverlay's whole-line comparison would
    // never catch it as a repeat, double-counting the same gain.
    private String lastHuntingActionBar = "";

    // Whichever of FUSION or HUNTING_XP arrives first, briefly, waiting for
    // the other - see reverseEngineerWisdom(). Only one of the two pending
    // slots is ever occupied at a time in practice, since a match on either
    // side immediately tries to pair and clear.
    private String pendingFusionRarity;
    private long pendingFusionAtMillis;
    private double pendingHuntingXp = -1;
    private long pendingHuntingXpAtMillis;
    private static final long PAIR_WINDOW_MILLIS = 2000;

    public SessionTracker() {
        Path dir = null;
        try {
            dir = FabricLoader.getInstance().getConfigDir().resolve(SquidUtils.MOD_ID);
            Files.createDirectories(dir);
        } catch (Exception e) {
            SquidUtils.LOG.warn("[squidutils] could not resolve tracker stats path", e);
        }
        statsPath = dir == null ? null : dir.resolve("tracker-stats.json");
        load();
    }

    // ------------------------------------------------------------------
    /** Supply the shard roster so trades in other items can be ignored. */
    public static void setShards(java.util.Map<String, String> nameToRarity) {
        SHARDS.clear();
        for (var e : nameToRarity.entrySet()) {
            SHARDS.put(normalise(e.getKey()), e.getValue());
        }
    }

    /** Rarity of a shard by its chat name, or null if unknown - the shape
     *  {@link #reverseEngineerWisdom} needs to turn a fusion result into a
     *  base XP value via {@link dev.squidutils.fusion.engine.Scorer#huntingXp}. */
    public static String rarityOf(String itemName) {
        return itemName == null ? null : SHARDS.get(normalise(itemName));
    }

    /**
     * Is this chat item name one of the shards?
     *
     * <p>Hypixel writes them as "Cod Shard" while the recipe data calls it
     * "Cod", so the word "shard" is stripped from both sides before comparing.
     */
    public static boolean isShard(String itemName) {
        if (itemName == null || SHARDS.isEmpty()) return false;
        return SHARDS.containsKey(normalise(itemName));
    }

    private static String normalise(String s) {
        String t = s.toLowerCase(Locale.ROOT).replace("shard", " ");
        return t.replaceAll("[^a-z0-9]", "");
    }

    // ------------------------------------------------------------------
    /** Arms the session clock on the first qualifying event - see the class
     *  doc. A no-op once already started, so every caller can just call this
     *  unconditionally at the top of its branch. */
    private void start() {
        if (started) return;
        started = true;
        startedAt = System.currentTimeMillis();
        pausedTotal = 0;
        if (paused) pausedAt = startedAt;
    }

    public void reset() {
        // Folded in before clearing, so Total's elapsed time does not lose
        // whatever this session had already counted toward it.
        priorElapsedSeconds += elapsedSeconds();

        started = false;
        startedAt = 0;
        pausedTotal = 0;
        pausedAt = paused ? System.currentTimeMillis() : 0;
        coinsSpent = coinsGained = xpGained = 0;
        shardsBought = shardsSold = fuses = shardsFused = 0;
        captured.clear();
        save();
    }

    public void togglePause() {
        if (paused) {
            pausedTotal += System.currentTimeMillis() - pausedAt;
            paused = false;
        } else {
            pausedAt = System.currentTimeMillis();
            paused = true;
        }
    }

    public boolean paused() { return paused; }
    public boolean started() { return started; }

    public boolean viewingTotal() { return viewingTotal; }
    public void toggleViewMode() { viewingTotal = !viewingTotal; }

    public long elapsedSeconds() {
        if (!started) return 0;
        long now = paused ? pausedAt : System.currentTimeMillis();
        return Math.max(0, (now - startedAt - pausedTotal) / 1000);
    }

    public long totalElapsedSeconds() {
        return priorElapsedSeconds + elapsedSeconds();
    }

    public double perHour(double total, long secs) {
        if (secs < 5) return 0;
        return total * 3600.0 / secs;
    }

    public double coinsSpent() { return coinsSpent; }
    public double coinsGained() { return coinsGained; }
    public double profit() { return coinsGained - coinsSpent; }
    public double xpGained() { return xpGained; }
    public long shardsBought() { return shardsBought; }
    public long shardsSold() { return shardsSold; }
    public long fuses() { return fuses; }
    public long shardsFused() { return shardsFused; }

    public double totalCoinsSpent() { return totalCoinsSpent; }
    public double totalCoinsGained() { return totalCoinsGained; }
    public double totalProfit() { return totalCoinsGained - totalCoinsSpent; }
    public double totalXpGained() { return totalXpGained; }
    public long totalShardsBought() { return totalShardsBought; }
    public long totalShardsSold() { return totalShardsSold; }
    public long totalFuses() { return totalFuses; }
    public long totalShardsFused() { return totalShardsFused; }

    public Set<String> captured() { return captured; }

    // ------------------------------------------------------------------
    /**
     * @param overlay true for the action bar, where Hypixel puts skill XP. The
     *        action bar repeats the same text for several ticks, so identical
     *        consecutive lines are skipped - otherwise one 402 XP gain would be
     *        counted five or six times over.
     */
    public void onChat(String text, boolean overlay) {
        if (text == null || text.isEmpty()) return;

        // Counted even while paused, so the diagnostic can tell "nothing is
        // arriving" apart from "arriving but not matching".
        if (overlay) seenOverlay++; else seenChat++;
        if (paused) return;

        // Hypixel bakes "§" formatting directly into the string content for
        // a lot of its own messages - confirmed from a live capture, where
        // the fusion line arrived as literal "§5§lFUSION! §7You obtained...".
        // Stripping it here means every pattern below can assume plain text
        // instead of each needing to guess where a colour code might land.
        text = text.replaceAll("§.", "");

        if (overlay) {
            if (text.equals(lastOverlay)) return;
            lastOverlay = text;

            // Checked here rather than folded into the pattern chain below:
            // this is action-bar only (chat never carries skill XP), and does
            // not return - a line that happens to also carry something else
            // useful should still fall through to the rest of the checks.
            Matcher hm = HUNTING_XP.matcher(text);
            if (hm.find() && !hm.group(0).equals(lastHuntingActionBar)) {
                lastHuntingActionBar = hm.group(0);
                // Hunting XP has no source but fusing, so this alone is
                // proof one just happened - arms the clock the same as the
                // FUSION chat line does, in case the two arrive out of order.
                start();
                double gained = parseDouble(hm.group(1));
                xpGained += gained;
                totalXpGained += gained;

                long now = System.currentTimeMillis();
                if (pendingFusionRarity != null && now - pendingFusionAtMillis <= PAIR_WINDOW_MILLIS) {
                    reverseEngineerWisdom(pendingFusionRarity, gained);
                    pendingFusionRarity = null;
                } else {
                    pendingHuntingXp = gained;
                    pendingHuntingXpAtMillis = now;
                }
                save();
            }
        }

        Matcher m = BOUGHT.matcher(text);
        if (m.find()) {
            if (isShard(m.group(2))) {
                start();
                long qty = parseLong(m.group(1));
                double coins = parseDouble(m.group(3));
                shardsBought += qty;
                coinsSpent += coins;
                totalShardsBought += qty;
                totalCoinsSpent += coins;
                save();
            }
            return;
        }
        m = SOLD.matcher(text);
        if (m.find()) {
            if (isShard(m.group(2))) {
                long qty = parseLong(m.group(1));
                double coins = parseDouble(m.group(3));
                shardsSold += qty;
                coinsGained += coins;
                totalShardsSold += qty;
                totalCoinsGained += coins;
                save();
            }
            return;
        }
        m = CLAIMED_COINS.matcher(text);
        if (m.find()) {
            if (isShard(m.group(3))) {
                long qty = parseLong(m.group(2));
                double coins = parseDouble(m.group(1));
                shardsSold += qty;
                coinsGained += coins;
                totalShardsSold += qty;
                totalCoinsGained += coins;
                save();
            }
            return;
        }
        m = CLAIMED_ITEMS.matcher(text);
        if (m.find()) {
            if (isShard(m.group(2))) {
                long qty = parseLong(m.group(1));
                double coins = parseDouble(m.group(3));
                if (text.contains("bought for")) {
                    start();
                    shardsBought += qty;
                    coinsSpent += coins;
                    totalShardsBought += qty;
                    totalCoinsSpent += coins;
                } else {
                    shardsSold += qty;
                    coinsGained += coins;
                    totalShardsSold += qty;
                    totalCoinsGained += coins;
                }
                save();
            }
            return;
        }
        m = FUSION.matcher(text);
        if (m.find()) {
            start();
            long produced = m.group(2) != null ? parseLong(m.group(2)) : 1;
            fuses++;
            shardsFused += produced;
            totalFuses++;
            totalShardsFused += produced;
            ShoppingList.onFusionCompleted(m.group(1));
            // XP is not counted here - see HUNTING_XP, which reads the real
            // number Hypixel granted independently of this message. This
            // result's rarity is still useful on its own, though: paired
            // with that real number, it is enough to back out the current
            // Hunting Wisdom - see reverseEngineerWisdom().
            String rarity = rarityOf(m.group(1));
            if (rarity != null) {
                long now = System.currentTimeMillis();
                if (pendingHuntingXp >= 0 && now - pendingHuntingXpAtMillis <= PAIR_WINDOW_MILLIS) {
                    reverseEngineerWisdom(rarity, pendingHuntingXp);
                    pendingHuntingXp = -1;
                } else {
                    pendingFusionRarity = rarity;
                    pendingFusionAtMillis = now;
                }
            }
            save();
            return;
        }

        capture(text);
    }

    /**
     * Keep every distinct line we could not parse.
     *
     * <p>The previous version logged only the first, which was invariably an
     * uninteresting one - so the messages that actually needed matching never
     * got seen. These are shown on demand with /squid debug.
     */
    private void capture(String text) {
        if (captured.size() >= MAX_CAPTURES) return;
        if (!captureAll && !INTERESTING.matcher(text).find()) return;
        String trimmed = text.trim();
        if (trimmed.length() < 4 || !captured.add(trimmed)) return;
        SquidUtils.LOG.info("[squidutils] unparsed: {}", trimmed);
    }

    public void toggleCaptureAll() {
        captureAll = !captureAll;
    }

    public boolean captureAll() { return captureAll; }

    /**
     * Print what we failed to parse, so the wording can be reported.
     *
     * <p>Deliberately bright: an earlier version printed these in dark grey,
     * which on a dark background looked exactly like nothing being printed at
     * all. The counts matter too - they separate "no messages are reaching the
     * handler" from "messages arrive but none match".
     */
    public void dumpCaptured() {
        var player = Minecraft.getInstance().player;
        if (player == null) return;

        player.sendSystemMessage(Component.literal(
                "§d[Squid Utils] §7seen §f" + seenChat + "§7 chat, §f" + seenOverlay
                        + "§7 action bar · capture-all is §f"
                        + (captureAll ? "on" : "off")));
        player.sendSystemMessage(Component.literal(
                "§d[Squid Utils] §7counters — started §f" + started
                        + "§7, spent §f" + (long) coinsSpent
                        + "§7, earned §f" + (long) coinsGained
                        + "§7, xp §f" + (long) xpGained
                        + "§7, fused §f" + fuses));

        if (captured.isEmpty()) {
            player.sendSystemMessage(Component.literal(
                    "§d[Squid Utils] §7nothing captured. Try §f/squid debug all§7, "
                            + "then sell a shard or fuse."));
            return;
        }
        player.sendSystemMessage(Component.literal(
                "§d[Squid Utils] §7" + captured.size() + " unmatched line(s):"));
        int i = 1;
        for (String s : captured) {
            player.sendSystemMessage(Component.literal("§e" + (i++) + ". §f" + s));
        }
    }

    /**
     * Back out the current Hunting Wisdom from one real fusion: knowing the
     * result's rarity gives the base XP ({@link
     * dev.squidutils.fusion.engine.Scorer#huntingXp}), and {@link
     * #HUNTING_XP} just gave the true amount actually granted, so {@code
     * gained = base * (1 + wisdom / 100)} has exactly one unknown left.
     *
     * <p>This is a second, independent way of keeping {@code
     * huntingWisdom} current alongside {@link WisdomDetector} - that one
     * needs the right SkyBlock menu open at some point in the session; this
     * one needs nothing but a single fusion, which is the whole reason to
     * have this mod open in the first place. Both write the same config
     * field, so every fusion self-corrects it a little further regardless
     * of which one last touched it.
     *
     * <p>Writes straight into the live config rather than keeping its own
     * copy, so the ranking engine's XP-per-fuse and XP-per-1,000-coins
     * figures - the XP table, its graphs, and the tooltip's cheapest-route
     * line - all pick up the correction on their very next refresh with no
     * further wiring needed.
     */
    private void reverseEngineerWisdom(String rarity, double gainedXp) {
        var cfg = SquidUtils.config();
        if (cfg == null || !cfg.fusion.general.autoDetectWisdom) return;

        double base = dev.squidutils.fusion.engine.Scorer.huntingXp(rarity);
        if (base <= 0) return;

        double wisdom = (gainedXp / base - 1.0) * 100.0;
        // A little slack for the action bar's own rounding rather than a
        // hard floor at zero - a small negative reading is measurement
        // noise, not evidence Wisdom actually went negative (impossible).
        // Anything further out than that is more likely a bad pairing (the
        // wrong rarity matched to this XP amount) than real Wisdom, so it
        // is discarded rather than stored.
        if (wisdom < -5 || wisdom > 10_000) return;
        wisdom = Math.max(0, wisdom);

        float current = cfg.fusion.general.huntingWisdom;
        if (Math.abs(current - wisdom) < 0.05) return;   // already correct

        cfg.fusion.general.huntingWisdom = (float) wisdom;
        var managed = SquidUtils.managedConfig();
        if (managed != null) managed.saveToFile();

        var player = Minecraft.getInstance().player;
        if (player != null) {
            player.sendSystemMessage(Component.literal(
                    "§d[Squid Utils] §7Hunting Wisdom recalculated as §b"
                            + String.format(Locale.ROOT, "%.1f", wisdom)
                            + " §7from that last fusion; XP figures now match your stats."));
        }
    }

    // ------------------------------------------------------------------
    /** The persisted shape of the lifetime totals - see the class doc for
     *  why elapsed time needs {@link #priorElapsedSeconds} instead of just
     *  being summed like every other field. */
    private static final class SaveData {
        double totalCoinsSpent;
        double totalCoinsGained;
        double totalXpGained;
        long totalShardsBought;
        long totalShardsSold;
        long totalFuses;
        long totalShardsFused;
        long priorElapsedSeconds;
    }

    private void load() {
        if (statsPath == null || !Files.exists(statsPath)) return;
        try (var reader = Files.newBufferedReader(statsPath, StandardCharsets.UTF_8)) {
            SaveData d = new Gson().fromJson(reader, SaveData.class);
            if (d == null) return;
            totalCoinsSpent = d.totalCoinsSpent;
            totalCoinsGained = d.totalCoinsGained;
            totalXpGained = d.totalXpGained;
            totalShardsBought = d.totalShardsBought;
            totalShardsSold = d.totalShardsSold;
            totalFuses = d.totalFuses;
            totalShardsFused = d.totalShardsFused;
            priorElapsedSeconds = d.priorElapsedSeconds;
        } catch (Exception e) {
            SquidUtils.LOG.warn("[squidutils] could not read tracker-stats.json", e);
        }
    }

    /** Called after every mutating event - these are infrequent (a handful a
     *  second at the very busiest) and the file is tiny, so there is no need
     *  to batch or throttle it. */
    private void save() {
        if (statsPath == null) return;
        SaveData d = new SaveData();
        d.totalCoinsSpent = totalCoinsSpent;
        d.totalCoinsGained = totalCoinsGained;
        d.totalXpGained = totalXpGained;
        d.totalShardsBought = totalShardsBought;
        d.totalShardsSold = totalShardsSold;
        d.totalFuses = totalFuses;
        d.totalShardsFused = totalShardsFused;
        // Not written back into the in-memory field - see the class doc.
        // Only ever recomputed fresh for the file, so a later event this same
        // run does not double-count today's elapsed time on top of itself.
        d.priorElapsedSeconds = priorElapsedSeconds + elapsedSeconds();
        try {
            Files.writeString(statsPath, new Gson().toJson(d), StandardCharsets.UTF_8);
        } catch (Exception e) {
            SquidUtils.LOG.warn("[squidutils] could not write tracker-stats.json", e);
        }
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.replace(",", "").replace(".", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static double parseDouble(String s) {
        try {
            return Double.parseDouble(s.replace(",", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
