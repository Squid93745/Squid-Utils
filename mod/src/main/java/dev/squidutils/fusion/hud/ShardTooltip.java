package dev.squidutils.fusion.hud;

import dev.squidutils.SquidUtils;
import dev.squidutils.fusion.data.FusionData;
import dev.squidutils.fusion.data.NpcPrices;
import dev.squidutils.fusion.engine.RouteSolver;
import dev.squidutils.hud.Draw;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/**
 * Adds a "cheapest fusion" line to a shard's own bazaar tooltip, next to the
 * buy/sell prices Hypixel already lists there - the feature the two toggles
 * in {@code FusionTooltipsCategory} were added for, before either was wired
 * up to anything.
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

        double cheapestKnown = cfg.fusion.tooltips.tooltipMultiStep
                ? addMultiStepLine(data, engine.routeCosts(), idx, lines)
                : addDirectLine(data, engine.directCosts(), idx, lines);

        var npc = NpcPrices.of(data.shard(idx).name());
        if (npc != null && npc.coins() < cheapestKnown) {
            lines.add(Component.literal("Cheaper from NPC: ").withStyle(ChatFormatting.LIGHT_PURPLE)
                    .append(Component.literal(npc.npc() + ": ").withStyle(ChatFormatting.WHITE))
                    .append(Component.literal(Draw.coins(npc.coins())).withStyle(ChatFormatting.GOLD)));
        }
    }

    /** Adds the one-hop tooltip line and returns the per-unit cost it is
     *  based on, or +infinity when {@link #directLine} fell back to "no
     *  data" - {@link RouteSolver#directCheapest} only ever compares fusion
     *  recipes against each other, never against the shard's own buy price,
     *  so a BUY result there really does mean nothing priced was found. */
    private static double addDirectLine(FusionData data, RouteSolver.Costs direct, int shardIndex,
                                        List<Component> lines) {
        lines.add(directLine(data, direct, shardIndex));
        if (direct == null || direct.via()[shardIndex] == RouteSolver.BUY) return Double.POSITIVE_INFINITY;
        return direct.cost()[shardIndex];
    }

    /** Adds the multi-step tooltip line and returns the per-unit cost it is
     *  based on. Unlike {@link #addDirectLine}, {@link RouteSolver#solve}
     *  does compare every fusion against the shard's own buy price, so a BUY
     *  result here usually means "buying already beats every route" - a
     *  real, common answer worth its own line, not the same "no data"
     *  fallback {@link #addDirectLine} uses when it truly has nothing. */
    private static double addMultiStepLine(FusionData data, RouteSolver.Costs routeCosts, int idx,
                                           List<Component> lines) {
        if (routeCosts == null) {
            lines.add(noRouteLine("Cheapest route: "));
            return Double.POSITIVE_INFINITY;
        }
        int via = routeCosts.via()[idx];
        double perUnit = routeCosts.cost()[idx];
        if (via == RouteSolver.BUY) {
            if (!Double.isFinite(perUnit)) {
                lines.add(noRouteLine("Cheapest route: "));
                return Double.POSITIVE_INFINITY;
            }
            lines.add(Component.literal("Cheapest: ").withStyle(ChatFormatting.LIGHT_PURPLE)
                    .append(Component.literal("buy directly").withStyle(ChatFormatting.WHITE))
                    .append(Component.literal(" (" + Draw.coins(perUnit) + ")").withStyle(ChatFormatting.GOLD)));
            return perUnit;
        }
        hoveredRootRecipe = via;
        hoveredAtMillis = System.currentTimeMillis();
        lines.add(multiStepLine(data, routeCosts, via));
        return perUnit;
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
     *  bazaar, exactly as {@link RouteSolver#directCheapest} finds it. */
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
     *  when that beats buying them - what "include multi-step routes" adds
     *  on top of the one-hop line above. {@code via} is already resolved by
     *  the caller, which also needs it to arm the open-route hotkey. */
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
