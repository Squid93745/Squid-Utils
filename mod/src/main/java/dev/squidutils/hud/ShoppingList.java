package dev.squidutils.hud;

import dev.squidutils.SquidUtils;
import dev.squidutils.fusion.engine.RouteSolver;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The running list built up by clicking "Add to shopping list" on one or more
 * route screens - session-only, like {@link SignFill}, not worth persisting
 * to config.json.
 *
 * <p>Adding the same shard twice sums the quantities, so shopping for several
 * different fusion targets that happen to share a raw input (Azure Shard,
 * say) shows one combined line instead of two you would have to add by hand.
 * Fusion steps are kept the same way, keyed by recipe rather than shard, for
 * the shopping list's own "fuse, in order" view - added routes each arrive
 * already dependency-sorted internally, and a shared recipe across two added
 * routes only needs performing once with both routes' craft counts summed, so
 * concatenating in add-order and merging duplicates is enough without a fresh
 * topological sort across the whole list.
 */
public final class ShoppingList {

    private static final Map<Integer, Integer> ITEMS = new LinkedHashMap<>();
    private static final Map<Integer, Integer> STEP_CRAFTS = new LinkedHashMap<>();

    /** Which recipe directly consumes each queued shard as one of its own
     *  two inputs - see {@link #viaRecipe} for why this exists. */
    private static final Map<Integer, Integer> VIA_RECIPE = new LinkedHashMap<>();

    private ShoppingList() {}

    public record Entry(int shardIndex, int units) {}
    public record StepEntry(int recipeIndex, int crafts) {}

    public static void addRoute(RouteSolver.Route route) {
        var engine = SquidUtils.engine();
        var data = engine != null ? engine.data() : null;

        for (var buy : route.buys()) {
            ITEMS.merge(buy.shardIndex(), buy.units(), Integer::sum);

            // A raw buy was demanded by exactly one step's own inputA/inputB
            // in RouteSolver's own recursive walk - findable again here by
            // checking which step actually names it, rather than having
            // RouteSolver carry the link forward itself. Last route to
            // declare a shard wins if two different added routes both queue
            // it - as good a guess as any once that happens, and no worse
            // than the flat quantity merge above already accepts for the
            // same reason.
            if (data != null) {
                for (var step : route.steps()) {
                    int r = step.recipeIndex();
                    if (data.inputA(r) == buy.shardIndex() || data.inputB(r) == buy.shardIndex()) {
                        VIA_RECIPE.put(buy.shardIndex(), r);
                        break;
                    }
                }
            }
        }
        for (var step : route.steps()) {
            STEP_CRAFTS.merge(step.recipeIndex(), step.crafts(), Integer::sum);
        }
    }

    public static void remove(int shardIndex) {
        ITEMS.remove(shardIndex);
        VIA_RECIPE.remove(shardIndex);
    }

    /**
     * The recipe that directly consumes this shard as a raw input, if the
     * route it was added from is known - lets the batch clamp check the
     * real fusion's profit margin instead of the shard's own price in
     * isolation, which used to mean a shard with deep buy-side liquidity
     * but a thin-margin or thin-selling recipe behind it reported a "safe"
     * quantity far larger than the table's own batch column would ever
     * allow for that same fusion. Null for a shard whose provenance is not
     * known (added some other way, or {@link SquidUtils#engine()} was not
     * up yet) - callers fall back to {@link
     * dev.squidutils.fusion.engine.Scorer#buyDepthLimit} in that case.
     */
    public static Integer viaRecipe(int shardIndex) {
        return VIA_RECIPE.get(shardIndex);
    }

    /** Clamps a line to an exact quantity - the shopping list's "batch"
     *  button uses this to pull an over-budget line back down to the
     *  quantity {@code Scorer.buyDepthLimit} says still stays within the
     *  book's price tolerance, rather than just warning about it. */
    public static void setUnits(int shardIndex, int units) {
        if (units <= 0) {
            ITEMS.remove(shardIndex);
        } else {
            ITEMS.put(shardIndex, units);
        }
    }

    public static void removeStep(int recipeIndex) {
        STEP_CRAFTS.remove(recipeIndex);
    }

    /**
     * A fusion just completed (from {@link dev.squidutils.fusion.SessionTracker}'s
     * chat parsing) - if the list is tracking a step that produces this
     * shard, count one craft of it done, clearing the row once none are left.
     *
     * <p>Only the fuse-order step is touched, never the buy list: a
     * completed fusion consumes shards you already bought or fused, it does
     * not un-buy anything, and this list has no reliable way to tell which
     * specific buy rows fed this particular fusion versus some other queued
     * one that happens to share an input.
     */
    public static void onFusionCompleted(String shardName) {
        var engine = SquidUtils.engine();
        if (engine == null || shardName == null) return;
        var data = engine.data();

        // The chat line names the shard with Hypixel's own " Shard" suffix
        // ("Honeyhog Shard"); FusionData's names never carry it ("Honeyhog") -
        // same mismatch ShardTooltip already strips before comparing.
        String bare = shardName.endsWith(" Shard")
                ? shardName.substring(0, shardName.length() - " Shard".length())
                : shardName;

        int shardIndex = -1;
        for (int i = 0; i < data.shardCount(); i++) {
            if (data.shard(i).name().equalsIgnoreCase(bare)) {
                shardIndex = i;
                break;
            }
        }
        if (shardIndex < 0) return;

        // Found first, mutated after: STEP_CRAFTS.merge() below would throw
        // ConcurrentModificationException if called while still iterating
        // its own keySet.
        Integer matchedRecipe = null;
        for (int recipe : STEP_CRAFTS.keySet()) {
            if (data.result(recipe) == shardIndex) {
                matchedRecipe = recipe;
                break;
            }
        }
        if (matchedRecipe == null) return;

        int remaining = STEP_CRAFTS.merge(matchedRecipe, -1, Integer::sum);
        if (remaining <= 0) STEP_CRAFTS.remove(matchedRecipe);
    }

    public static void clear() {
        ITEMS.clear();
        STEP_CRAFTS.clear();
        VIA_RECIPE.clear();
    }

    public static boolean isEmpty() {
        return ITEMS.isEmpty();
    }

    public static int size() {
        return ITEMS.size();
    }

    public static List<Entry> entries() {
        List<Entry> out = new ArrayList<>(ITEMS.size());
        for (var e : ITEMS.entrySet()) out.add(new Entry(e.getKey(), e.getValue()));
        return out;
    }

    public static boolean hasSteps() {
        return !STEP_CRAFTS.isEmpty();
    }

    public static List<StepEntry> steps() {
        List<StepEntry> out = new ArrayList<>(STEP_CRAFTS.size());
        for (var e : STEP_CRAFTS.entrySet()) out.add(new StepEntry(e.getKey(), e.getValue()));
        return out;
    }
}
