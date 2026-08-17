package dev.squidutils.fusion.engine;

import dev.squidutils.SquidUtils;
import dev.squidutils.config.SquidUtilsConfig;
import dev.squidutils.fusion.data.CoflnetClient;
import dev.squidutils.fusion.data.FusionData;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Fills the graphs with history the session has not lived through yet.
 *
 * <p>The mod only records data while the game runs, so after a login a four
 * hour window is four hours of blank graph. Coflnet already has the prices;
 * this reconstructs the same metrics from them so the graphs are useful from
 * the first minute.
 *
 * <p>Backfilled points are <em>approximations</em>, and deliberately so. They
 * are computed from top-of-book quotes without order-book depth, the
 * phantom-price guard or measured queue competition, none of which Coflnet's
 * aggregates carry. They are good enough to show the shape of the last few
 * hours; live points, which have all of that, always take precedence where the
 * two overlap.
 */
public final class HistoryBackfill {

    /** Fetching every shard would be hundreds of requests; only what is shown. */
    private static final int MAX_SHARDS = 60;

    /**
     * Always fetch the widest window the slider allows, whatever it is set to.
     *
     * <p>Fetching only the configured window meant a re-fetch every time it was
     * widened, and made backfill useless at the default of 60 minutes: Coflnet
     * runs behind live by a margin that has been measured between 46 and 99
     * minutes, so a 60 minute window can fall entirely inside the gap and
     * legitimately match nothing. Pulling the full four hours once means the
     * data is already there whenever the window is widened.
     */
    private static final int BACKFILL_MINUTES = 240;

    private final FusionEngine engine;
    private final FusionData data;
    private final CoflnetClient cofl = new CoflnetClient();

    private boolean done;
    private boolean warned;

    public HistoryBackfill(FusionEngine engine, FusionData data) {
        this.engine = engine;
        this.data = data;
    }

    /** Once per session is enough; the live collector takes over from there. */
    public boolean needsRun(SquidUtilsConfig cfg) {
        return cfg.fusion.settings.advanced.backfillHistory && !done;
    }

    public void run(SquidUtilsConfig cfg, Scorer.Settings settings) {
        int minutes = BACKFILL_MINUTES;

        List<Scorer.Opportunity> shown = new ArrayList<>(engine.profitVariant(0));
        shown.addAll(engine.byXp());
        for (var r : engine.recommended()) shown.add(r.opportunity());
        if (shown.isEmpty()) return;

        // Every shard the displayed fusions touch: both inputs and the output,
        // since profit cannot be reconstructed without the input prices.
        Set<String> tags = new HashSet<>();
        for (Scorer.Opportunity o : shown) {
            int r = o.recipeIndex();
            tags.add(data.shard(data.inputA(r)).tag());
            tags.add(data.shard(data.inputB(r)).tag());
            tags.add(data.shard(data.result(r)).tag());
            if (tags.size() > MAX_SHARDS) break;
        }

        Instant to = Instant.now();
        Instant from = to.minusSeconds((long) minutes * 60);

        Map<String, List<CoflnetClient.Point>> prices = new HashMap<>(tags.size() * 2);
        for (String tag : tags) {
            var pts = cofl.history(tag, from, to);
            if (!pts.isEmpty()) prices.put(tag, pts);
        }
        if (prices.isEmpty()) {
            // Not necessarily a fault: Coflnet lags live by up to about an hour
            // and a half, so there is genuinely nothing to hand back sometimes.
            // Retry on the next refresh rather than giving up for the session.
            if (!warned) {
                warned = true;
                SquidUtils.LOG.info("[squidutils] backfill found nothing yet "
                        + "(Coflnet runs behind live); will retry");
            }
            return;
        }

        int seeded = 0;
        for (Scorer.Opportunity o : shown) {
            List<FusionEngine.Point> pts = rebuild(o, prices, settings);
            if (!pts.isEmpty()) {
                engine.seedHistory(o.resultTag(), pts);
                seeded++;
            }
        }

        done = true;
        SquidUtils.LOG.info("[squidutils] backfilled {} shards over {} minutes "
                + "from {} price series", seeded, minutes, prices.size());
    }

    /** Recompute this fusion's metrics at each historical timestamp. */
    private List<FusionEngine.Point> rebuild(Scorer.Opportunity o,
                                             Map<String, List<CoflnetClient.Point>> prices,
                                             Scorer.Settings cfg) {
        int r = o.recipeIndex();
        var sa = data.shard(data.inputA(r));
        var sb = data.shard(data.inputB(r));
        var sr = data.shard(data.result(r));
        int qty = data.qty(r);
        boolean same = data.inputA(r) == data.inputB(r);

        var pa = prices.get(sa.tag());
        var pb = prices.get(sb.tag());
        var pr = prices.get(sr.tag());
        if (pa == null || pb == null || pr == null) return List.of();

        double xpPerFuse = Scorer.xpPerFuse(sr.rarity(), cfg.huntingWisdom());
        List<FusionEngine.Point> out = new ArrayList<>(pr.size());

        for (CoflnetClient.Point result : pr) {
            CoflnetClient.Point a = nearest(pa, result.ts());
            CoflnetClient.Point b = nearest(pb, result.ts());
            if (a == null || b == null) continue;

            // Buying costs the ask; a sell offer earns roughly the ask too.
            double cost = same
                    ? a.buy() * (sa.fuseAmount() + sb.fuseAmount())
                    : a.buy() * sa.fuseAmount() + b.buy() * sb.fuseAmount();
            if (cost <= 0) continue;

            double revenue = result.buy() * qty * (1.0 - cfg.tax());
            double profit = revenue - cost;
            double sales = result.buyMovingWeek() / 168.0;

            out.add(new FusionEngine.Point(result.ts(), 0, 0, profit, sales,
                    xpPerFuse, cost > 0 ? xpPerFuse / cost : 0));
        }
        return out;
    }

    /** Nearest sample in time; series are sorted, so a scan is fine at this size. */
    private static CoflnetClient.Point nearest(List<CoflnetClient.Point> pts, long ts) {
        CoflnetClient.Point best = null;
        long bestDelta = Long.MAX_VALUE;
        for (CoflnetClient.Point p : pts) {
            long d = Math.abs(p.ts() - ts);
            if (d < bestDelta) {
                bestDelta = d;
                best = p;
            } else if (p.ts() > ts) {
                break;   // sorted, so it only gets worse from here
            }
        }
        // Refuse to pair samples that are far apart in time.
        return bestDelta <= 900 ? best : null;
    }
}
