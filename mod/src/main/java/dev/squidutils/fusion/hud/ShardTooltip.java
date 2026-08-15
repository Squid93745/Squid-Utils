package dev.squidutils.fusion.hud;

import dev.squidutils.SquidUtils;
import dev.squidutils.fusion.data.FusionData;
import dev.squidutils.fusion.data.NpcPrices;
import dev.squidutils.fusion.engine.FusionEngine;
import dev.squidutils.fusion.engine.RouteSolver;
import dev.squidutils.hud.Draw;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/**
 * Adds a "cheapest" line to a shard's own bazaar tooltip, next to the
 * buy/sell prices Hypixel already lists there - the feature the toggles in
 * {@code FusionTooltipsCategory} were added for, before any were wired up to
 * anything.
 *
 * <p>Deliberately separate from the panel-hover tooltip {@link FusionHud}
 * draws: that one only exists while a screen with one of this mod's own
 * panels is open, and fires when the cursor is over a shard's <em>name as
 * text drawn by this mod</em>. This one fires whenever the game itself is
 * about to render a tooltip for an actual item in a slot - inventory,
 * bazaar, anywhere - which is a completely different hook
 * ({@link net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback}, not
 * anything screen-specific.
 *
 * <p>Also remembers the last shard shown with a multi-step route, briefly,
 * so the "open route hotkey" in {@code SquidUtils}'s tick handler knows what
 * to open - {@link #currentHoverRoute()}. Tooltip rendering only fires while
 * actively hovering an item, so there is no explicit "stopped hovering"
 * event to clear it on; a short staleness window does the same job.
 */
public final class ShardTooltip {

    private static final String SUFFIX = " Shard";
    private static final long HOVER_TIMEOUT_MILLIS = 400;

    private static volatile int hoveredRootRecipe = -1;
    private static volatile long hoveredAtMillis;

    private ShardTooltip() {}

    public static void append(ItemStack stack, Item.TooltipContext ctx, TooltipFlag flag, List<Component> lines) {
        var cfg = SquidUtils.config();
        if (cfg == null || !cfg.fusion.tooltips.tooltipCheapest) return;

        String name = stack.getHoverName().getString();
        if (!name.endsWith(SUFFIX)) return;
        String bare = name.substring(0, name.length() - SUFFIX.length());

        var engine = SquidUtils.engine();
        if (engine == null) return;
        FusionData data = engine.data();

        int idx = -1;
        for (int i = 0; i < data.shardCount(); i++) {
            if (data.shard(i).name().equalsIgnoreCase(bare)) {
                idx = i;
                break;
            }
        }
        if (idx < 0) return;

        boolean multiStep = cfg.fusion.tooltips.tooltipMultiStep;
        lines.add(cfg.fusion.tooltips.tooltipCheapestPrice
                ? cheapestPriceLine(data, engine, idx, multiStep)
                : cheapestFuseLine(data, engine, idx, multiStep));
    }

    /**
     * "Show cheapest price" ON (the default): whichever is actually cheapest
     * for this shard - buying it outright (bazaar, or a known NPC) or fusing
     * it - not just a fusion recipe that turns out to cost more than buying
     * the result would have.
     */
    private static Component cheapestPriceLine(FusionData data, FusionEngine engine, int idx, boolean multiStep) {
        double fuseCost = Double.POSITIVE_INFINITY;
        Component fuseLine = null;
        int fuseVia = RouteSolver.BUY;
        boolean fuseIsRoute = false;

        if (multiStep) {
            var routeCosts = engine.routeCosts();
            if (routeCosts != null) {
                int via = routeCosts.via()[idx];
                double cost = routeCosts.cost()[idx];
                if (via != RouteSolver.BUY) {
                    // solve() already folds the buy price into cost/via -
                    // BUY only loses here when some fusion genuinely beats
                    // it, so this is already the cheaper of the two.
                    fuseCost = cost;
                    fuseLine = multiStepLine(data, routeCosts, via);
                    fuseVia = via;
                    fuseIsRoute = true;
                } else if (Double.isFinite(cost)) {
                    fuseCost = cost;
                    fuseLine = buyDirectLine(cost);
                }
            }
        } else {
            var direct = engine.directCosts();
            double[] buyCosts = engine.buyCosts();
            double buyCost = buyCosts != null && idx < buyCosts.length ? buyCosts[idx] : Double.POSITIVE_INFINITY;
            int recipe = direct == null ? RouteSolver.BUY : direct.via()[idx];
            // directCheapest() never compares against the buy price (unlike
            // solve() above), so that comparison has to happen here instead.
            if (recipe != RouteSolver.BUY && direct.cost()[idx] <= buyCost) {
                fuseCost = direct.cost()[idx];
                fuseLine = Component.literal("Cheapest fusion: ").withStyle(ChatFormatting.LIGHT_PURPLE)
                        .append(fuseText(data, recipe))
                        .append(Component.literal(" (" + Draw.coins(fuseCost) + ")").withStyle(ChatFormatting.GOLD));
            } else if (Double.isFinite(buyCost)) {
                fuseCost = buyCost;
                fuseLine = buyDirectLine(buyCost);
            }
        }

        var npc = NpcPrices.of(data.shard(idx).name());
        if (npc != null && npc.coins() < fuseCost) return npcLine(npc);

        if (fuseIsRoute) {
            hoveredRootRecipe = fuseVia;
            hoveredAtMillis = System.currentTimeMillis();
        }
        return fuseLine != null ? fuseLine : noRouteLine("Cheapest: ");
    }

