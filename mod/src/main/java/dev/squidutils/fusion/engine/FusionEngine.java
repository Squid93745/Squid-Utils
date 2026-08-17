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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Owns the data, the refresh loop, and the current rankings.
 *
 * <p>All network and scoring work happens on a single daemon thread; the render
 * thread only ever reads the {@code volatile} result lists ({@link #profitVariant},
 * {@link #byXp()}, {@link #recommended()}), each swapped atomically. A
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
    private final Supplier<Scorer.Settings[]> profitVariantSettings;
    private final Supplier<Integer> topN;

    private volatile Brain brain;
    private volatile RouteSolver.Costs routeCosts;
    private volatile RouteSolver.Costs directCosts;
    private volatile double[] buyCosts;
    // Index 0-3, matching FusionCategory's Profit Shards variants 1-4 - one
    // scored+sorted list per table, each under its own configured buy/sell
    // mode. Variant 0 (the default Instabuy/Sell-offer combination) is also
    // the one that feeds history/stability - see record() below.
    private volatile List<List<Scorer.Opportunity>> profitVariants =
            List.of(List.of(), List.of(), List.of(), List.of());
    private volatile List<Scorer.Opportunity> byXp = List.of();
    private volatile List<Recommender.Scored> recommended = List.of();
    private volatile long lastRefresh = 0L;
    private volatile String status = "starting";

    /** Keyed by result tag so a shard's line survives re-ranking. */
    private final java.util.Map<String, Deque<Point>> history = new java.util.HashMap<>();

    public FusionEngine(FusionData data, Path brainPath, Supplier<Scorer.Settings> settings,
                           Supplier<Scorer.Settings[]> profitVariantSettings, Supplier<Integer> topN) {
        this.data = data;
        this.brainPath = brainPath;
        this.settings = settings;
        this.profitVariantSettings = profitVariantSettings;
        this.topN = topN;
        this.brain = Brain.loadOrEmpty(brainPath);
    }

    public static FusionData loadBundled(InputStream in) {
        return FusionData.load(in);
    }

    private Supplier<Integer> refreshSecondsSupplier;

    /**
     * Self-reschedules after every run rather than {@code
     * scheduleWithFixedDelay}, which locks the interval in for good the
     * moment it is called - a real report confirmed this: lowering "Refresh
     * interval" in the settings screen changed nothing, because the already-
     * running scheduled task had no way to notice. Re-reading the supplier
     * before each reschedule means the very next cycle picks up a changed
     * setting instead of needing a restart to take effect.
     */
    public void start(Supplier<Integer> refreshSecondsSupplier) {
        this.refreshSecondsSupplier = refreshSecondsSupplier;
        scheduleNext(0);
    }

    private void scheduleNext(long delaySeconds) {
        worker.schedule(() -> {
            refresh();
            scheduleNext(Math.max(20, refreshSecondsSupplier.get()));
        }, delaySeconds, TimeUnit.SECONDS);
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

            // Cheapest buy-or-fuse route per shard, for multi-step table rows
            // and the "include multi-step routes" tooltip line. Same cost per
            // rescore as one more Scorer.evaluate() pass; cheap next to the
            // order-book work evaluate() already does per recipe.
            Brain brainNow = brain;
            java.util.function.IntToDoubleFunction unitBuyCost = i -> Scorer.unitBuyCost(
                    bazaar.products().get(data.shard(i).tag()), cfg,
                    brainNow.reference(data.shard(i).tag()));
            routeCosts = RouteSolver.solve(data, unitBuyCost);
            // The plain "cheapest fusion" tooltip line, before that toggle -
            // one honest hop, not the recursively-optimal chain above.
            directCosts = RouteSolver.directCheapest(data, unitBuyCost);
            // The shard's own buy price alone, with no fusion mixed in - the
            // "show cheapest price" tooltip mode needs this on its own to
            // compare against a one-hop fusion recipe; routeCosts already
            // folds it in for the recursive case, but discards it once a
            // fusion beats it, so it cannot be recovered from there.
            double[] buy = new double[data.shardCount()];
            for (int i = 0; i < buy.length; i++) buy[i] = unitBuyCost.applyAsDouble(i);
            buyCosts = buy;

            // Profit Shards: up to four independently-configured trading-mode
            // variants, each ranked by margin multiplied by how fast the
            // market absorbs it - a 400k margin on a shard trading twice an
            // hour loses to a 40k margin on one trading five hundred times,
            // and ranking on margin alone hides that entirely. Settings is a
            // record, so two variant slots left on the same buy/sell mode
            // compare equal and share one Scorer.evaluate() call rather than
            // scoring the identical thing twice; the base evaluation above is
            // reused outright when a variant happens to match it exactly.
            Map<Scorer.Settings, List<Scorer.Opportunity>> byVariantSettings = new HashMap<>();
            List<List<Scorer.Opportunity>> variants = new ArrayList<>(4);
            for (Scorer.Settings vs : profitVariantSettings.get()) {
                variants.add(byVariantSettings.computeIfAbsent(vs, key -> {
                    var evaluated = key.equals(cfg) ? all
                            : Scorer.evaluate(data, bazaar.products(), brain, key, hour);
                    var coins = new ArrayList<>(evaluated);
                    coins.sort(Comparator.comparingDouble(
                            (Scorer.Opportunity o) -> o.profit() * o.salesPerHour()).reversed());
                    return Scorer.dedupe(coins, 1, n);
                }));
            }
            profitVariants = variants;

            // XP per fuse only takes five distinct values, so on its own it
            // would leave every legendary tied in arbitrary order. Coins spent
            // per XP breaks those ties the way a grinder would.
            var xp = new ArrayList<>(all);
            xp.sort(Comparator.comparingDouble(Scorer.Opportunity::xpPerFuse).reversed()
                    .thenComparing(Comparator.comparingDouble(Scorer.Opportunity::xpPerCoin).reversed()));
            byXp = Scorer.dedupe(xp, 1, n);

            recommended = Recommender.rank(all, n);

            lastRefresh = System.currentTimeMillis();
            status = all.size() + " viable";

            // Record every shard on show, so a graph line exists for whichever
            // table the player actually has open. Only the first Profit
            // Shards variant feeds this - see the field doc on profitVariants
            // for why the other three do not need their own history.
            List<Scorer.Opportunity> shown = new ArrayList<>(variants.get(0));
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

    /** One of the four Profit Shards tables' scored+sorted rows. {@code index} is
     *  0-3, matching config's variants 1-4. */
    public List<Scorer.Opportunity> profitVariant(int index) { return profitVariants.get(index); }

    /** The exact Settings one of the four Profit Shards tables was scored
     *  under - fetched fresh, not cached, so a caller recomputing something
     *  for that table (a multi-step route's real cost, say) stays under the
     *  same trading assumptions {@link #profitVariant} already used, instead
     *  of silently mixing this table's numbers with the global settings'. */
    public Scorer.Settings variantSettings(int index) { return profitVariantSettings.get()[index]; }
    public List<Scorer.Opportunity> byXp() { return byXp; }
    public List<Recommender.Scored> recommended() { return recommended; }
    public long lastRefresh() { return lastRefresh; }
    /** Hypixel's own {@code lastUpdated} on the bazaar reply, not the local
     *  clock time we happened to poll at - see {@link BazaarClient#lastUpdated()}.
     *  Hypixel's backend only advances this every so often regardless of how
     *  often we ask, so two of our own polls can carry the same value; a
     *  caller that needs to know it is looking at a genuinely new market
     *  snapshot (not the same cached reply read twice) should compare this,
     *  not {@link #lastRefresh()}. */
    public long bazaarSnapshotTime() { return bazaar.lastUpdated(); }
    public String status() { return status; }
    public Brain brain() { return brain; }
    public FusionData data() { return data; }
    public RouteSolver.Costs routeCosts() { return routeCosts; }
    public RouteSolver.Costs directCosts() { return directCosts; }
    public double[] buyCosts() { return buyCosts; }

    /** Live bazaar prices, for callers that need a real order-book sweep at
     *  an arbitrary quantity - the shopping list and fuse order panels, via
     *  {@link Scorer#totalBuyCost}/{@link Scorer#totalSellRevenue} - rather
     *  than the per-fuse quantities {@link #refresh} itself scores. */
    public java.util.Map<String, dev.squidutils.fusion.data.BazaarClient.Product> products() {
        return bazaar.products();
    }

    /** The same settings the last refresh scored everything with - fetched
     *  fresh from the supplier, not cached, so it always matches whatever the
     *  config screen currently says even between refreshes. */
    public Scorer.Settings currentSettings() {
        return settings.get();
    }
    public int pricedProducts() { return bazaar.products().size(); }
}
