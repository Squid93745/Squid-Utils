package dev.squidutils.fusion.engine;

import dev.squidutils.fusion.data.BazaarClient;
import dev.squidutils.fusion.data.Brain;
import dev.squidutils.fusion.data.FusionData;

import java.io.InputStream;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Owns the data, the refresh loop, and the current ranking.
 *
 * <p>All network and scoring work happens on a single daemon thread; the render
 * thread only ever reads {@link #current()}, which is swapped atomically. A
 * 135,000-recipe rescore has no business happening during a frame.
 */
public final class FusionEngine {

    /** One sample of a fusion, for the history graphs and the stability rating. */
    public record Point(long epochSeconds, double coinsPerHour, double xpPerHour,
                        double profit, double salesPerHour,
                        double xpPerFuse, double xpPerCoin) {}

    // Four hours at a 60s refresh, plus room for backfilled points which arrive
    // at Coflnet's finer spacing.
    private static final int HISTORY_LIMIT = 600;

    private final BazaarClient bazaar = new BazaarClient();
    private final ScheduledExecutorService worker =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "squidutils-refresh");
                t.setDaemon(true);
                return t;
            });

    private final FusionData data;
    private final Path brainPath;
    private final Supplier<Scorer.Settings> settings;
    private final Supplier<Integer> topN;

    private volatile Brain brain;
    private volatile RouteSolver.Costs routeCosts;
    private volatile List<Scorer.Opportunity> current = List.of();
    private volatile List<Scorer.Opportunity> byCoins = List.of();
    private volatile List<Scorer.Opportunity> byXp = List.of();
    private volatile List<Recommender.Scored> recommended = List.of();
    private volatile long lastRefresh = 0L;
    private volatile String status = "starting";

    /** Keyed by result tag so a shard's line survives re-ranking. */
    private final java.util.Map<String, Deque<Point>> history = new java.util.HashMap<>();

    public FusionEngine(FusionData data, Path brainPath,
                           Supplier<Scorer.Settings> settings, Supplier<Integer> topN) {
        this.data = data;
        this.brainPath = brainPath;
        this.settings = settings;
        this.topN = topN;
        this.brain = Brain.loadOrEmpty(brainPath);
    }

    public static FusionData loadBundled(InputStream in) {
        return FusionData.load(in);
    }

    public void start(int intervalSeconds) {
        worker.scheduleWithFixedDelay(this::refresh, 0,
                Math.max(20, intervalSeconds), TimeUnit.SECONDS);
    }

    public void stop() {
        worker.shutdownNow();
    }

    /** Force a refresh now, off-thread. */
    public void refreshSoon() {
        worker.execute(this::refresh);
    }

    private void refresh() {
        try {
            // Re-read the brain each cycle so a re-tune from the Python lab
            // lands without restarting the game.
            Brain fresh = Brain.loadOrEmpty(brainPath);
            if (fresh.hasReferences() || !brain.hasReferences()) {
                brain = fresh;
            }

            Set<String> tags = new HashSet<>();
            for (var s : data.shards()) tags.add(s.tag());

            if (!bazaar.refresh(tags)) {
                status = "bazaar error: " + bazaar.lastError();
                return;
            }

            int hour = ZonedDateTime.now(ZoneOffset.UTC).getHour();
            Scorer.Settings cfg = settings.get();
            var all = Scorer.evaluate(data, bazaar.products(), brain, cfg, hour);
            int n = Math.max(1, topN.get());

            // Cheapest buy-or-fuse route per shard, for multi-step table rows.
            // Same cost per rescore as one more Scorer.evaluate() pass; cheap
            // next to the order-book work evaluate() already does per recipe.
            Brain brainNow = brain;
            routeCosts = RouteSolver.solve(data, i -> Scorer.unitBuyCost(
                    bazaar.products().get(data.shard(i).tag()), cfg,
                    brainNow.reference(data.shard(i).tag())));

            // Three independent views over one evaluation. Scoring 135k recipes
            // three times would be wasteful; re-sorting the result is trivial.
            // Both tables rank per fuse, not per hour: the question they answer
            // is "which single fusion should I do", not "what maximises a grind".
            // Profit Shards: margin multiplied by how fast the market absorbs
            // it. A 400k margin on a shard trading twice an hour loses to a 40k
            // margin on one trading five hundred times, and ranking on margin
            // alone hides that entirely.
            var coins = new ArrayList<>(all);
            coins.sort(Comparator.comparingDouble(
                    (Scorer.Opportunity o) -> o.profit() * o.salesPerHour()).reversed());
            byCoins = Scorer.dedupe(coins, 1, n);

            // XP per fuse only takes five distinct values, so on its own it
            // would leave every legendary tied in arbitrary order. Coins spent
            // per XP breaks those ties the way a grinder would.
            var xp = new ArrayList<>(all);
            xp.sort(Comparator.comparingDouble(Scorer.Opportunity::xpPerFuse).reversed()
                    .thenComparing(Comparator.comparingDouble(Scorer.Opportunity::xpPerCoin).reversed()));
            byXp = Scorer.dedupe(xp, 1, n);

            recommended = Recommender.rank(all, n);

            current = byCoins;
            lastRefresh = System.currentTimeMillis();
            status = all.size() + " viable";

            // Record every shard on show, so a graph line exists for whichever
            // table the player actually has open.
            List<Scorer.Opportunity> shown = new ArrayList<>(byCoins);
            shown.addAll(byXp);
            for (var r : recommended) shown.add(r.opportunity());
            record(shown);

            if (backfill != null) backfill.run();
        } catch (Exception e) {
            status = "error: " + e.getClass().getSimpleName();
        }
    }

    private synchronized void record(List<Scorer.Opportunity> top) {
        long now = System.currentTimeMillis() / 1000;
        Set<String> live = new HashSet<>();
        for (var o : top) {
            live.add(o.resultTag());
            Deque<Point> q = history.computeIfAbsent(o.resultTag(), k -> new ArrayDeque<>());
            q.addLast(new Point(now, o.coinsPerHour(), o.xpPerHour(),
                    o.profit(), o.salesPerHour(), o.xpPerFuse(), o.xpPerCoin()));
            while (q.size() > HISTORY_LIMIT) q.removeFirst();
        }
        // Let shards that have dropped off the list decay out rather than
        // vanishing, so the graph keeps its shape.
        history.keySet().removeIf(tag -> {
            if (live.contains(tag)) return false;
            Deque<Point> q = history.get(tag);
            return q.isEmpty() || now - q.getLast().epochSeconds() > 1800;
        });
    }

    public synchronized List<Point> historyFor(String resultTag) {
        Deque<Point> q = history.get(resultTag);
        return q == null ? List.of() : new ArrayList<>(q);
    }

    /**
     * Merge backfilled history in, without displacing anything measured live.
     *
     * <p>Live samples carry order-book depth and measured competition that a
     * reconstruction cannot, so where the two cover the same second the live
     * one wins.
     */
    public synchronized void seedHistory(String resultTag, List<Point> seed) {
        if (seed == null || seed.isEmpty()) return;
        Deque<Point> q = history.computeIfAbsent(resultTag, k -> new ArrayDeque<>());

        java.util.TreeMap<Long, Point> merged = new java.util.TreeMap<>();
        for (Point p : seed) merged.put(p.epochSeconds(), p);
        for (Point p : q) merged.put(p.epochSeconds(), p);   // live overwrites

        q.clear();
        for (Point p : merged.values()) {
            q.addLast(p);
            if (q.size() > HISTORY_LIMIT) q.removeFirst();
        }
    }

    /** History trimmed to the last {@code minutes}, for the graphs. */
    public synchronized List<Point> historyFor(String resultTag, int minutes) {
        Deque<Point> q = history.get(resultTag);
        if (q == null) return List.of();
        long cutoff = System.currentTimeMillis() / 1000 - (long) minutes * 60;
        List<Point> out = new ArrayList<>();
        for (Point p : q) {
            if (p.epochSeconds() >= cutoff) out.add(p);
        }
        return out;
    }

    /**
     * How steady a fusion's profit has been, from 0 to 1.
     *
     * <p>One minus the coefficient of variation of profit per fuse across the
     * samples we have. A shard whose margin swings 40% between refreshes scores
     * around 0.6, and that is worth knowing before you commit capital: a high
     * coins-per-hour built on a number that keeps moving is a number that may
     * not survive the time it takes you to act on it.
     *
     * <p>Returns -1 when there are too few samples to say anything, so callers
     * can distinguish "unstable" from "not yet known".
     */
    public synchronized double stabilityFor(String resultTag) {
        Deque<Point> q = history.get(resultTag);
        if (q == null || q.size() < 3) return -1;

        double sum = 0;
        for (Point p : q) sum += p.profit();
        double mean = sum / q.size();
        if (mean <= 0) return -1;

        double var = 0;
        for (Point p : q) {
            double d = p.profit() - mean;
            var += d * d;
        }
        double cv = Math.sqrt(var / q.size()) / mean;
        return Math.max(0.0, Math.min(1.0, 1.0 - cv));
    }

    /** Optional hook run after each refresh, used for the launch backfill. */
    private volatile Runnable backfill;

    public void setBackfill(Runnable backfill) { this.backfill = backfill; }

    public List<Scorer.Opportunity> current() { return current; }
    public List<Scorer.Opportunity> byCoins() { return byCoins; }
    public List<Scorer.Opportunity> byXp() { return byXp; }
    public List<Recommender.Scored> recommended() { return recommended; }
    public long lastRefresh() { return lastRefresh; }
    public String status() { return status; }
    public Brain brain() { return brain; }
    public FusionData data() { return data; }
    public RouteSolver.Costs routeCosts() { return routeCosts; }
    public int pricedProducts() { return bazaar.products().size(); }
}
