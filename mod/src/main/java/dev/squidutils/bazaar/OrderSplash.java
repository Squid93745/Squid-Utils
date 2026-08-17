package dev.squidutils.bazaar;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.awt.Color;

/**
 * The big, centered, rainbow "order is outdated" text - a title card, not a
 * HUD panel, so unlike every other overlay this mod draws it is not
 * positioned by the drag editor: it always appears center-upper-screen,
 * fades in place for a fixed duration, and is gone.
 *
 * <p>Registered directly as a {@code HudElement} method reference, same as
 * {@code FrozenBlazeOverlay::render}.
 */
public final class OrderSplash {

    private OrderSplash() {}

    private static String text = "";
    private static float scale = 2.0f;
    private static long showUntilMillis = -1;
    private static long fadeStartMillis = -1;

    private static final long FADE_MILLIS = 400;

    /** Wired to {@link OrderTracker}'s alert. */
    public static void show(String message, float scale, int seconds) {
        text = message;
        OrderSplash.scale = scale;
        long now = System.currentTimeMillis();
        showUntilMillis = now + seconds * 1000L;
        fadeStartMillis = showUntilMillis - FADE_MILLIS;
    }

    public static void render(GuiGraphicsExtractor g, DeltaTracker delta) {
        long now = System.currentTimeMillis();
        if (showUntilMillis < 0 || now > showUntilMillis) return;

        Minecraft mc = Minecraft.getInstance();
        Font font = mc == null ? null : mc.font;
        if (font == null) return;

        float alpha = now < fadeStartMillis ? 1f
                : Math.max(0f, (showUntilMillis - now) / (float) FADE_MILLIS);
        int a = Math.round(alpha * 255) << 24;

        int textWidth = font.width(text);
        int x = Math.round((g.guiWidth() - textWidth * scale) / 2f);
        int y = Math.round(g.guiHeight() * 0.35f);

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
    }
}
