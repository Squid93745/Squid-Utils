package dev.squidutils.fusion.hud;

import dev.squidutils.config.SquidUtilsConfig;
import dev.squidutils.config.WidgetPos;
import dev.squidutils.fusion.engine.FusionEngine;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Draws the fusion overlays from the engine's existing snapshot.
 *
 * <p>No scoring, no network and no allocation-heavy work happens here - this
 * runs every frame.
 *
 * <p>Two entry points, for two different jobs. Normally the HUD calls {@link
 * #extractRenderState} and that draws the panels directly. When a screen is
 * open, panels instead draw from a screen background hook ({@link
 * #drawUnderScreen}), which puts them beneath the menu rather than on top of
 * it - important at the Fusion Box, where you want to read the panel and use
 * the menu at the same time. {@link #visibilityOf} decides, per screen,
 * whether that even happens at all.
 *
 * <p>The hover tooltip is the one exception: it belongs on top of everything,
 * menu included. Drawing it from the same background hook as the panels put
 * it under a container's own foreground (item slots, its own tooltips), which
 * made it look broken. The HUD layer renders above the current screen, so
 * {@link #extractRenderState} draws the tooltip itself whenever a screen is
 * open, using the cursor position {@link #drawUnderScreen} last saw - the HUD
 * callback gets no mouse position of its own, since normal gameplay has no
 * cursor to report.
 */
public final class FusionHud implements HudElement {

    public enum ScreenVisibility { FULL, DIMMED, HIDDEN }

    /**
     * How much of the overlay a given open screen gets.
     *
     * <p>Full while browsing a container - the Fusion Box, the bazaar, an NPC
     * shop, or your own inventory are all indistinguishable from the client's
     * side, since Hypixel's own menus are just more of the same vanilla
     * container protocol {@link AbstractContainerScreen} already covers.
     * Dimmed while chatting, so a glance at prices does not compete for
     * attention with what you are typing - and is not clickable there either,
     * to keep a stray click from landing on a shard name instead of the chat
     * box. Hidden everywhere else: the pause menu, Options, the title screen,
     * this mod's own settings screen, the route screen, and anything else
     * that has no business showing bazaar data underneath it.
     */
    public static ScreenVisibility visibilityOf(Screen screen) {
        if (screen instanceof AbstractContainerScreen<?>) return ScreenVisibility.FULL;
        if (screen instanceof ChatScreen) return ScreenVisibility.DIMMED;
        return ScreenVisibility.HIDDEN;
    }

    private final FusionEngine engine;
    private final Supplier<SquidUtilsConfig> config;

    /**
     * Panel bounds measured last frame, in {x, y, w, h}.
     *
     * <p>Connectors must be drawn before the panels so they pass underneath,
     * but a panel's size is only known once drawn. Using the previous frame's
     * measurements resolves that; sizes change only when the data or scale
     * does, and one frame of lag is invisible.
     */
    private final Map<FusionWidgets.Which, int[]> bounds =
            new EnumMap<>(FusionWidgets.Which.class);

    private volatile int screenMouseX;
    private volatile int screenMouseY;

    public FusionHud(FusionEngine engine, Supplier<SquidUtilsConfig> config) {
        this.engine = engine;
        this.config = config;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        if (mc.screen != null) {
            drawTooltip(g, mc, screenMouseX, screenMouseY);
            return;
        }
        drawAll(g, mc, false);
    }

    /**
     * Called from the screen background hook, so panels sit under the menu.
     *
     * <p>This is also the only place the shard names can be interactive: a HUD
     * overlay has no cursor during normal play, so clicking and hovering only
     * make sense while some screen is open.
     */
    public void drawUnderScreen(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        screenMouseX = mouseX;
        screenMouseY = mouseY;

        ScreenVisibility vis = visibilityOf(mc.screen);
        if (vis == ScreenVisibility.HIDDEN) {
            FusionWidgets.clearHits();
            return;
        }

        SquidUtilsConfig cfg = config.get();
        if (cfg == null || cfg.general.hideInMenus) {
            FusionWidgets.clearHits();
            return;
        }
        drawAll(g, mc, vis == ScreenVisibility.DIMMED);
    }

    private void drawTooltip(GuiGraphicsExtractor g, Minecraft mc, int mouseX, int mouseY) {
        SquidUtilsConfig cfg = config.get();
        if (cfg == null || cfg.general.hideInMenus || !cfg.general.showHud) return;
        if (visibilityOf(mc.screen) != ScreenVisibility.FULL) return;

        String shard = FusionWidgets.shardAt(mouseX, mouseY);
        if (shard != null) {
            tooltip(g, mc, mouseX, mouseY, "Click to view " + shard + " on the bazaar");
        }
    }

    private static void tooltip(GuiGraphicsExtractor g, Minecraft mc,
                                int x, int y, String text) {
        int w = mc.font.width(text) + 8;
        int h = mc.font.lineHeight + 6;
        int tx = Math.min(x + 10, mc.getWindow().getGuiScaledWidth() - w - 2);
        int ty = Math.max(2, y - h - 2);
        g.fill(tx, ty, tx + w, ty + h, 0xF0100010);
        g.outline(tx, ty, w, h, 0xFFB86BFF);
        g.text(mc.font, text, tx + 4, ty + 3, 0xFFFFFFFF);
    }

    private void drawAll(GuiGraphicsExtractor g, Minecraft mc, boolean dimmed) {
        SquidUtilsConfig cfg = config.get();
        if (cfg == null || !cfg.general.showHud || !cfg.fusion.general.enabled) return;
        Font font = mc.font;
        if (font == null) return;

        FusionWidgets.clearHits();
        for (FusionWidgets.Which which : FusionWidgets.Which.values()) {
            if (!FusionWidgets.enabled(cfg, which)) {
                bounds.remove(which);
                continue;
            }
            int[] size = FusionWidgets.draw(g, font, cfg, engine, which, false);
            WidgetPos p = FusionWidgets.pos(cfg, which);
            int w = Math.round(size[0] * p.scale);
            int h = Math.round(size[1] * p.scale);
            bounds.put(which, new int[]{p.x, p.y, w, h});
            // A translucent veil over the whole panel, rather than a lower-alpha
            // redraw: cheap, and it fades every part of the panel by the same
            // amount regardless of what colour it started as.
            if (dimmed) g.fill(p.x, p.y, p.x + w, p.y + h, 0x90000000);
        }
    }

}
