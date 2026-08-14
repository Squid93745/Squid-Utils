package dev.squidutils.hud;

import dev.squidutils.SquidUtils;
import dev.squidutils.config.SquidUtilsConfig;
import dev.squidutils.config.WidgetPos;
import dev.squidutils.fusion.hud.FusionWidgets;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.EnumMap;
import java.util.Map;

/**
 * Drag the overlays where you want them.
 *
 * <p>Widgets are drawn by exactly the same code the HUD uses, so this is a true
 * preview rather than a stand-in rectangle. Three numeric sliders were a poor
 * way to place something you can see.
 */
public class HudEditorScreen extends Screen {

    private final Map<FusionWidgets.Which, int[]> bounds = new EnumMap<>(FusionWidgets.Which.class);

    private FusionWidgets.Which dragging;
    private int grabDx, grabDy;
    // Tracked from the render pass so key handling knows what is under the
    // cursor without reaching into the mouse handler.
    private int lastMouseX, lastMouseY;

    public HudEditorScreen() {
        super(Component.literal("Squid Utils overlay editor"));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private SquidUtilsConfig cfg() {
        return SquidUtils.config();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
        SquidUtilsConfig cfg = cfg();
        if (cfg == null || minecraft == null) return;

        lastMouseX = mouseX;
        lastMouseY = mouseY;
        g.fill(0, 0, width, height, 0xB0101018);

        // Only what is actually switched on. Showing every panel meant hunting
        // for the one you wanted among a dozen you had deliberately turned off.
        int shown = 0;
        for (FusionWidgets.Which which : FusionWidgets.Which.values()) {
            if (!FusionWidgets.enabled(cfg, which)) {
                bounds.remove(which);
                continue;
            }
            shown++;
            WidgetPos p = FusionWidgets.pos(cfg, which);
            int[] size = FusionWidgets.draw(g, minecraft.font, cfg,
                    SquidUtils.engine(), which, true);

            int w = Math.round(size[0] * p.scale);
            int h = Math.round(size[1] * p.scale);
            bounds.put(which, new int[]{p.x, p.y, w, h});

            boolean hot = which == dragging || contains(p.x, p.y, w, h, mouseX, mouseY);
            // outline() takes (x, y, width, height) - not two corners. Passing
            // corners drew a box running from the panel to the far edge of the
            // screen. The two forms coincide at the origin, which is why the
            // panel borders in Draw.panel looked correct while this did not.
            g.outline(p.x - 1, p.y - 1, w + 2, h + 2, hot ? 0xFFB86BFF : 0x60FFFFFF);

            String label = FusionWidgets.title(which)
                    + String.format("  %.0f%%", p.scale * 100);
            g.text(minecraft.font, label, p.x, p.y - minecraft.font.lineHeight - 2,
                    hot ? 0xFFFFFFFF : 0xFF9A9A9A);
        }

        if (shown == 0) {
            String msg = "Nothing is switched on - enable a table or graph in the settings first";
            g.text(minecraft.font, msg, (width - minecraft.font.width(msg)) / 2,
                    height / 2, 0xFFCCCCCC);
        }

        String[] help = {
                "Drag to move  ·  Scroll to resize",
                "R resets the hovered panel  ·  Esc saves and returns to settings",
        };
        int y = height - (help.length * (minecraft.font.lineHeight + 2)) - 6;
        for (String s : help) {
            g.text(minecraft.font, s, (width - minecraft.font.width(s)) / 2, y, 0xFFCCCCCC);
            y += minecraft.font.lineHeight + 2;
        }
    }

    private static boolean contains(int x, int y, int w, int h, double mx, double my) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    private FusionWidgets.Which hovered(double mx, double my) {
        // Reverse order so the panel drawn last wins an overlap, matching what
        // the eye expects to grab.
        FusionWidgets.Which[] all = FusionWidgets.Which.values();
        for (int i = all.length - 1; i >= 0; i--) {
            int[] b = bounds.get(all[i]);
            if (b != null && contains(b[0], b[1], b[2], b[3], mx, my)) return all[i];
        }
        return null;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        SquidUtilsConfig cfg = cfg();
        if (cfg == null) return super.mouseClicked(event, doubleClick);

        FusionWidgets.Which hit = hovered(event.x(), event.y());
        if (hit == null) return super.mouseClicked(event, doubleClick);

        // No right-click hiding: with only enabled panels drawn, hiding one
        // would make it vanish from the editor with no way to bring it back.
        // Visibility belongs to the settings screen.
        WidgetPos p = FusionWidgets.pos(cfg, hit);
        dragging = hit;
        grabDx = (int) Math.round(event.x()) - p.x;
        grabDy = (int) Math.round(event.y()) - p.y;
        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        SquidUtilsConfig cfg = cfg();
        if (dragging == null || cfg == null) {
            return super.mouseDragged(event, dragX, dragY);
        }
        WidgetPos p = FusionWidgets.pos(cfg, dragging);
        p.x = (int) Math.round(event.x()) - grabDx;
        p.y = (int) Math.round(event.y()) - grabDy;

        int[] b = bounds.get(dragging);
        if (b != null) p.clamp(width, height, b[2], b[3]);
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (dragging != null) {
            dragging = null;
            save();
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        SquidUtilsConfig cfg = cfg();
        FusionWidgets.Which hit = hovered(mouseX, mouseY);
        if (hit == null || cfg == null) {
            return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        }
        WidgetPos p = FusionWidgets.pos(cfg, hit);
        p.scale = Math.max(0.4f, Math.min(3.0f, p.scale + (float) scrollY * 0.05f));
        save();
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        SquidUtilsConfig cfg = cfg();
        if (event.key() == GLFW.GLFW_KEY_R && cfg != null) {
            FusionWidgets.Which hit = hovered(lastMouseX, lastMouseY);
            if (hit != null) {
                WidgetPos p = FusionWidgets.pos(cfg, hit);
                p.scale = 1.0f;
                p.enabled = true;
                save();
                return true;
            }
        }
        return super.keyPressed(event);
    }

    /**
     * Escape returns to the settings screen rather than to the game.
     *
     * <p>The editor is opened from a button in the settings, so closing it
     * should land back where you came from - otherwise adjusting a panel means
     * reopening the whole config to change the next thing.
     */
    @Override
    public void onClose() {
        save();
        var managed = SquidUtils.managedConfig();
        if (managed != null) {
            managed.openConfigGui();
        } else {
            super.onClose();
        }
    }

    private void save() {
        var managed = SquidUtils.managedConfig();
        if (managed != null) managed.saveToFile();
    }
}