    /**
     * "Show cheapest price" OFF: always describes a fusion recipe, even when
     * buying the shard outright would come out cheaper - the tooltip's
     * original behaviour, for whoever specifically wants the recipe.
     */
    private static Component cheapestFuseLine(FusionData data, FusionEngine engine, int idx, boolean multiStep) {
        if (multiStep) {
            var routeCosts = engine.routeCosts();
            // solve() only keeps a route once it beats buying, so once via
            // is BUY there is no "second-best fusion, ignoring the buy
            // price" left to recover - falling back to the one-hop recipe
            // below is the only option.
            if (routeCosts != null && routeCosts.via()[idx] != RouteSolver.BUY) {
                int via = routeCosts.via()[idx];
                hoveredRootRecipe = via;
                hoveredAtMillis = System.currentTimeMillis();
                return multiStepLine(data, routeCosts, via);
            }
        }
        return directLine(data, engine.directCosts(), idx);
    }

    private static Component buyDirectLine(double cost) {
        return Component.literal("Cheapest: ").withStyle(ChatFormatting.LIGHT_PURPLE)
                .append(Component.literal("buy directly").withStyle(ChatFormatting.WHITE))
                .append(Component.literal(" (" + Draw.coins(cost) + ")").withStyle(ChatFormatting.GOLD));
    }

    private static Component npcLine(NpcPrices.Entry npc) {
        return Component.literal("Cheapest: ").withStyle(ChatFormatting.LIGHT_PURPLE)
                .append(Component.literal("NPC (" + npc.npc() + ")").withStyle(ChatFormatting.WHITE))
                .append(Component.literal(" (" + Draw.coins(npc.coins()) + ")").withStyle(ChatFormatting.GOLD));
    }

    /** Every shard gets a line, even when no priced route is available right
     *  now (a stale or missing bazaar quote somewhere in its recipe tree,
     *  which {@code CLAUDE.md} already documents as routine) - matching the
     *  house rule that a missing number says so rather than just vanishing,
     *  the same as a table row falling back to "?" instead of an empty cell. */
    private static Component noRouteLine(String label) {
        return Component.literal(label).withStyle(ChatFormatting.LIGHT_PURPLE)
                .append(Component.literal("— (no priced route right now)").withStyle(ChatFormatting.GRAY));
    }

    /** The root recipe of whatever multi-step route a tooltip showed within
     *  the last moment, or -1 if nothing fresh is being hovered. */
    public static int currentHoverRoute() {
        if (System.currentTimeMillis() - hoveredAtMillis > HOVER_TIMEOUT_MILLIS) return -1;
        return hoveredRootRecipe;
    }

    /** One hop: the cheapest recipe buying both inputs straight off the
     *  bazaar, exactly as {@link RouteSolver#directCheapest} finds it - never
     *  compared against the shard's own buy price, unlike the "cheapest
     *  price" mode above. */
    private static Component directLine(FusionData data, RouteSolver.Costs direct, int shardIndex) {
        if (direct == null) return noRouteLine("Cheapest fusion: ");
        int recipe = direct.via()[shardIndex];
        if (recipe == RouteSolver.BUY) return noRouteLine("Cheapest fusion: ");

        return Component.literal("Cheapest fusion: ").withStyle(ChatFormatting.LIGHT_PURPLE)
                .append(fuseText(data, recipe))
                .append(Component.literal(" (" + Draw.coins(direct.cost()[shardIndex]) + ")")
                        .withStyle(ChatFormatting.GOLD));
    }

    /** The recursively cheapest full route, which may fuse the inputs too
     *  when that comes out cheaper than buying them. {@code via} is already
     *  resolved by the caller, which also needs it to arm the open-route
     *  hotkey. */
    private static Component multiStepLine(FusionData data, RouteSolver.Costs routeCosts, int via) {
        var route = RouteSolver.explain(data, routeCosts, via);
        double cost = RouteSolver.routeCost(data, routeCosts, route);
        int steps = route.steps().size();

        if (steps <= 1) {
            return Component.literal("Cheapest fusion: ").withStyle(ChatFormatting.LIGHT_PURPLE)
                    .append(fuseText(data, via))
                    .append(Component.literal(" (" + Draw.coins(cost) + ")").withStyle(ChatFormatting.GOLD));
        }
        return Component.literal("Cheapest route: ").withStyle(ChatFormatting.LIGHT_PURPLE)
                .append(Component.literal(steps + " steps").withStyle(ChatFormatting.WHITE))
                .append(Component.literal(" (" + Draw.coins(cost) + ")").withStyle(ChatFormatting.GOLD));
    }

    private static Component fuseText(FusionData data, int recipe) {
        var sa = data.shard(data.inputA(recipe));
        var sb = data.shard(data.inputB(recipe));
        boolean same = data.inputA(recipe) == data.inputB(recipe);
        String text = same
                ? (sa.fuseAmount() + sb.fuseAmount()) + "x " + sa.name()
                : sa.fuseAmount() + "x " + sa.name() + " + " + sb.fuseAmount() + "x " + sb.name();
        return Component.literal(text).withStyle(ChatFormatting.WHITE);
    }
}
