package dev.squidutils.hud;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.List;
import java.util.Locale;

/** Small drawing helpers shared by every overlay. */
public final class Draw {

    public static final int BG = 0x90000000;
    public static final int BORDER = 0x40FFFFFF;
    public static final int TITLE = 0xFFB86BFF;
    public static final int DIM = 0xFF9A9A9A;
    public static final int AXIS = 0x50FFFFFF;
    public static final int GRID = 0x22FFFFFF;

    /** Colour-matched between the list numbers and the graph lines. */
    public static final int[] SERIES = {
            0xFF7FD4FF, 0xFF55FF55, 0xFFFFB020, 0xFFB86BFF, 0xFFFF6B8A,
            0xFF6BE8C8, 0xFFE8E86B, 0xFFFF9E5E, 0xFF9E9EFF, 0xFFFF6BE8,
    };

    // Semantic colours for the numbers in a table row. Each kind of figure keeps
    // the same colour everywhere, so a row can be read at a glance without
    // parsing the labels.
    public static final int C_COST = 0xFFFF9E5E;   // what you pay
    public static final int C_PROFIT = 0xFF55FF55; // what you make
    public static final int C_ROI = 0xFF7FD4FF;    // return on that
    public static final int C_XP = 0xFFE8E86B;     // hunting xp
    public static final int C_FILL = 0xFFFFB020;   // time to fill
    public static final int C_VOLUME = 0xFF9E9EFF; // market volume
    public static final int C_STABLE = 0xFFB86BFF; // steadiness
    public static final int C_FIT = 0xFFFF6BE8;    // recommendation score

    private Draw() {}

    /** Rarity tint, matching the colours SkyBlock itself uses. */
    public static int rarity(String rarity) {
        if (rarity == null) return 0xFFAAAAAA;
        return switch (rarity.toLowerCase(Locale.ROOT)) {
            case "common" -> 0xFFFFFFFF;
            case "uncommon" -> 0xFF55FF55;
            case "rare" -> 0xFF5555FF;
            case "epic" -> 0xFFAA00AA;
            case "legendary" -> 0xFFFFAA00;
            default -> 0xFFAAAAAA;
        };
    }

    /** Coins in the shorthand players actually read. */
    public static String coins(double n) {
        double a = Math.abs(n);
        if (a >= 1e9) return String.format("%.2fb", n / 1e9);
        if (a >= 1e6) return String.format("%.2fm", n / 1e6);
        if (a >= 1e3) return String.format("%.1fk", n / 1e3);
        return String.format("%.0f", n);
    }

    public static String units(double n) {
        double a = Math.abs(n);
        if (a >= 1e6) return String.format("%.1fm", n / 1e6);
        if (a >= 1e3) return String.format("%.1fk", n / 1e3);
        return String.format("%.0f", n);
    }

    public static void panel(GuiGraphicsExtractor g, int w, int h) {
        panel(g, w, h, BORDER);
    }

    /**
     * A panel with a tinted border.
     *
     * <p>Used to group a table with its graphs: the same colour on all of them
     * says which belong together at a glance, without lines crossing the screen
     * between them.
     */
    public static void panel(GuiGraphicsExtractor g, int w, int h, int border) {
        g.fill(0, 0, w, h, BG);
        g.outline(0, 0, w, h, border);
    }

    /** Border tint per table group, matching the accordion they live under. */
    public static int groupColour(int table) {
        return switch (table) {
            case 1 -> 0xC055FF55;   // profit per fuse
            case 2 -> 0xC0E8E86B;   // xp per fuse
            default -> 0xC0B86BFF;  // recommended
        };
    }

    /**
     * A curved connector between two points, drawn as a cubic Bezier.
     *
     * <p>The control points sit horizontally out from each end, which gives the
     * S-curve node editors use: it leaves and arrives perpendicular to the panel
     * edge, so the eye follows it without confusing it for a table border.
     */
    public static void curve(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1,
                             int colour) {
        int bow = Math.max(14, Math.abs(x1 - x0) / 2);
        int cx0 = x0 + (x1 >= x0 ? bow : -bow);
        int cx1 = x1 - (x1 >= x0 ? bow : -bow);

        int steps = Math.max(8, Math.min(48, (Math.abs(x1 - x0) + Math.abs(y1 - y0)) / 6));
        int px = x0, py = y0;
        for (int i = 1; i <= steps; i++) {
            double t = (double) i / steps;
            double u = 1 - t;
            double bx = u * u * u * x0 + 3 * u * u * t * cx0 + 3 * u * t * t * cx1 + t * t * t * x1;
            double by = u * u * u * y0 + 3 * u * u * t * y0 + 3 * u * t * t * y1 + t * t * t * y1;
            int nx = (int) Math.round(bx), ny = (int) Math.round(by);
            line(g, px, py, nx, ny, colour);
            px = nx;
            py = ny;
        }
    }

