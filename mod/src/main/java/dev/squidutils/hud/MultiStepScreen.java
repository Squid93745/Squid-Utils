package dev.squidutils.hud;

import dev.squidutils.SquidUtils;
import dev.squidutils.fusion.data.FusionData;
import dev.squidutils.fusion.engine.RouteSolver;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * The full chain behind one multi-step table row: everything bought raw and
 * every fusion needed, dependency-first, with clickable shard names exactly
 * like the overlay panels use.
 *
 * <p>The route is recomputed on every render rather than cached at open time,
 * the same way every other screen in the mod reads live state. That is cheap
 * here: {@link RouteSolver#explain} only walks the one small subtree this
 * route touches, not the full recipe graph.
 */
public class MultiStepScreen extends Screen {

    private record Hit(int x, int y, int w, int h, String shard) {
        boolean contains(double mx, double my) {
            return mx >= x && mx <= x + w && my >= y && my <= y + h;
        }
    }

    private final Screen previous;
    private final int rootRecipe;
    private final List<Hit> hits = new ArrayList<>();
    private int scroll = 0;
    private int contentHeight = 0;

    public MultiStepScreen(Screen previous, int rootRecipe) {
        super(Component.literal("Fusion route"));
        this.previous = previous;
        this.rootRecipe = rootRecipe;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
        g.fill(0, 0, width, height, 0xE0101018);
        hits.clear();

        var engine = SquidUtils.engine();
        var costs = engine == null ? null : engine.routeCosts();
        if (engine == null || costs == null) {
            String msg = "Still calculating - try again in a moment";
            g.text(font, msg, (width - font.width(msg)) / 2, height / 2, Draw.DIM);
            return;
        }

        FusionData data = engine.data();
        var route = RouteSolver.explain(data, costs, rootRecipe);
        var result = data.shard(data.result(rootRecipe));
        int qty = data.qty(rootRecipe);
        int lineH = font.lineHeight + 3;

        int y = 20 - scroll;
        drawCentered(g, "Route to " + qty + "x " + result.name(), y, Draw.TITLE);
        y += lineH + 6;

        if (!route.buys().isEmpty()) {
            g.text(font, "Buy off the bazaar", 20, y, Draw.TITLE);
            y += lineH;
            for (var buy : route.buys()) {
                var s = data.shard(buy.shardIndex());
                double cost = costs.cost()[buy.shardIndex()] * buy.units();
                int x = 20;
                x = plain(g, x, y, buy.units() + "x ");
                x = name(g, x, y, s.name());
                g.text(font, "  (" + Draw.coins(cost) + ")", x, y, Draw.C_COST);
                y += lineH;
            }
            y += 6;
        }

        g.text(font, "Fuse, in order", 20, y, Draw.TITLE);
        y += lineH;
        for (var step : route.steps()) {
            int r = step.recipeIndex();
            var sa = data.shard(data.inputA(r));
            var sb = data.shard(data.inputB(r));
            var sr = data.shard(data.result(r));
            boolean same = data.inputA(r) == data.inputB(r);

            int x = 20;
            if (step.crafts() > 1) {
                String prefix = step.crafts() + "×  ";
                g.text(font, prefix, x, y, Draw.DIM);
                x += font.width(prefix);
            }
            if (same) {
                x = plain(g, x, y, (sa.fuseAmount() + sb.fuseAmount()) + "x ");
                x = name(g, x, y, sa.name());
            } else {
                x = plain(g, x, y, sa.fuseAmount() + "x ");
                x = name(g, x, y, sa.name());
                x = plain(g, x, y, " + " + sb.fuseAmount() + "x ");
                x = name(g, x, y, sb.name());
            }
            x = plain(g, x, y, " → " + data.qty(r) + "x ");
            name(g, x, y, sr.name());
            y += lineH;
        }
        y += 10;

        double total = RouteSolver.routeCost(data, costs, route);
        drawCentered(g, "Total cost for one route: " + Draw.coins(total), y, Draw.C_COST);
        y += lineH;
        if (route.truncated()) {
            drawCentered(g, "(route runs deep - showing as much as fits safely)", y, Draw.DIM);
            y += lineH;
        }

        contentHeight = y + scroll - 20;

        String help = "Click a name to view it on the bazaar  ·  Esc to close";
        g.text(font, help, (width - font.width(help)) / 2, height - font.lineHeight - 6, Draw.DIM);
    }

    private void drawCentered(GuiGraphicsExtractor g, String text, int y, int colour) {
        g.text(font, text, (width - font.width(text)) / 2, y, colour);
    }

    private int plain(GuiGraphicsExtractor g, int x, int y, String s) {
        g.text(font, s, x, y, 0xFFFFFFFF);
        return x + font.width(s);
    }

    private int name(GuiGraphicsExtractor g, int x, int y, String shard) {
        g.text(font, shard, x, y, 0xFF7FD4FF);
        int w = font.width(shard);
        hits.add(new Hit(x, y, w, font.lineHeight, shard));
        return x + w;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        for (Hit h : hits) {
            if (h.contains(event.x(), event.y())) {
                SquidUtils.openBazaar(h.shard());
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int maxScroll = Math.max(0, contentHeight - (height - 40));
        scroll = Math.max(0, Math.min(maxScroll, scroll - (int) (scrollY * 16)));
        return true;
    }

    /** Closing returns to whatever menu this was opened over, matching the
     *  overlay editor - landing at the desktop would mean reopening the whole
     *  menu just to look at a different row. */
    @Override
    public void onClose() {
        if (minecraft != null) minecraft.setScreen(previous);
    }
}
