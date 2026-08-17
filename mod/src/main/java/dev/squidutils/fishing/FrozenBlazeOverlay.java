package dev.squidutils.fishing;

import dev.squidutils.SquidUtils;
import dev.squidutils.config.FrozenBlazeCategory;
import dev.squidutils.config.SquidUtilsConfig;
import io.github.notenoughupdates.moulconfig.ChromaColour;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;

/**
 * Frozen Blaze's full-set bonus stops dealing its passive damage once you
 * have stood still long enough, so this fades a full-screen tint in as a
 * warning before that happens, then nags with a repeating sound once it has.
 *
 * <p>Two things gate this: {@link #wearingFullSet} - the bonus is a full-set
 * perk, so it does nothing with fewer than all four pieces on - and world
 * position. The Hypixel wiki describes the timeout as "not moving your
 * mouse", and this mod tried camera angle on that basis for a while, but the
 * player's own read of the real mechanic is positional - standing still, not
 * looking still - so {@link #tick} polls {@link Player#position()} instead.
 * {@link #STILL_EPSILON_SQ} tolerates the tiny position corrections the
 * server sends even while genuinely stationary, so the timer does not keep
 * restarting on its own; an ordinary click with no movement key held does
 * not move the player at all, so it does not reset the timer either.
 */
public final class FrozenBlazeOverlay {

    private FrozenBlazeOverlay() {}

    private static boolean havePos;
    private static double lastX, lastY, lastZ;
    private static long stillSinceMillis = -1;
    // Counts ticks since progress() first reached 1, driving the repeating
    // reminder sound - separate from stillSinceMillis, which drives the
    // smooth visual fade and must stay in real time regardless of tick rate.
    private static int ticksSinceFull = -1;

    private static final double STILL_EPSILON_SQ = 0.0001; // 0.01 blocks/tick

    /** Called once a client tick from {@code SquidUtils}. */
    public static void tick(Minecraft mc) {
        if (mc == null || mc.player == null) {
            reset();
            return;
        }
        SquidUtilsConfig cfg = SquidUtils.config();
        if (cfg == null || !cfg.fishing.general.enabled || !cfg.fishing.frozenBlaze.enabled
                || !wearingFullSet(mc.player)) {
            reset();
            return;
        }

        Vec3 pos = mc.player.position();
        if (!havePos || pos.distanceToSqr(lastX, lastY, lastZ) > STILL_EPSILON_SQ) {
            havePos = true;
            lastX = pos.x;
            lastY = pos.y;
            lastZ = pos.z;
            stillSinceMillis = -1;
            ticksSinceFull = -1;
            return;
        }
        if (stillSinceMillis < 0) stillSinceMillis = System.currentTimeMillis();

        if (progress() >= 1f) {
            if (ticksSinceFull < 0) ticksSinceFull = 0;
            int repeat = Math.max(1, cfg.fishing.frozenBlaze.sound.repeatTicks);
            // Fires immediately on the tick progress first hits 1, then every
            // repeat ticks after - not tied to wall-clock time, so it can't
            // drift out of sync with the tick counter itself.
            if (ticksSinceFull % repeat == 0) playReminder(mc, cfg);
            ticksSinceFull++;
        } else {
            ticksSinceFull = -1;
        }
    }

    private static void reset() {
        havePos = false;
        stillSinceMillis = -1;
        ticksSinceFull = -1;
    }

    /**
     * The Frozen Blazing Aura is a full-set bonus - confirmed from the wiki,
     * not assumed - so it does nothing while missing a piece. Matched against
     * each slot's own expected word rather than one blanket "frozen blaze"
     * check, so a Frozen Blaze helmet paired with unrelated leggings does not
     * false-positive as a complete set. Lowercased and substring-matched to
     * tolerate reforge prefixes and dungeon star suffixes Hypixel adds to the
     * display name, the same way {@code WisdomDetector} tolerates decoration
     * around the numbers it reads.
     */
    private static boolean wearingFullSet(Player player) {
        return pieceMatches(player, EquipmentSlot.HEAD, "frozen blaze helmet")
                && pieceMatches(player, EquipmentSlot.CHEST, "frozen blaze chestplate")
                && pieceMatches(player, EquipmentSlot.LEGS, "frozen blaze leggings")
                && pieceMatches(player, EquipmentSlot.FEET, "frozen blaze boots");
    }

    private static boolean pieceMatches(Player player, EquipmentSlot slot, String expected) {
        ItemStack stack = player.getItemBySlot(slot);
        if (stack.isEmpty()) return false;
        return stack.getHoverName().getString().toLowerCase(Locale.ROOT).contains(expected);
    }

