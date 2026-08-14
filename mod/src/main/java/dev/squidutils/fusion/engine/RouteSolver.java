package dev.squidutils.fusion.engine;

import dev.squidutils.fusion.data.FusionData;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntToDoubleFunction;

/**
 * Cheapest way to obtain one unit of each shard: buy it, or fuse it from
 * cheaper inputs, recursively. A bounded Bellman-Ford relaxation over the
 * recipe graph, ported from {@code solve_input_costs} in the Python lab's
 * {@code score.py}.
 *
 * <p>Costs only ever decrease and every fusion still costs something beyond
 * its inputs' value, so a fixed number of rounds settles - matched to the
 * lab's round count so a route the mod finds and the route the lab finds stay
 * comparable.
 */
public final class RouteSolver {

    /** {@code via} value meaning "buy this shard directly", not fuse it. */
    public static final int BUY = -1;

    private static final int ROUNDS = 6;
    /** Same purpose as the lab's own depth cap on plan explanations: a
     *  relaxation snapshot could in principle still contain a cycle, and this
     *  keeps the explainer below from ever recursing into one. */
    private static final int MAX_DEPTH = 8;

    private RouteSolver() {}

    /** {@code cost[i]}: cheapest coins for one unit of shard {@code i}.
     *  {@code via[i]}: the recipe that achieves it, or {@link #BUY}. */
    public record Costs(double[] cost, int[] via) {}

    public static Costs solve(FusionData data, IntToDoubleFunction unitBuyCost) {
        int n = data.shardCount();
        double[] cost = new double[n];
        int[] via = new int[n];
        java.util.Arrays.fill(via, BUY);

        for (int i = 0; i < n; i++) {
            double c = unitBuyCost.applyAsDouble(i);
            cost[i] = c > 0 ? c : Double.POSITIVE_INFINITY;
        }

        for (int round = 0; round < ROUNDS; round++) {
            boolean changed = false;
            for (int r = 0; r < data.recipeCount(); r++) {
                int ai = data.inputA(r), bi = data.inputB(r), ri = data.result(r);
                double ca = cost[ai], cb = cost[bi];
                if (Double.isInfinite(ca) || Double.isInfinite(cb)) continue;

                double unit = (ca * data.shard(ai).fuseAmount() + cb * data.shard(bi).fuseAmount())
                        / data.qty(r);
                if (unit < cost[ri] * 0.999) {
                    cost[ri] = unit;
                    via[ri] = r;
                    changed = true;
                }
            }
            if (!changed) break;
        }
        return new Costs(cost, via);
    }

    // ------------------------------------------------------------------

    /** One fusion to perform, and how many times. */
    public record Step(int recipeIndex, int crafts) {}

    /** One shard to buy off the bazaar, and how many units. */
    public record Buy(int shardIndex, int units) {}

    /** The route to one craft of {@code rootRecipe}: every fusion needed,
     *  dependency-first, plus everything bought raw. */
    public record Route(List<Step> steps, List<Buy> buys, boolean truncated) {}

    /**
     * Expand {@code costs.via()} into the concrete plan for one craft of
     * {@code rootRecipe}.
     *
     * <p>Demand is summed per recipe and per shard across branches rather than
     * aggregated before rounding up to whole fusions, so a shard needed by two
     * different branches of the chain may be bought or crafted a fraction more
     * than the true minimum. That is always the safe direction - the plan
     * still produces enough of everything - and exact aggregation would need a
     * full topological demand pass for a saving that is usually zero, since a
     * cheapest-route chain rarely converges back on a shared intermediate.
     */
    public static Route explain(FusionData data, Costs costs, int rootRecipe) {
        return explain(data, costs, rootRecipe, 1);
    }

