package dev.squidutils.fusion;

import com.google.gson.Gson;
import com.google.gson.annotations.Expose;
import dev.squidutils.SquidUtils;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tracks the player's Attribute levels - Pure Reptile chief among them, but
 * every attribute the game has, keyed by display name, since a future
 * feature caring about a different one should not need its own detector.
 *
 * <p>Three sources feed the same cached state, most-authoritative first:
 * <ol>
 *   <li><b>The Hypixel profile API</b> (planned, not wired up yet - the
 *   exact field path this account's own profile data exposes it under
 *   still needs pinning down before this can be built). Meant to run once
 *   per login, since it reflects your real, currently-toggled state without
 *   needing any menu open - the primary source once it exists.
 *   <li><b>The "Attribute Menu" inventory</b> ({@link #tick}) - scanned
 *   whenever it happens to be open, the same technique {@link
 *   WisdomDetector} already uses for Hunting Wisdom off the stats menu.
 *   Every entry's name carries a trailing roman numeral for its level
 *   ("Pure Reptile VII"), and its lore an "Enabled: Yes"/"Enabled: No"
 *   line - confirmed against SkyHanni's own tested patterns for this menu,
 *   genuinely new (mid-2025) content neither project had reference
 *   material for before. Doubles as the data source a future attribute
 *   overlay (styled like SkyHanni's own) will read from, so this scan
 *   stays even once the API path exists rather than being replaced by it.
 *   <li><b>Chat</b> ({@link #onChat}) - a backup between menu visits and
 *   API refreshes: syphoning a shard announces the attribute's exact
 *   resulting level directly, so a level-up is picked up the moment it
 *   happens rather than waiting for the next menu open or login.
 * </ol>
 *
 * <p>An attribute only contributes its bonus while enabled, so both level
 * and enabled state are tracked - only the menu (and eventually the API)
 * can see the toggle; chat only ever reveals a level.
 */
public final class AttributeDetector {

    /** Matches SkyHanni's own confirmed pattern for this menu's title. */
    private static final Pattern MENU_TITLE = Pattern.compile("^(?:\\(\\d+/\\d+\\) )?Attribute Menu$");

    /** A trailing roman numeral (I-X covers every attribute's 1-10 level
     *  range) on an item's name is that attribute's current level. Items
     *  with no numeral - not yet leveled, or unrelated menu furniture like
     *  the "Advanced Mode" toggle - simply do not match and are skipped. */
    private static final Pattern NAME_LEVEL = Pattern.compile("^(.+?) ([IVX]+)$");

    private static final Pattern ENABLED_LINE = Pattern.compile("Enabled: (Yes|No)");

    /** Syphoning a shard announces the attribute's exact resulting level in
     *  chat - confirmed against SkyHanni's own tested patterns:
     *  {@code +1 Arthropod Ruler Attribute (Level 1) - 2 more to upgrade!}
     *  {@code +6 Ender Ruler Attribute (Level 3) - 3 more to upgrade!} */
    private static final Pattern SYPHONED = Pattern.compile(
            "\\+\\d+ (.+?) Attribute \\(Level (\\d+)\\) - \\d+ more to upgrade!");

    /** As {@link #SYPHONED}, once no further syphoning does anything:
     *  {@code +43 Essence of Ice Attribute (Level 10) MAXED} */
    private static final Pattern SYPHONED_MAXED = Pattern.compile(
            "\\+\\d+ (.+?) Attribute \\(Level (\\d+)\\) MAXED");

    /** Pure Reptile's own confirmed formula: +2% chance per level, 1-10, so
     *  2%-20% - see the SkyBlock wiki's Attribute Fusion and Attributes/List
     *  pages, consistent across both. Echo of Hunter/Echo of Echoes are
     *  claimed by one of those pages to push this further (to "up to 26%"),
     *  but that specific interaction is not corroborated anywhere else found
     *  - Echo of Hunter's own dedicated page documents it boosting Hunter's
     *  Karma (a completely different stat, shard yield while hunting, not
     *  fusion output) instead. Left out rather than guessed at; only the
     *  confirmed base formula is applied.
     */
    private static final double CHANCE_PER_LEVEL = 0.02;
    public static final String PURE_REPTILE = "Pure Reptile";

    /** Scanning every tick would be wasteful; menus do not change that fast. */
    private static final int TICK_INTERVAL = 10;
    private static int ticks;

    private static final Map<String, State> STATE = new HashMap<>();
    private static final java.util.Set<String> ANNOUNCED = new java.util.HashSet<>();

    private static final Path STORE_PATH = resolveStorePath();

    private AttributeDetector() {}

    private static final class State {
        @Expose int level;
        @Expose boolean enabled;
    }

    static { load(); }

    public static void tick(Minecraft mc) {
        if (mc == null || mc.player == null) return;
        if (++ticks < TICK_INTERVAL) return;
        ticks = 0;

        if (!(mc.screen instanceof AbstractContainerScreen<?> screen)) return;
        if (!MENU_TITLE.matcher(screen.getTitle().getString()).matches()) return;

        for (Slot slot : screen.getMenu().slots) {
            scan(mc, slot.getItem());
        }
    }

    /** Backup path - see the class doc. Chat only ever reveals a level, never
     *  the enabled toggle, so an already-known enabled state is left alone
     *  rather than guessed at. */
    public static void onChat(String text) {
        if (text == null || text.isEmpty()) return;
        text = text.replaceAll("§.", "");

        Matcher m = SYPHONED.matcher(text);
        boolean found = m.find();
        if (!found) {
            m = SYPHONED_MAXED.matcher(text);
            found = m.find();
        }
        if (!found) return;

        String attribute = m.group(1).trim();
        int level;
        try {
            level = Integer.parseInt(m.group(2));
        } catch (NumberFormatException e) {
            return;
        }
        if (level <= 0) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        apply(mc, attribute, level, null);
    }

    private static void scan(Minecraft mc, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return;

        String name = strip(stack.getHoverName().getString());
        Matcher nm = NAME_LEVEL.matcher(name);
        if (!nm.matches()) return;
        String attribute = nm.group(1).trim();
        int level = fromRoman(nm.group(2));
        if (level <= 0) return;

        Boolean enabled = null;
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore != null) {
            for (Component line : lore.lines()) {
                Matcher em = ENABLED_LINE.matcher(strip(line.getString()));
                if (em.find()) {
                    enabled = em.group(1).equals("Yes");
                    break;
                }
            }
        }
        // No "Enabled:" line on this item is not evidence it is disabled -
        // some attributes (Chameleon Fusion's own entry, for one) have no
        // toggle at all and are always active. Leaving the last-known state
        // alone rather than defaulting to false avoids reading a real
        // "enabled" attribute as off just because this particular item's
        // lore does not carry the line.
        apply(mc, attribute, level, enabled);
    }

    private static void apply(Minecraft mc, String attribute, int level, Boolean enabled) {
        boolean firstSight = !STATE.containsKey(attribute);
        State state = STATE.computeIfAbsent(attribute, k -> new State());
        boolean changed = state.level != level || (enabled != null && state.enabled != enabled);
        state.level = level;
        if (enabled != null) state.enabled = enabled;

        if (changed) save();
        // Re-announced on every real change (a level-up, a toggle) as well as
        // the first sighting, same reasoning as WisdomDetector: a silent
        // auto-detector that quietly fails is worse than no detector, since
        // there is no way to tell "read correctly" apart from "never ran".
        if ((changed || firstSight) && ANNOUNCED.add(attribute + "@" + level + "@" + state.enabled)
                && mc.player != null) {
            mc.player.sendSystemMessage(Component.literal(
                    "§d[Squid Utils] §7" + attribute + " read as level §b" + level
                            + (state.enabled ? "§7, enabled" : "§7, disabled")));
        }
    }

    /** Level 1-10 -> 2%-20%, or 0 if never detected or currently disabled -
     *  see {@link #CHANCE_PER_LEVEL}'s doc for why nothing beyond the base
     *  formula is applied. */
    public static double pureReptileChance() {
        State state = STATE.get(PURE_REPTILE);
        if (state == null || !state.enabled) return 0.0;
        return Math.max(0, Math.min(10, state.level)) * CHANCE_PER_LEVEL;
    }

    public static int level(String attribute) {
        State state = STATE.get(attribute);
        return state == null ? 0 : state.level;
    }

    public static boolean enabled(String attribute) {
        State state = STATE.get(attribute);
        return state != null && state.enabled;
    }

    private static String strip(String s) {
        return s.replaceAll("§.", "");
    }

    private static int fromRoman(String s) {
        int total = 0, prev = 0;
        for (int i = s.length() - 1; i >= 0; i--) {
            int v = switch (Character.toUpperCase(s.charAt(i))) {
                case 'I' -> 1;
                case 'V' -> 5;
                case 'X' -> 10;
                default -> 0;
            };
            if (v == 0) return 0;
            total += v < prev ? -v : v;
            prev = v;
        }
        return total;
    }

    // ------------------------------------------------------------------
    private static Path resolveStorePath() {
        try {
            Path dir = FabricLoader.getInstance().getConfigDir().resolve(SquidUtils.MOD_ID);
            Files.createDirectories(dir);
            return dir.resolve("attributes.json");
        } catch (Exception e) {
            SquidUtils.LOG.warn("[squidutils] could not resolve attribute store path", e);
            return null;
        }
    }

    private static void load() {
        if (STORE_PATH == null || !Files.exists(STORE_PATH)) return;
        try (var reader = Files.newBufferedReader(STORE_PATH, StandardCharsets.UTF_8)) {
            var type = new com.google.gson.reflect.TypeToken<Map<String, State>>() {}.getType();
            Map<String, State> loaded = new Gson().fromJson(reader, type);
            if (loaded != null) STATE.putAll(loaded);
        } catch (Exception e) {
            SquidUtils.LOG.warn("[squidutils] could not read attributes.json", e);
        }
    }

    private static void save() {
        if (STORE_PATH == null) return;
        try {
            Files.writeString(STORE_PATH, new Gson().toJson(STATE), StandardCharsets.UTF_8);
        } catch (Exception e) {
            SquidUtils.LOG.warn("[squidutils] could not write attributes.json", e);
        }
    }
}
