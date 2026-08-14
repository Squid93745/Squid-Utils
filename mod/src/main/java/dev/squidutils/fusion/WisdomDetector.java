package dev.squidutils.fusion;

import dev.squidutils.SquidUtils;
import dev.squidutils.config.SquidUtilsConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads Hunting Wisdom off the SkyBlock stats menu instead of asking for it.
 *
 * <p>Wisdom scales fusion XP, and asking the player to type it in means the
 * number is wrong the moment their gear changes.
 *
 * <p>Every open container is scanned - plus the player's own inventory, since
 * the stats item is not always in the container half - for an item whose lore
 * carries a "Hunting Wisdom" line. Both the plain and styled lore lists are
 * checked, because which one a server populates varies.
 *
 * <p>Detection is announced in chat. A silent auto-detector that quietly fails
 * is worse than no auto-detector, because you cannot tell the difference
 * between "it read 41.5" and "it never ran".
 */
public final class WisdomDetector {

    // Tolerates the colour codes, symbols and padding SkyBlock puts around it.
    private static final Pattern PATTERN =
            Pattern.compile("Hunting Wisdom[^0-9-]{0,8}([0-9]+(?:[.,][0-9]+)?)");

    /**
     * Other skills' wisdom lines, used to recognise the stats panel.
     *
     * <p>Plenty of gear grants Hunting Wisdom and says so in its lore, so
     * matching the number alone picks up a single accessory's bonus instead of
     * the player's total - which is exactly what happened: an item granting
     * +4.5 was read as a total of 4.5 against an actual 41.5.
     */
    private static final Pattern OTHER_WISDOM = Pattern.compile(
            "(Combat|Farming|Fishing|Mining|Foraging|Enchanting|Alchemy|"
                    + "Carpentry|Runecrafting|Taming|Social) Wisdom");

    /** The stats panel states the resulting multiplier; gear never does. */
    private static final Pattern MULTIPLIER = Pattern.compile("XP Multiplier");

    /** A leading + marks a gear bonus rather than a total. */
    private static final Pattern BONUS = Pattern.compile("\\+\\s*[0-9]");

    /** Scanning every tick would be wasteful; menus do not change that fast. */
    private static final int TICK_INTERVAL = 10;

    private static int ticks;
    private static float lastSeen = -1;
    private static boolean announcedThisSession;

    private WisdomDetector() {}

    public static void tick(Minecraft mc) {
        if (mc == null || mc.player == null) return;
        SquidUtilsConfig cfg = SquidUtils.config();
        if (cfg == null || !cfg.fusion.general.autoDetectWisdom) return;
        if (++ticks < TICK_INTERVAL) return;
        ticks = 0;

        if (mc.screen instanceof AbstractContainerScreen<?> screen) {
            for (Slot slot : screen.getMenu().slots) {
                if (scan(cfg, mc, slot.getItem())) return;
            }
        }
        for (ItemStack stack : mc.player.getInventory().getNonEquipmentItems()) {
            if (scan(cfg, mc, stack)) return;
        }
    }

    private static boolean scan(SquidUtilsConfig cfg, Minecraft mc, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore == null) return false;

        if (check(cfg, mc, lore.lines())) return true;
        return check(cfg, mc, lore.styledLines());
    }

    /**
     * Read a whole lore block, accepting a value only when the block is clearly
     * the stats panel rather than a piece of gear.
     *
     * <p>Two things identify it: an "XP Multiplier" line, which only the stats
     * display carries, or several other skills' wisdom listed alongside, which
     * only the Wisdom Stats summary has. A lone "+4.5 Hunting Wisdom" on an
     * accessory satisfies neither and is ignored.
     */
    private static boolean check(SquidUtilsConfig cfg, Minecraft mc, List<Component> lines) {
        if (lines == null || lines.isEmpty()) return false;

        boolean hasMultiplier = false;
        int otherSkills = 0;
        Float hunting = null;

        for (Component line : lines) {
            String text = line.getString();
            if (MULTIPLIER.matcher(text).find()) hasMultiplier = true;
            if (OTHER_WISDOM.matcher(text).find()) otherSkills++;

            Matcher m = PATTERN.matcher(text);
            if (m.find() && !BONUS.matcher(text).find()) {
                try {
                    hunting = Float.parseFloat(m.group(1).replace(',', '.'));
                } catch (NumberFormatException ignored) {
                    // keep looking
                }
            }
        }

        if (hunting == null) return false;
        if (!hasMultiplier && otherSkills < 2) return false;   // gear, not the panel
        return apply(cfg, mc, hunting);
    }

    /** Store a value taken from a verified stats panel. */
    private static boolean apply(SquidUtilsConfig cfg, Minecraft mc, float value) {
        if (value < 0 || value > 10_000) return false;

        boolean changed = Math.abs(cfg.fusion.general.huntingWisdom - value) >= 0.01f;
        boolean firstSight = lastSeen < 0;
        lastSeen = value;

        // Announce again whenever the number actually moves, so a correction to
        // a previously mis-read value is visible rather than silent.
        if (changed) announcedThisSession = false;

        if (changed) {
            float previous = cfg.fusion.general.huntingWisdom;
            cfg.fusion.general.huntingWisdom = value;
            var managed = SquidUtils.managedConfig();
            if (managed != null) managed.saveToFile();
            SquidUtils.LOG.info("[squidutils] hunting wisdom {} -> {} (read from stats menu)",
                    previous, value);
        }

        if ((changed || firstSight) && !announcedThisSession && mc.player != null) {
            announcedThisSession = true;
            mc.player.sendSystemMessage(Component.literal(
                    "§d[Squid Utils] §7Hunting Wisdom read as §b" + value
                            + "§7; XP figures now match your stats."));
        }
        return true;
    }

    /** The last value read this session, or -1 if nothing has been read. */
    public static float lastSeen() {
        return lastSeen;
    }
}
