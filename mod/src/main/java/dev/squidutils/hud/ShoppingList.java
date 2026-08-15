package dev.squidutils.hud;

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

    private ShoppingList() {}

    public record Entry(int shardIndex, int units) {}
    public record StepEntry(int recipeIndex, int crafts) {}

    public static void addRoute(RouteSolver.Route route) {
        for (var buy : route.buys()) {
            ITEMS.merge(buy.shardIndex(), buy.units(), Integer::sum);
        }
        for (var step : route.steps()) {
            STEP_CRAFTS.merge(step.recipeIndex(), step.crafts(), Integer::sum);
        }
    }

    public static void remove(int shardIndex) {
        ITEMS.remove(shardIndex);
    }

    public static void clear() {
        ITEMS.clear();
        STEP_CRAFTS.clear();
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
