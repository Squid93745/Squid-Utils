package dev.squidutils.fusion.hud;

import dev.squidutils.config.SquidUtilsConfig;
import dev.squidutils.config.WidgetPos;
import dev.squidutils.fusion.engine.FusionEngine;
import dev.squidutils.hud.Draw;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Draws the fusion overlays from the engine's existing snapshot.
 *
 * <p>No scoring, no network and no allocation-heavy work happens here - this
 * runs every frame.
 *
 * <p>Two entry points. Normally the HUD calls {@link #extractRenderState}. When
 * a screen is open, that path bows out and the overlay is drawn from a screen
 * background hook instead ({@link #drawUnderScreen}), which puts it beneath the
 * menu rather than on top of it - important at the Fusion Box, where you want
 * to read the panel and use the menu at the same time.
 */
public final class FusionHud implements HudElement {

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

    public FusionHud(FusionEngine engine, Supplier<SquidUtilsConfig> config) {
        this.engine = engine;
        this.config = config;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.screen != null) return;   // handled by drawUnderScreen
        drawAll(g, mc);
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
        SquidUtilsConfig cfg = config.get();
        if (cfg == null || cfg.general.hideInMenus) return;
        drawAll(g, mc);

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

    private void drawAll(GuiGraphicsExtractor g, Minecraft mc) {
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
            bounds.put(which, new int[]{p.x, p.y,
                    Math.round(size[0] * p.scale), Math.round(size[1] * p.scale)});
        }
    }

}
