package dev.squidutils.fusion.engine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Picks fusions that are worth doing and easy to actually execute.
 *
 * <p>Three factors, weighted in this order:
 *
 * <ol>
 *   <li><b>Profit per fuse</b> (50%) — more is better.</li>
 *   <li><b>Time to fill</b> (30%) — faster is better. Nothing wastes a session
 *       like an order that sits unfilled for an hour.</li>
 *   <li><b>Market volume</b> (20%) — busier is better, because a thick market
 *       moves less between deciding and acting.</li>
 * </ol>
 *
 * <p>Note this ranks on profit <em>per fuse</em>, not per hour. That is the
 * point of the table: coins-per-hour rewards grinding a thin margin at volume,
 * whereas someone still learning wants each individual fusion to be clearly
 * worth the trip, to fill quickly, and to sit in a market deep enough that the
 * price does not move while they are deciding.
 *
 * <p>Each factor is scored by percentile within the candidate pool rather than
 * by raw value. Coins, seconds and units per hour share no scale, and a single
 * extreme outlier would otherwise flatten a linear normalisation to nothing.
 */
public final class Recommender {

    public static final double W_PROFIT = 0.50;
    public static final double W_SPEED = 0.30;
    public static final double W_VOLUME = 0.20;

    private Recommender() {}

    /** A scored recommendation, keeping the parts so the UI can explain itself. */
    public record Scored(Scorer.Opportunity opportunity, double score,
                         double profitScore, double speedScore, double volumeScore) {}

    public static List<Scored> rank(List<Scorer.Opportunity> candidates, int limit) {
        if (candidates.isEmpty()) return List.of();

        // Percentile of each opportunity within the pool, per factor.
        Map<Scorer.Opportunity, Double> profit =
                percentile(candidates, Scorer.Opportunity::profit);       // more better
        Map<Scorer.Opportunity, Double> speed =
                percentile(candidates, o -> -fillSeconds(o));             // faster better
        Map<Scorer.Opportunity, Double> volume =
                percentile(candidates, Scorer.Opportunity::salesPerHour); // busier better

        List<Scored> out = new ArrayList<>(candidates.size());
        for (Scorer.Opportunity o : candidates) {
            double p = profit.getOrDefault(o, 0.0);
            double s = speed.getOrDefault(o, 0.0);
            double v = volume.getOrDefault(o, 0.0);
            out.add(new Scored(o, W_PROFIT * p + W_SPEED * s + W_VOLUME * v, p, s, v));
        }

        out.sort(Comparator.comparingDouble(Scored::score).reversed());

        // One entry per output shard, so the shortlist is genuinely varied.
        Map<String, Boolean> seen = new HashMap<>();
        List<Scored> deduped = new ArrayList<>(limit);
        for (Scored sc : out) {
            if (seen.putIfAbsent(sc.opportunity().resultTag(), true) != null) continue;
            deduped.add(sc);
            if (deduped.size() >= limit) break;
        }
        return deduped;
    }

    /** Fill time in seconds; 0 for anything that executes immediately. */
    public static double fillSeconds(Scorer.Opportunity o) {
        return Math.max(0.0, o.fillMinutes() * 60.0);
    }

    /**
     * Map each entry to its percentile rank in [0,1], 1 being best.
     *
     * <p>Ties share the same score, so a hundred fusions that all fill instantly
     * are not arbitrarily ordered against each other.
     */
    private static Map<Scorer.Opportunity, Double> percentile(
            List<Scorer.Opportunity> items,
            java.util.function.ToDoubleFunction<Scorer.Opportunity> metric) {

        List<Scorer.Opportunity> sorted = new ArrayList<>(items);
        sorted.sort(Comparator.comparingDouble(metric));

        Map<Scorer.Opportunity, Double> out = new HashMap<>(items.size() * 2);
        int n = sorted.size();
        int i = 0;
        while (i < n) {
            double value = metric.applyAsDouble(sorted.get(i));
            int j = i;
            while (j + 1 < n && metric.applyAsDouble(sorted.get(j + 1)) == value) j++;
            // Midpoint of the tied block, normalised.
            double pct = n <= 1 ? 1.0 : ((i + j) / 2.0) / (n - 1);
            for (int k = i; k <= j; k++) out.put(sorted.get(k), pct);
            i = j + 1;
        }
        return out;
    }
}
