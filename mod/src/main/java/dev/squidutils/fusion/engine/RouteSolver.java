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

    /**
     * @param pureReptileChance the player's current Pure Reptile chance
     *        (0.0-0.20, see {@code Scorer.Settings#pureReptileChance}) - a
     *        Reptile-eligible recipe's expected output is {@code qty *
     *        (1 + pureReptileChance)} rather than a flat {@code qty}, so its
     *        effective per-unit cost is lower than the naive division by
     *        {@code qty} alone would say. Without this, a route through
     *        several Reptile-family tiers (Python into King Cobra into
     *        Basilisk, say) can lose to a route that never procs at all,
     *        purely because the comparison ignored real, player-measured
     *        upside one of them has and the other does not.
     */
    public static Costs solve(FusionData data, IntToDoubleFunction unitBuyCost, double pureReptileChance) {
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

                double qty = expectedQty(data, r, pureReptileChance);
                double unit = (ca * data.shard(ai).fuseAmount() + cb * data.shard(bi).fuseAmount()) / qty;
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

    /** {@code data.qty(r)}, scaled up for a Reptile-eligible recipe's own
     *  chance to double its output - the expected yield {@link #solve} and
     *  {@link #directCheapest} both price a craft's cost against. */
    private static double expectedQty(FusionData data, int r, double pureReptileChance) {
        double qty = data.qty(r);
        if (pureReptileChance <= 0) return qty;
        int ai = data.inputA(r), bi = data.inputB(r);
        return Scorer.reptileEligible(data.shard(ai).tag(), data.shard(bi).tag())
                ? qty * (1.0 + pureReptileChance) : qty;
    }

    /**
     * The cheapest single recipe for each shard, buying both inputs straight
     * off the bazaar - no recursive fusing of the inputs, unlike {@link
     * #solve}. This is what the "cheapest fusion" tooltip line means before
     * the "include multi-step routes" toggle is also on: one honest hop, not
     * the full recursively-optimal chain.
     */
    public static Costs directCheapest(FusionData data, IntToDoubleFunction unitBuyCost, double pureReptileChance) {
        int n = data.shardCount();
        double[] buyCost = new double[n];
        for (int i = 0; i < n; i++) {
            double c = unitBuyCost.applyAsDouble(i);
            buyCost[i] = c > 0 ? c : Double.POSITIVE_INFINITY;
        }

        double[] bestCost = new double[n];
        int[] bestRecipe = new int[n];
        java.util.Arrays.fill(bestCost, Double.POSITIVE_INFINITY);
        java.util.Arrays.fill(bestRecipe, BUY);

        for (int r = 0; r < data.recipeCount(); r++) {
            int ai = data.inputA(r), bi = data.inputB(r), ri = data.result(r);
            double ca = buyCost[ai], cb = buyCost[bi];
            if (Double.isInfinite(ca) || Double.isInfinite(cb)) continue;

            double qty = expectedQty(data, r, pureReptileChance);
            double unit = (ca * data.shard(ai).fuseAmount() + cb * data.shard(bi).fuseAmount()) / qty;
            if (unit < bestCost[ri]) {
                bestCost[ri] = unit;
                bestRecipe[ri] = r;
            }
        }
        return new Costs(bestCost, bestRecipe);
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

    /**
     * The expected coin value of every Reptile-eligible fusion in {@code
     * route} over-delivering its nominal output - what {@link #routeCost}
     * itself has no way to reflect, since it only sums {@code route.buys()}
     * at each raw leaf's own price, which already assumes the nominal
     * (non-doubled) output of every fused intermediate tier along the way.
     * A real report: fusing toward Basilisk produced noticeably more profit
     * than the mod projected, from Pure Reptile repeatedly proccing on the
     * Python and King Cobra tiers feeding it, not just a chance on the final
     * Basilisk fuse itself - subtracting this from {@link #routeCost} (or
     * adding it to profit) is meant to close exactly that gap.
     *
     * <p>Each bonus unit is valued at {@code costs.cost()[]} - the cheapest
     * way {@link #solve} already found to acquire one unit of that shard -
     * rather than its live sell price. The bonus substitutes for buying or
     * fusing that unit again further up the chain at least as often as it
     * gets sold outright once the route is done, and acquisition cost is
     * never higher than sale price for any route worth taking in the first
     * place, so this is the conservative side of the two to assume -
     * understating the credit rather than promising more than the bonus
     * reliably delivers, the same direction {@code Scorer.evaluate}'s own
     * Pure Reptile handling already leans.
     *
     * <p>Deliberately does not touch how many units {@link #explain} plans
     * to buy or how many crafts of each step it lists - those stay the safe,
     * guaranteed-sufficient plan regardless of luck. Only the coin figure
     * changes, the same way a table row's profit column is a projection and
     * its "batch" column is a hard limit, not the same kind of number.
     *
     * <p>Includes the route's own root recipe (its very last step) along with
     * every intermediate one - correct for a caller pricing pure acquisition
     * cost with no separate revenue term of its own (the route screen, the
     * tooltip's cost line). A caller that already prices the root recipe's
     * bonus through elevated sale revenue instead must use {@link
     * #reptileCreditExcludingRoot} - see its own doc for why.
     */
    public static double reptileCredit(FusionData data, Costs costs, Route route, double pureReptileChance) {
        return reptileCredit(data, costs, route.steps(), pureReptileChance);
    }

    /**
     * As {@link #reptileCredit(FusionData, Costs, Route, double)}, but over
     * every step except the very last - which {@link #explain} always
     * appends as the route's own root recipe (see its source: {@code
     * steps.add(new Step(rootRecipe, m))} runs after every dependency is
     * already emitted). A caller that separately prices the root recipe's
     * own Pure Reptile bonus through elevated sale revenue (any single-fuse
     * {@code Scorer.Opportunity}, e.g. the table row {@code
     * FusionWidgets.rowData} recomputes cost for) must use this instead of
     * the full version - crediting the root step a second time on the cost
     * side would double the exact same bonus output up: once through
     * revenue, once through cost.
     */
    public static double reptileCreditExcludingRoot(FusionData data, Costs costs, Route route,
                                                     double pureReptileChance) {
        List<Step> steps = route.steps();
        if (steps.isEmpty()) return 0;
        return reptileCredit(data, costs, steps.subList(0, steps.size() - 1), pureReptileChance);
    }

    private static double reptileCredit(FusionData data, Costs costs, List<Step> steps, double pureReptileChance) {
        if (pureReptileChance <= 0) return 0;
        double credit = 0;
        for (Step step : steps) {
            int r = step.recipeIndex();
            int ai = data.inputA(r), bi = data.inputB(r), ri = data.result(r);
            if (!Scorer.reptileEligible(data.shard(ai).tag(), data.shard(bi).tag())) continue;
            double bonusUnits = step.crafts() * data.qty(r) * pureReptileChance;
            credit += bonusUnits * costs.cost()[ri];
        }
        return credit;
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