    /** Bresenham, because fill() rectangles are the only primitive available. */
    public static void line(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int colour) {
        int dx = Math.abs(x1 - x0), dy = Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1, sy = y0 < y1 ? 1 : -1;
        int err = dx - dy;
        int guard = 0;
        while (guard++ < 4096) {
            g.fill(x0, y0, x0 + 1, y0 + 1, colour);
            if (x0 == x1 && y0 == y1) break;
            int e2 = err << 1;
            if (e2 > -dy) { err -= dy; x0 += sx; }
            if (e2 < dx)  { err += dx; y0 += sy; }
        }
    }


    /**
     * Plot several time series into a box, with labelled axes.
     *
     * <p>All series share one vertical scale on purpose: the point is to compare
     * shards against each other, which a per-series scale would hide.
     *
     * @param values  one array of {timestamp, value} pairs per series
     * @param colours one colour per series
     * @param yFormat how to render an axis value
     */
    /**
     * @param minTs,maxTs the time span the horizontal axis covers. Passed in
     *        rather than derived from the data so the axis always represents the
     *        configured window: with a 60 minute window and 10 minutes of
     *        history, the line occupies the right sixth instead of stretching to
     *        fill the panel and making the setting look inert.
     */
    public static void plot(GuiGraphicsExtractor g, Font font,
                            int x, int y, int w, int h,
                            List<double[][]> values, int[] colours,
                            java.util.function.DoubleFunction<String> yFormat,
                            String emptyMessage, double minTs, double maxTs) {
        g.fill(x, y, x + w, y + h, 0x50000000);

        double maxV = 0;
        int usable = 0;
        for (double[][] s : values) {
            if (s.length >= 2) usable++;
            for (double[] p : s) {
                maxV = Math.max(maxV, p[1]);
            }
        }

        if (usable == 0 || maxV <= 0 || maxTs <= minTs) {
            g.text(font, emptyMessage, x + 4, y + h / 2 - font.lineHeight / 2, DIM);
            return;
        }

        // Leave room on the left for value labels and below for time labels.
        int padL = font.width(yFormat.apply(maxV)) + 5;
        int padB = font.lineHeight + 2;
        int px0 = x + padL, py0 = y + 2;
        int pw = w - padL - 4, ph = h - padB - 4;
        if (pw < 10 || ph < 10) return;

        // Horizontal guides at 0, half and full scale.
        for (int i = 0; i <= 2; i++) {
            int gy = py0 + ph - (int) (ph * (i / 2.0));
            g.fill(px0, gy, px0 + pw, gy + 1, i == 0 ? AXIS : GRID);
            String lbl = yFormat.apply(maxV * i / 2.0);
            g.text(font, lbl, x + padL - 4 - font.width(lbl), gy - font.lineHeight / 2, DIM);
        }

        // Time axis: the full configured window, oldest left, now right.
        long span = (long) (maxTs - minTs);
        String left = span >= 90 ? (span / 60) + "m window" : span + "s window";
        g.text(font, left, px0, y + h - font.lineHeight - 1, DIM);
        String right = "now";
        g.text(font, right, x + w - 3 - font.width(right), y + h - font.lineHeight - 1, DIM);

        for (int i = 0; i < values.size(); i++) {
            double[][] s = values.get(i);
            if (s.length < 2) continue;
            int colour = colours[i % colours.length];
            int lx = -1, ly = -1;
            for (double[] p : s) {
                // Clamp so a sample older than the window cannot draw outside.
                double t = Math.max(minTs, Math.min(maxTs, p[0]));
                int cx = px0 + (int) ((t - minTs) * (pw - 1) / (maxTs - minTs));
                int cy = py0 + ph - 1 - (int) (Math.min(1.0, p[1] / maxV) * (ph - 2));
                if (lx >= 0) line(g, lx, ly, cx, cy, colour);
                lx = cx;
                ly = cy;
            }
        }
    }
}
