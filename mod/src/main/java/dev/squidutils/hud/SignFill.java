package dev.squidutils.hud;

import dev.squidutils.SquidUtils;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.glfw.GLFW;

/**
 * Remembers "the shopping list just told you to buy N of this," and fills
 * that number into the next sign-edit screen that opens - the same
 * click-then-go-to-bazaar-then-click-again convenience SkyHanni's visitor
 * shopping list uses, since Hypixel's bazaar custom-amount prompt is a real
 * sign-edit screen under the hood.
 *
 * <p>There is no public API for setting a sign's text after it opens - only
 * the public input entry points {@code keyPressed}/{@code charTyped} are
 * exposed - so this drives those directly, exactly as if the numbers had been
 * typed by hand: backspacing whatever the screen pre-filled, then typing the
 * digits. It never presses Enter or closes the sign, so the player still sees
 * and confirms the number themselves before it goes anywhere.
 */
public final class SignFill {

    private static final long TIMEOUT_MILLIS = 120_000;

    private static volatile int pendingUnits = -1;
    private static volatile String pendingShard;
    private static volatile long pendingAtMillis;

    private SignFill() {}

    /** Call when a shopping-list entry is clicked to open its bazaar page. */
    public static void remember(String shardName, int units) {
        pendingUnits = units;
        pendingShard = shardName;
        pendingAtMillis = System.currentTimeMillis();
    }

    /**
     * Call once for every newly-opened screen. No-ops unless it is a sign
     * editor and a request is still pending and fresh - one-shot, so a sign
     * you open unrelated to any of this, long after the click, is left alone.
     */
    public static void tryFill(Screen screen) {
        int units = pendingUnits;
        if (units <= 0 || !(screen instanceof AbstractSignEditScreen sign)) return;
        boolean fresh = System.currentTimeMillis() - pendingAtMillis <= TIMEOUT_MILLIS;
        pendingUnits = -1;
        if (!fresh) return;

        SquidUtils.LOG.info("[squidutils] filling sign with {} (requested for {})",
                units, pendingShard);
        for (int i = 0; i < 12; i++) {
            sign.keyPressed(new KeyEvent(GLFW.GLFW_KEY_BACKSPACE, 0, 0));
        }
        for (char c : String.valueOf(units).toCharArray()) {
            sign.charTyped(new CharacterEvent(c));
        }
    }
}
