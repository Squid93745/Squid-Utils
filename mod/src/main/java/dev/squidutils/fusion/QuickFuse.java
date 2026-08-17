package dev.squidutils.fusion;

import dev.squidutils.SquidUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.util.List;
import java.util.Locale;

/**
 * A hotkey that clicks through the Fusion Box's own two confirmation prompts
 * - "Click to repeat this fusion!" and "Click to fuse!" - exactly the way
 * clicking them with the mouse does.
 *
 * <p>This fires once per keypress and only ever clicks a slot whose own
 * displayed text is one of those two known phrases - it does not know or
 * care where that slot sits, so a changed layout cannot make it click the
 * wrong thing, and no matching text means no click at all. That is the same
 * shape as SkyHanni's own garden "Accept Hotkey" and Pest Trap release
 * keybind: a single bounded action per single deliberate keypress, not
 * unattended automation.
 *
 * <p>{@link #press} calls {@code MultiPlayerGameMode.handleContainerInput}
 * directly with a left-click ({@link ContainerInput#PICKUP}) - confirmed by
 * disassembling {@code AbstractContainerScreen.slotClicked} that this is the
 * exact call a real mouse click makes, not a hand-rolled substitute for one.
 */
public final class QuickFuse {

    private QuickFuse() {}

    // Confirmed from a live capture of both prompts (see the two screenshots
    // this feature was built from). Lore/name text is plain Component data,
    // not the "§"-packed raw strings chat and the action bar carry, so no
    // stripping is needed before matching.
    private static final String REPEAT_PHRASE = "click to repeat this fusion";
    private static final String CONFIRM_PHRASE = "click to fuse!";

    /** Called on a fresh hotkey press - the caller already edge-detects. */
    public static void press(Minecraft mc) {
        if (mc == null || mc.player == null) return;
        if (!(mc.screen instanceof AbstractContainerScreen<?> screen)) return;

        Slot target = findSlot(screen, REPEAT_PHRASE);
        if (target == null) target = findSlot(screen, CONFIRM_PHRASE);
        if (target == null) return;

        SquidUtils.LOG.info("[squidutils] quick fuse clicking slot {}", target.index);
        mc.gameMode.handleContainerInput(
                screen.getMenu().containerId, target.index, 0,
                ContainerInput.PICKUP, mc.player);
    }

    private static Slot findSlot(AbstractContainerScreen<?> screen, String phrase) {
        for (Slot slot : screen.getMenu().slots) {
            if (matches(slot.getItem(), phrase)) return slot;
        }
        return null;
    }

    private static boolean matches(ItemStack stack, String phrase) {
        if (stack == null || stack.isEmpty()) return false;
        if (contains(stack.getHoverName(), phrase)) return true;
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore == null) return false;
        return contains(lore.lines(), phrase) || contains(lore.styledLines(), phrase);
    }

    private static boolean contains(Component c, String phrase) {
        return c != null && c.getString().toLowerCase(Locale.ROOT).contains(phrase);
    }

    private static boolean contains(List<Component> lines, String phrase) {
        if (lines == null) return false;
        for (Component line : lines) {
            if (contains(line, phrase)) return true;
        }
        return false;
    }
}
