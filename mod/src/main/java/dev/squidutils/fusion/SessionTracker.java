package dev.squidutils.fusion;

import dev.squidutils.SquidUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Session totals for a fusion run: coins in, coins out, XP and fuses.
 *
 * <p>Only shard trades count. The bazaar messages are identical in shape for
 * every item, so the item name is checked against the known shard roster -
 * otherwise buying enchanted cod for a completely unrelated reason lands in
 * your fusion profit.
 *
 * <p>Orders that fill in pieces are handled by counting each claim as it
 * arrives rather than assuming an order fills whole; a large buy order
 * routinely arrives as a dozen partial fills.
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
     * The fusion result line, confirmed in game:
     * {@code FUSION! You obtained Tempest Shard x2! NEW!}
     *
     * <p>This is the whole answer for both fuses and XP. Scraping Hunting XP
     * off the action bar never worked, and would have been fragile anyway -
     * the bar repeats and rounds. The result shard's rarity gives the base XP
     * exactly, so one reliable message replaces two unreliable ones.
     */
    private static final Pattern FUSION = Pattern.compile(
            "FUSION!\\s*You obtained\\s+(.+?)\\s*x\\s*(\\d+)");

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

    private long startedAt = System.currentTimeMillis();
    private long pausedTotal;
    private long pausedAt;
    private boolean paused;

    private double coinsSpent;
    private double coinsGained;
    private double xpGained;
    private long shardsBought;
    private long shardsSold;
    private long fuses;
    private long shardsFused;   // shards produced, which is not one per fusion

    private final Set<String> captured = new LinkedHashSet<>();
    private String lastOverlay = "";

    // ------------------------------------------------------------------
    /** Supply the shard roster so trades in other items can be ignored. */
    public static void setShards(java.util.Map<String, String> nameToRarity) {
        SHARDS.clear();
        for (var e : nameToRarity.entrySet()) {
            SHARDS.put(normalise(e.getKey()), e.getValue());
        }
    }

    /** Rarity of a shard by its chat name, or null if unknown. */
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
    public void reset() {
        startedAt = System.currentTimeMillis();
        pausedTotal = 0;
        pausedAt = paused ? startedAt : 0;
        coinsSpent = coinsGained = xpGained = 0;
        shardsBought = shardsSold = fuses = shardsFused = 0;
        captured.clear();
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

    public long elapsedSeconds() {
        long now = paused ? pausedAt : System.currentTimeMillis();
        return Math.max(0, (now - startedAt - pausedTotal) / 1000);
    }

    public double perHour(double total) {
        long secs = elapsedSeconds();
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

        if (overlay) {
            if (text.equals(lastOverlay)) return;
            lastOverlay = text;
        }

        Matcher m = BOUGHT.matcher(text);
        if (m.find()) {
            if (isShard(m.group(2))) {
                shardsBought += parseLong(m.group(1));
                coinsSpent += parseDouble(m.group(3));
            }
            return;
        }
        m = SOLD.matcher(text);
        if (m.find()) {
            if (isShard(m.group(2))) {
                shardsSold += parseLong(m.group(1));
                coinsGained += parseDouble(m.group(3));
            }
            return;
        }
        m = CLAIMED_COINS.matcher(text);
        if (m.find()) {
            if (isShard(m.group(3))) {
                shardsSold += parseLong(m.group(2));
                coinsGained += parseDouble(m.group(1));
            }
            return;
        }
        m = CLAIMED_ITEMS.matcher(text);
        if (m.find()) {
            if (isShard(m.group(2))) {
                long qty = parseLong(m.group(1));
                double coins = parseDouble(m.group(3));
                if (text.contains("bought for")) {
                    shardsBought += qty;
                    coinsSpent += coins;
                } else {
                    shardsSold += qty;
                    coinsGained += coins;
                }
            }
            return;
        }
        m = FUSION.matcher(text);
        if (m.find()) {
            fuses++;
            shardsFused += parseLong(m.group(2));

            // XP is granted per fusion by the rarity of the result, so the one
            // message gives both counters. Falls back to nothing rather than a
            // guess when the shard name is not recognised.
            String rarity = rarityOf(m.group(1));
            if (rarity != null) {
                float wisdom = 0;
                var cfg = SquidUtils.config();
                if (cfg != null) wisdom = cfg.fusion.general.huntingWisdom;
                xpGained += dev.squidutils.fusion.engine.Scorer.xpPerFuse(rarity, wisdom);
            } else {
                SquidUtils.LOG.info("[squidutils] fusion result not recognised: {}",
                        m.group(1));
            }
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
                "§d[Squid Utils] §7counters — spent §f" + (long) coinsSpent
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
