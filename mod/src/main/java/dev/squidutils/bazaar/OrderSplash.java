package dev.squidutils.bazaar;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

/**
 * The big, centered, rainbow "order is outdated" text - a title card, not a
 * HUD panel, so unlike every other overlay this mod draws it is not
 * positioned by the drag editor: it always appears center-upper-screen,
 * fades in place for a fixed duration, and is gone.
 *
 * <p>A list of entries, not one - cancelling and relisting several orders at
 * once used to mean each new alert simply overwrote whatever the previous
 * one had just set, so only the last of a burst was ever actually seen. Each
 * {@link #show} call gets its own row instead, stacked below whichever
 * entries are still on screen, and each fades and expires independently on
 * its own timer.
 *
 * <p>Registered directly as a {@code HudElement} method reference, same as
 * {@code FrozenBlazeOverlay::render}.
 */
public final class OrderSplash {

    private OrderSplash() {}

    private record Entry(String text, float scale, long showUntilMillis, long fadeStartMillis) {}

    private static final long FADE_MILLIS = 400;
    /** Vertical gap between stacked rows, in unscaled pixels before each
     *  entry's own scale is applied. */
    private static final int ROW_GAP = 4;

    private static final List<Entry> entries = new ArrayList<>();

    /** Wired to {@link OrderTracker}'s alert. */
    public static void show(String message, float scale, int seconds) {
        long now = System.currentTimeMillis();
        long showUntil = now + seconds * 1000L;
        entries.add(new Entry(message, scale, showUntil, showUntil - FADE_MILLIS));
    }

    public static void render(GuiGraphicsExtractor g, DeltaTracker delta) {
        long now = System.currentTimeMillis();
        entries.removeIf(e -> now > e.showUntilMillis());
        if (entries.isEmpty()) return;

        Minecraft mc = Minecraft.getInstance();
        Font font = mc == null ? null : mc.font;
        if (font == null) return;

        int y = Math.round(g.guiHeight() * 0.35f);
        for (Entry entry : entries) {
            y = drawRow(g, font, entry, now, y);
        }
    }

    /** @return the y position the next stacked row should start at. */
    private static int drawRow(GuiGraphicsExtractor g, Font font, Entry entry, long now, int y) {
        float alpha = now < entry.fadeStartMillis() ? 1f
                : Math.max(0f, (entry.showUntilMillis() - now) / (float) FADE_MILLIS);
        int a = Math.round(alpha * 255) << 24;

        String text = entry.text();
        float scale = entry.scale();
        int textWidth = font.width(text);
        int x = Math.round((g.guiWidth() - textWidth * scale) / 2f);

        var pose = g.pose();
        pose.pushMatrix();
        pose.translate(x, y);
        pose.scale(scale, scale);

        // Rainbow per character, animated - each character's hue offset by
        // its position, all of them cycling together over time.
        int cx = 0;
        for (int i = 0; i < text.length(); i++) {
            String ch = String.valueOf(text.charAt(i));
            float hue = (i * 0.06f + (now % 3000) / 3000f) % 1f;
            int rgb = Color.HSBtoRGB(hue, 0.75f, 1.0f) & 0xFFFFFF;
            g.text(font, ch, cx, 0, a | rgb);
            cx += font.width(ch);
        }
        pose.popMatrix();

        return y + Math.round((font.lineHeight + ROW_GAP) * scale);
    }
}