    /**
     * 0 (invisible) to 1 (fully dark). Two phases: nothing shows at all until
     * the fade-in window starts, then it ramps linearly across that window,
     * landing on fully dark exactly at {@link FrozenBlazeCategory#STILLNESS_SECONDS}
     * - Hypixel's own fixed timing for when Frozen Blaze stops dealing
     * damage, not something this mod can offer as a setting. Not a ramp
     * spread across the whole 30 seconds, which is what an earlier version
     * of this did before "fade-in time" existed as its own setting.
     *
     * <p>Real elapsed time, not the tick counter {@link #tick} also keeps -
     * keeps the fade itself smooth at any framerate, since {@link #render}
     * calls this every frame, not once a tick.
     */
    public static float progress() {
        if (stillSinceMillis < 0) return 0f;
        SquidUtilsConfig cfg = SquidUtils.config();
        if (cfg == null) return 0f;
        var fb = cfg.fishing.frozenBlaze;

        double totalSeconds = FrozenBlazeCategory.STILLNESS_SECONDS;
        // Clamped rather than trusted: a fade-in longer than the stillness
        // time itself would put the ramp's start before t=0, which just
        // means to start fading immediately instead.
        double fadeSeconds = Math.min(fb.fadeInSeconds, totalSeconds);
        double rampStartSeconds = totalSeconds - fadeSeconds;

        double elapsedSeconds = (System.currentTimeMillis() - stillSinceMillis) / 1000.0;
        if (elapsedSeconds <= rampStartSeconds) return 0f;
        if (fadeSeconds <= 0) return elapsedSeconds >= totalSeconds ? 1f : 0f;

        double t = (elapsedSeconds - rampStartSeconds) / fadeSeconds;
        return (float) Math.min(1.0, Math.max(0.0, t));
    }

    /** Registered directly as a {@code HudElement} method reference. */
    public static void render(GuiGraphicsExtractor g, DeltaTracker delta) {
        SquidUtilsConfig cfg = SquidUtils.config();
        if (cfg == null || !cfg.general.showHud
                || !cfg.fishing.general.enabled || !cfg.fishing.frozenBlaze.enabled) {
            return;
        }
        float t = progress();
        if (t <= 0f) return;

        int argb = ChromaColour.forLegacyString(cfg.fishing.frozenBlaze.color).getEffectiveColourRGB();
        int baseAlpha = (argb >>> 24) & 0xFF;
        int alpha = resolveAlpha(cfg.fishing.frozenBlaze, baseAlpha, t);
        int drawColor = (alpha << 24) | (argb & 0x00FFFFFF);
        g.fill(0, 0, g.guiWidth(), g.guiHeight(), drawColor);
    }

    /**
     * The colour picker's own alpha channel is only the warning ceiling
     * while Frozen Blaze is still dealing damage ({@code t < 1}, ramping up
     * to it as {@link #progress} approaches full). The instant it actually
     * stops ({@code t == 1}), the opacity jumps straight to {@code
     * stoppedOpacity} - a 0-100 percent setting, not a raw 0-255 alpha, so
     * 100 means solidly opaque - because that setting represents a real
     * state change in the game, not a further countdown this mod is
     * tracking on its own.
     */
    private static int resolveAlpha(FrozenBlazeCategory fb, int baseAlpha, float t) {
        if (t < 1f) return Math.round(baseAlpha * t);

        int alpha = (int) Math.round(fb.stoppedOpacity / 100.0 * 255.0);
        return Math.max(0, Math.min(255, alpha));
    }

    private static void playReminder(Minecraft mc, SquidUtilsConfig cfg) {
        play(mc, cfg.fishing.frozenBlaze.sound.id, cfg.fishing.frozenBlaze.sound.pitch);
    }

    /** Wired to the config screen's "Test Sound" button. */
    public static void testSound() {
        Minecraft mc = Minecraft.getInstance();
        SquidUtilsConfig cfg = SquidUtils.config();
        if (mc == null || cfg == null) return;
        play(mc, cfg.fishing.frozenBlaze.sound.id, cfg.fishing.frozenBlaze.sound.pitch);
    }

    private static void play(Minecraft mc, String soundId, float pitch) {
        Identifier id = Identifier.tryParse(soundId);
        SoundEvent event = id == null ? null : BuiltInRegistries.SOUND_EVENT.getValue(id);
        if (event == null) {
            if (mc.player != null) {
                mc.player.sendSystemMessage(Component.literal(
                        "§d[Squid Utils] §7Unknown sound id: §f" + soundId));
            }
            return;
        }
        mc.getSoundManager().play(SimpleSoundInstance.forUI(event, pitch));
    }

    /**
     * Wired to the config screen's "List of Sounds" button. Dumped to a file
     * rather than chat - a typical modpack registers well over a thousand
     * sound events, which chat is not built to browse.
     */
    public static void listSounds() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null) return;
        try {
            Path dir = FabricLoader.getInstance().getConfigDir().resolve(SquidUtils.MOD_ID);
            Files.createDirectories(dir);
            Path file = dir.resolve("sounds.txt");

            var ids = new ArrayList<String>();
            for (Identifier id : BuiltInRegistries.SOUND_EVENT.keySet()) {
                ids.add(id.toString());
            }
            Collections.sort(ids);
            Files.write(file, ids, StandardCharsets.UTF_8);

            mc.player.sendSystemMessage(Component.literal(
                    "§d[Squid Utils] §7" + ids.size()
                            + " sound ids written to §f" + file));
        } catch (Exception e) {
            SquidUtils.LOG.warn("[squidutils] could not write sounds.txt", e);
        }
    }
}