    /** As {@link #explain(FusionData, Costs, int)}, but for {@code multiplier}
     *  crafts of the root recipe instead of one - every quantity in the route
     *  scales with it. Used by the route screen's quantity presets; table rows
     *  always want one, which is what the numbers there mean. */
    public static Route explain(FusionData data, Costs costs, int rootRecipe, int multiplier) {
        int m = Math.max(1, multiplier);
        Map<Integer, Integer> recipeCrafts = new LinkedHashMap<>();
        Map<Integer, Integer> shardBuys = new LinkedHashMap<>();
        boolean[] truncated = {false};

        int ai = data.inputA(rootRecipe), bi = data.inputB(rootRecipe);
        if (ai == bi) {
            demand(data, costs, ai, m * (data.shard(ai).fuseAmount() + data.shard(bi).fuseAmount()),
                    0, new HashSet<>(), recipeCrafts, shardBuys, truncated);
        } else {
            demand(data, costs, ai, m * data.shard(ai).fuseAmount(),
                    0, new HashSet<>(), recipeCrafts, shardBuys, truncated);
            demand(data, costs, bi, m * data.shard(bi).fuseAmount(),
                    0, new HashSet<>(), recipeCrafts, shardBuys, truncated);
        }

        // Dependency-first order: walk the same via-tree again, emitting a
        // recipe only the first time it is reached, after its own inputs.
        List<Step> steps = new ArrayList<>();
        Set<Integer> emitted = new HashSet<>();
        order(data, costs, ai, 0, new HashSet<>(), recipeCrafts, emitted, steps);
        if (ai != bi) order(data, costs, bi, 0, new HashSet<>(), recipeCrafts, emitted, steps);
        steps.add(new Step(rootRecipe, m));

        List<Buy> buys = new ArrayList<>();
        for (var e : shardBuys.entrySet()) buys.add(new Buy(e.getKey(), e.getValue()));
        buys.sort(Comparator.comparing(b -> data.shard(b.shardIndex()).name()));

        return new Route(steps, buys, truncated[0]);
    }

    /** Total buy cost of a route, at the per-unit prices {@code costs} solved. */
    public static double routeCost(FusionData data, Costs costs, Route route) {
        double total = 0;
        for (Buy b : route.buys()) total += costs.cost()[b.shardIndex()] * b.units();
        return total;
    }

    private static void demand(FusionData data, Costs costs, int shardIndex, int units,
                               int depth, Set<Integer> path,
                               Map<Integer, Integer> recipeCrafts, Map<Integer, Integer> shardBuys,
                               boolean[] truncated) {
        if (depth > MAX_DEPTH || path.contains(shardIndex)) {
            truncated[0] = true;
            return;
        }
        int recipe = costs.via()[shardIndex];
        if (recipe == BUY) {
            shardBuys.merge(shardIndex, units, Integer::sum);
            return;
        }

        int crafts = ceilDiv(units, data.qty(recipe));
        recipeCrafts.merge(recipe, crafts, Integer::sum);

        Set<Integer> next = new HashSet<>(path);
        next.add(shardIndex);
        int ai = data.inputA(recipe), bi = data.inputB(recipe);
        if (ai == bi) {
            demand(data, costs, ai, crafts * (data.shard(ai).fuseAmount() + data.shard(bi).fuseAmount()),
                    depth + 1, next, recipeCrafts, shardBuys, truncated);
        } else {
            demand(data, costs, ai, crafts * data.shard(ai).fuseAmount(),
                    depth + 1, next, recipeCrafts, shardBuys, truncated);
            demand(data, costs, bi, crafts * data.shard(bi).fuseAmount(),
                    depth + 1, next, recipeCrafts, shardBuys, truncated);
        }
    }

    private static void order(FusionData data, Costs costs, int shardIndex,
                              int depth, Set<Integer> path,
                              Map<Integer, Integer> recipeCrafts, Set<Integer> emitted,
                              List<Step> steps) {
        if (depth > MAX_DEPTH || path.contains(shardIndex)) return;
        int recipe = costs.via()[shardIndex];
        if (recipe == BUY || !emitted.add(recipe)) return;

        Set<Integer> next = new HashSet<>(path);
        next.add(shardIndex);
        int ai = data.inputA(recipe), bi = data.inputB(recipe);
        order(data, costs, ai, depth + 1, next, recipeCrafts, emitted, steps);
        if (ai != bi) order(data, costs, bi, depth + 1, next, recipeCrafts, emitted, steps);

        steps.add(new Step(recipe, recipeCrafts.getOrDefault(recipe, 1)));
    }

    private static int ceilDiv(int units, int qty) {
        return (units + qty - 1) / qty;
    }
}
