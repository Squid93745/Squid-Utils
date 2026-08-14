package dev.squidutils.config;

import com.google.gson.annotations.Expose;

/**
 * Where one overlay widget sits, and how big it is.
 *
 * <p>Persisted but deliberately given no {@code @ConfigOption}, so it saves and
 * loads without appearing in the settings list. Three numeric sliders are a
 * miserable way to place something on screen; the drag editor sets these
 * instead.
 */
public class WidgetPos {

    @Expose public int x;
    @Expose public int y;
    @Expose public float scale = 1.0f;
    @Expose public boolean enabled = true;

    public WidgetPos() {}

    public WidgetPos(int x, int y, float scale) {
        this.x = x;
        this.y = y;
        this.scale = scale;
    }

    /** Keep a widget from being dragged entirely off screen and lost. */
    public void clamp(int screenW, int screenH, int widgetW, int widgetH) {
        int margin = 12;
        x = Math.max(-widgetW + margin, Math.min(screenW - margin, x));
        y = Math.max(-widgetH + margin, Math.min(screenH - margin, y));
    }
}
