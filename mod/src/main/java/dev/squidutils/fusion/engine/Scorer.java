package dev.squidutils.fusion.engine;

import dev.squidutils.fusion.data.BazaarClient.Product;
import dev.squidutils.fusion.data.Brain;
import dev.squidutils.fusion.data.FusionData;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The ranking engine, mirroring the Python lab so both stay comparable.
 *
 * <pre>
 *   fuses_per_hour = capture * min(input A supply, input B supply, output absorption)
 *   coins_per_hour = profit_per_fuse * fuses_per_hour
 *   score          = coins_per_hour * hourFactor ^ hourAlpha
 * </pre>
 *
 * Ranking on margin alone surfaces shards nobody trades, so throughput is part
 * of the objective rather than a filter applied afterwards.
 */
public final class Scorer {

    private static final double HOURS_PER_WEEK = 168.0;
    private static final double TICK = 0.1;

    public enum BuyMode { INSTA_BUY, BUY_ORDER }
    public enum SellMode { SELL_OFFER, INSTA_SELL }

    /** Everything the formula depends on. Mirrors the lab's config.py. */
    public record Settings(
            double tax,
            BuyMode buyMode,
            SellMode sellMode,
            double captureShare,
            double hourAlpha,
            double minProfitPerFuse,
            double maxCostPerFuse,
            long minMovingWeek,
            double maxBookImpact,
            double maxPremiumOverReference,
            int minBookOrders,
            boolean requireReference,
            double maxFillMinutes,
            double queueEfficiency,
            Set<String> inputBlacklist,
            Set<String> outputBlacklist,
            Set<String> rarityFilter,
            /** Rank by hunting XP per hour rather than coins per hour. */
            boolean rankByXp,
            /** Hunting Wisdom, as a percentage bonus to fusion XP. */
            double huntingWisdom,
            /** How far the average profit per fuse may drop from the first
             *  fuse's own, buying/selling deeper into the book, before {@link
             *  Opportunity#depthLimitFuses()} stops counting - 0.10 for "within
             *  10%". */
            double depthLimitThreshold
    ) {
        public static Settings defaults() {
            return new Settings(0.00875, BuyMode.INSTA_BUY, SellMode.SELL_OFFER,
                    0.20, 0.5, 1000, 0, 5000, 0.35, 0.20, 3, true, 30.0, 0.7,
                    Set.of(), Set.of(), Set.of(), false, 0, 0.10);
        }

        /**
         * A copy with a different buy/sell mode, everything else unchanged -
         * lets the Profit Shards variant panels each price the market under
         * their own trading assumption while sharing one tax rate, one set of
         * filters and one Hunting Wisdom value with the rest of the mod,
         * rather than needing four fully independent settings screens.
         */
        public Settings withMode(BuyMode buyMode, SellMode sellMode) {
            return new Settings(tax, buyMode, sellMode, captureShare, hourAlpha,
                    minProfitPerFuse, maxCostPerFuse, minMovingWeek, maxBookImpact,
                    maxPremiumOverReference, minBookOrders, requireReference,
                    maxFillMinutes, queueEfficiency, inputBlacklist, outputBlacklist,
                    rarityFilter, rankByXp, huntingWisdom, depthLimitThreshold);
        }
    }

    public record Opportunity(
            int recipeIndex, String label, String resultTag, String resultName,
            String rarity,
            double cost, double profit, double roi,
            double fusesPerHour, double coinsPerHour, double score,
            String limiter, double limiterVolume, double fillMinutes,
            double capture, boolean measured,
            double xpPerFuse, double xpPerHour, double salesPerHour,
            /** How many consecutive fuses of this recipe you could do at once
             *  before buying/selling deeper into the order book drags the
             *  average profit per fuse down past {@code Settings.depthLimitThreshold}
             *  of the first fuse's own profit - see {@link #depthLimit}. */
            long depthLimitFuses,
            /** Units of the result instabought per hour, always the ask side
             *  regardless of this table's own sell mode - unlike {@link
             *  #salesPerHour}, which follows {@code cfg.sellMode()} and feeds
             *  the Profit Shards ranking itself, this is a fixed "how much
             *  genuine buyer demand exists" figure that reads the same way on
             *  every one of the four Profit Shards tables side by side. */
            double boughtPerHour) {

        /**
         * Hunting XP bought per coin spent - the efficiency that matters when
         * you are grinding levels rather than profit, since it says how far a
         * given budget goes.
         */
        public double xpPerCoin() {
            return cost > 0 ? xpPerFuse / cost : 0;
        }
    }

    /**
     * Base Hunting XP per successful fusion, by rarity of the result.
     *
     * <p>From the SkyBlock wiki's Attribute Fusion page, and corroborated in
     * game: a rare result (base 300) yielded 403 with roughly 34 Hunting Wisdom,
     * which is 300 x 1.343 - consistent with the standard wisdom formula
     * {@code base * (1 + wisdom/100)} applied in {@link #xpPerFuse}.
     */
    public static double huntingXp(String rarity) {
        if (rarity == null) return 0;
        return switch (rarity.toLowerCase(Locale.ROOT)) {
            case "common" -> 75;
            case "uncommon" -> 150;
            case "rare" -> 300;
            case "epic" -> 500;
            case "legendary" -> 1000;
            default -> 0;
        };
    }

    /**
     * Fusion XP actually gained, base scaled by Hunting Wisdom.
     *
     * <p>Wisdom multiplies every fusion equally, so it corrects the absolute
     * numbers without changing which fusion ranks highest.
     */
    public static double xpPerFuse(String rarity, double huntingWisdom) {
        return huntingXp(rarity) * (1.0 + Math.max(0, huntingWisdom) / 100.0);
    }

    private Scorer() {}

    /** Cost to buy one unit, or +infinity if the book cannot fill it. Reuses
     *  the same instabuy/buy-order and phantom-price-guard logic as the
     *  scorer itself, for {@link RouteSolver}. */
    public static double unitBuyCost(Product p, Settings cfg, Brain.Ref ref) {
        if (p == null) return Double.POSITIVE_INFINITY;
        Fill f = buy(p, 1, cfg, ref);
        return f.ok() ? f.total() : Double.POSITIVE_INFINITY;
    }

    /**
     * Real cost to buy exactly {@code units} - the same order-book sweep (or
     * resting-order fill model) {@link #evaluate} already runs for one fuse's
     * worth of inputs, just exposed for an arbitrary bulk quantity instead of
     * hardcoding a recipe's own small per-fuse amount.
     *
     * <p>This is deliberately not {@code unitBuyCost(...) * units}: buying 5
     * units and buying 500 sweep different amounts of book depth, and the
     * average price per unit is never cheaper at the larger quantity. A
     * shopping list total built from the per-unit number understates cost
     * the moment the queued quantity is large enough to matter.
     *
     * @return the real total, or -1 if the book (or settings, e.g. max fill
     *         minutes) cannot fill the amount at all.
     */
    public static double totalBuyCost(Product p, long units, Settings cfg, Brain.Ref ref) {
        if (p == null || units <= 0) return units <= 0 ? 0 : -1;
        Fill f = buy(p, units, cfg, ref);
        return f.ok() ? f.total() : -1;
    }

    /**
     * Real revenue from selling exactly {@code units}, tax already taken out
     * - the bulk-quantity counterpart to {@link #totalBuyCost}, and the same
     * post-tax convention {@link Opportunity#profit()} uses, so a caller
     * never has to remember to apply {@code cfg.tax()} itself.
     *
     * @return the real total, or -1 if it cannot be filled at all.
     */
    public static double totalSellRevenue(Product p, long units, Settings cfg, Brain.Ref ref) {
        if (p == null || units <= 0) return units <= 0 ? 0 : -1;
        Fill f = sell(p, units, cfg, ref);
        return f.ok() ? f.total() * (1.0 - cfg.tax()) : -1;
    }

    /**
     * Largest quantity of one product buyable in a single sweep such that
     * the average price paid stays within {@code cfg.depthLimitThreshold} of
     * the current top-of-book price - {@link #depthLimit}'s question for a
     * single raw purchase instead of a full fusion's paired buy and sell,
     * for a caller like the shopping list that queues raw quantities rather
     * than fuses.
     *
     * <p>Under Buy order there is no sweep curve to exceed at all - a
     * resting order fills at the one price it was placed at, not a
     * depth-dependent average - so this always returns {@link
     * Long#MAX_VALUE} in that mode rather than a number that would only
     * ever falsely suggest a limit exists.
     */
    public static long buyDepthLimit(Product p, Settings cfg, Brain.Ref ref) {
        if (p == null) return 0;
        if (cfg.buyMode() == BuyMode.BUY_ORDER) return Long.MAX_VALUE;
        double top = p.instaBuy();
        if (top <= 0) return 0;
        double ceiling = top * (1.0 + cfg.depthLimitThreshold());

        long lo = 1, hi = 1;
        while (true) {
            long next = hi * 2;
            double cost = totalBuyCost(p, next, cfg, ref);
            if (cost < 0 || cost / next > ceiling) { hi = next; break; }
            lo = next;
            hi = next;
            if (next > 1_000_000) break;
        }
        while (lo < hi) {
            long mid = lo + (hi - lo + 1) / 2;
            double cost = totalBuyCost(p, mid, cfg, ref);
            if (cost >= 0 && cost / mid <= ceiling) lo = mid; else hi = mid - 1;
        }
        return lo;
    }

    /**
     * The largest number of consecutive fuses of one recipe whose average
     * profit per fuse - real order-book depth on every leg, not the first
     * fuse's top-of-book price scaled up - stays within {@code
     * cfg.depthLimitThreshold()} of {@code firstFuseProfit}.
     *
     * <p>Average profit per fuse is monotonically non-increasing as the
     * quantity grows - buying deeper into the ask side or selling deeper
     * into the bid side is never cheaper than the level before it - so an
     * exponential bracket followed by a binary search finds the exact
     * boundary in a handful of book sweeps rather than checking every
     * quantity one at a time.
     */
    private static long depthLimit(Product pa, int ua, Product pb, int ub, boolean sameShard,
                                   Product pr, int oq, Settings cfg,
                                   Brain.Ref refA, Brain.Ref refB, Brain.Ref refR,
                                   double firstFuseProfit) {
        if (firstFuseProfit <= 0) return 1;
        double floor = firstFuseProfit * (1.0 - cfg.depthLimitThreshold());

        long lo = 1, hi = 1;
        while (true) {
            long next = hi * 2;
            Double avg = avgProfitAt(pa, ua, pb, ub, sameShard, pr, oq, cfg, refA, refB, refR, next);
            if (avg == null || avg < floor) { hi = next; break; }
            lo = next;
            hi = next;
            if (next > 1_000_000) break;   // no realistic order book is this deep
        }
        while (lo < hi) {
            long mid = lo + (hi - lo + 1) / 2;
            Double avg = avgProfitAt(pa, ua, pb, ub, sameShard, pr, oq, cfg, refA, refB, refR, mid);
            if (avg != null && avg >= floor) lo = mid; else hi = mid - 1;
        }
        return lo;
    }

    /** Average profit per fuse buying/selling {@code n} fuses' worth at
     *  once, or null if the book cannot fill that much at all. */
    private static Double avgProfitAt(Product pa, int ua, Product pb, int ub, boolean sameShard,
                                      Product pr, int oq, Settings cfg,
                                      Brain.Ref refA, Brain.Ref refB, Brain.Ref refR, long n) {
        double cost;
        if (sameShard) {
            double c = totalBuyCost(pa, n * (long) (ua + ub), cfg, refA);
            if (c < 0) return null;
            cost = c;
        } else {
            double ca = totalBuyCost(pa, n * (long) ua, cfg, refA);
            double cb = totalBuyCost(pb, n * (long) ub, cfg, refB);
            if (ca < 0 || cb < 0) return null;
            cost = ca + cb;
        }
        double revenue = totalSellRevenue(pr, n * (long) oq, cfg, refR);
        if (revenue < 0) return null;
        return (revenue - cost) / n;
    }

    public static List<Opportunity> evaluate(FusionData data,
                                             Map<String, Product> products,
                                             Brain brain, Settings cfg, int utcHour) {
        double hourFactor = Math.pow(brain.hourFactor(utcHour), cfg.hourAlpha());
        List<Opportunity> out = new ArrayList<>();

        for (int r = 0; r < data.recipeCount(); r++) {
            int ai = data.inputA(r), bi = data.inputB(r), ri = data.result(r);
            var sa = data.shard(ai);
            var sb = data.shard(bi);
            var sr = data.shard(ri);

            if (blacklisted(sa, cfg.inputBlacklist()) || blacklisted(sb, cfg.inputBlacklist())
                    || blacklisted(sr, cfg.outputBlacklist())) continue;
            if (!cfg.rarityFilter().isEmpty()
                    && !cfg.rarityFilter().contains(sr.rarity().toLowerCase(Locale.ROOT))) continue;

            Product pa = products.get(sa.tag());
            Product pb = products.get(sb.tag());
            Product pr = products.get(sr.tag());
            if (pa == null || pb == null || pr == null) continue;
            if (pr.buyMovingWeek() < cfg.minMovingWeek()) continue;

            boolean sameShard = ai == bi;
            int ua = sa.fuseAmount(), ub = sb.fuseAmount(), oq = data.qty(r);

            // --- cost ---
            double cost;
            double fillIn;
            if (sameShard) {
                // Tier-up fusions need ua+ub of one shard; two independent
                // sweeps would take the same top of book twice.
                Fill f = buy(pa, ua + ub, cfg, brain.reference(sa.tag()));
                if (!f.ok) continue;
                cost = f.total;
                fillIn = f.minutes;
            } else {
                Fill fa = buy(pa, ua, cfg, brain.reference(sa.tag()));
                Fill fb = buy(pb, ub, cfg, brain.reference(sb.tag()));
                if (!fa.ok || !fb.ok) continue;
                cost = fa.total + fb.total;
                fillIn = Math.max(fa.minutes, fb.minutes);
            }
            if (cost <= 0) continue;
            if (cfg.maxCostPerFuse() > 0 && cost > cfg.maxCostPerFuse()) continue;

            // --- revenue ---
            Fill fs = sell(pr, oq, cfg, brain.reference(sr.tag()));
            if (!fs.ok) continue;
            double revenue = fs.total * (1.0 - cfg.tax());
            double profit = revenue - cost;
            if (profit < cfg.minProfitPerFuse()) continue;

            // --- throughput ---
            // Which side of the book each leg draws on. This decides both which
            // measured flow applies and whether we are queuing or crossing.
            String inSide = cfg.buyMode() == BuyMode.INSTA_BUY ? "ask" : "bid";
            String outSide = cfg.sellMode() == SellMode.SELL_OFFER ? "ask" : "bid";

            Brain.Demand da = brain.demand(sa.tag());
            Brain.Demand db = brain.demand(sb.tag());
            Brain.Demand dr = brain.demand(sr.tag());

            // Measured fills where the collector has seen them, weekly average
            // otherwise; then scaled by demand momentum, matching the lab.
            double srcA = flowOf(da, inSide, pa, cfg) * brain.trend(sa.tag());
            double srcB = flowOf(db, inSide, pb, cfg) * brain.trend(sb.tag());
            double absorb = flowOf(dr, outSide, pr, cfg) * brain.trend(sr.tag());

            double rateA, rateB;
            if (sameShard) {
                rateA = rateB = srcA / (ua + ub);
            } else {
                rateA = srcA / ua;
                rateB = srcB / ub;
            }
            double rateR = absorb / oq;
            double bottleneck = Math.min(rateA, Math.min(rateB, rateR));
            if (bottleneck <= 0) continue;

            String limiter = bottleneck == rateA ? sa.name()
                    : bottleneck == rateB ? sb.name() : sr.name();
            // The limiting shard's own raw flow, in units/hour - rateA/rateB/rateR
            // already divide that down into fusions/hour, which is not what the
            // "estimated volume" of the bottleneck shard means on its own.
            double limiterVolume = bottleneck == rateA ? srcA
                    : bottleneck == rateB ? srcB : absorb;

            // Share of that flow you can actually take. Measured from queue
            // competition when resting an order; the configured assumption only
            // when crossing the spread, where nothing observable reveals how
            // many other players are doing the same.
            double capture = captureOf(dr, da, outSide, inSide, cfg);
            double fusesPerHour = bottleneck * capture;
            double coinsPerHour = profit * fusesPerHour;
            boolean measured = dr != null && da != null
                    && dr.coverage() >= 600 && da.coverage() >= 600;

            // Hunting XP is granted per fusion, not per output shard, so it does
            // not scale with the output quantity.
            double xpPerFuse = xpPerFuse(sr.rarity(), cfg.huntingWisdom());
            double xpPerHour = xpPerFuse * fusesPerHour;

            // Units per hour the result actually trades - the market's raw
            // appetite, before any assumption about your share of it.
            double salesPerHour = flowOf(dr, outSide, pr, cfg);
            // Always the ask side - unlike salesPerHour above, this does not
            // flip with sellMode, so it reads the same way regardless of
            // which of the four Profit Shards tables it is shown on.
            double boughtPerHour = flowOf(dr, "ask", pr, cfg);

            double rank = (cfg.rankByXp() ? xpPerHour : coinsPerHour) * hourFactor;

            long depthLimitFuses = depthLimit(pa, ua, pb, ub, sameShard, pr, oq, cfg,
                    brain.reference(sa.tag()), brain.reference(sb.tag()), brain.reference(sr.tag()),
                    profit);

            out.add(new Opportunity(r, label(sameShard, ua, ub, oq, sa, sb, sr),
                    sr.tag(), sr.name(), sr.rarity(),
                    cost, profit, profit / cost,
                    fusesPerHour, coinsPerHour, rank,
                    limiter, limiterVolume, fillIn + fs.minutes, capture, measured,
                    xpPerFuse, xpPerHour, salesPerHour, depthLimitFuses, boughtPerHour));
        }

        out.sort(Comparator.comparingDouble(Opportunity::score).reversed());
        return out;
    }

    /** Keep the best recipe per output shard; otherwise the top ten is ten
     *  near-identical variants of the same fusion. */
    public static List<Opportunity> dedupe(List<Opportunity> in, int perResult, int limit) {
        Map<String, Integer> seen = new HashMap<>();
        List<Opportunity> out = new ArrayList<>();
        for (Opportunity o : in) {
            int n = seen.getOrDefault(o.resultTag(), 0);
            if (n >= perResult) continue;
            seen.put(o.resultTag(), n + 1);
            out.add(o);
            if (out.size() >= limit) break;
        }
        return out;
    }

    /** Users type shard names; the data keys on ids. Accept either, plus tags. */
    private static boolean blacklisted(FusionData.Shard s, Set<String> list) {
        if (list.isEmpty()) return false;
        return list.contains(s.name().toLowerCase(Locale.ROOT))
                || list.contains(s.id().toLowerCase(Locale.ROOT))
                || list.contains(s.tag().toLowerCase(Locale.ROOT));
    }

    private static String label(boolean same, int ua, int ub, int oq,
                                FusionData.Shard sa, FusionData.Shard sb,
                                FusionData.Shard sr) {
        String tail = " → " + oq + "x " + sr.name();
        if (same) return (ua + ub) + "x " + sa.name() + tail;
        return ua + "x " + sa.name() + " + " + ub + "x " + sb.name() + tail;
    }

    // ------------------------------------------------------------------
    private record Fill(boolean ok, double total, double minutes) {
        static final Fill FAIL = new Fill(false, 0, 0);
    }

    private static double flow(long movingWeek) {
        return Math.max(0.0, movingWeek / HOURS_PER_WEEK);
    }

    /** Units per hour on one side: measured when observed, weekly otherwise. */
    private static double flowOf(Brain.Demand d, String side, Product p, Settings cfg) {
        long mw = "ask".equals(side) ? p.buyMovingWeek() : p.sellMovingWeek();
        return d == null ? flow(mw) : d.flow(side, mw);
    }

    private static double captureOf(Brain.Demand dOut, Brain.Demand dIn,
                                    String outSide, String inSide, Settings cfg) {
        boolean restingOut = cfg.sellMode() == SellMode.SELL_OFFER;
        boolean restingIn = cfg.buyMode() == BuyMode.BUY_ORDER;

        double share = Double.MAX_VALUE;
        if (restingOut && dOut != null && dOut.coverage() >= 600) {
            share = Math.min(share, dOut.queueShare(outSide));
        }
        if (restingIn && dIn != null && dIn.coverage() >= 600) {
            share = Math.min(share, dIn.queueShare(inSide));
        }
        return share == Double.MAX_VALUE ? cfg.captureShare() : share;
    }

    private static Fill buy(Product p, long units, Settings cfg, Brain.Ref ref) {
        if (cfg.requireReference() && ref == null) return Fill.FAIL;

        if (cfg.buyMode() == BuyMode.INSTA_BUY) {
            if (p.asks().isEmpty() || p.askOrders() < cfg.minBookOrders()) return Fill.FAIL;
            double total = p.sweepCost(units);
            if (total < 0) return Fill.FAIL;
            double top = p.instaBuy();
            double worst = total / units;
            if (top > 0 && (worst - top) / top > cfg.maxBookImpact()) return Fill.FAIL;
            return new Fill(true, total, 0);
        }

        if (p.bids().isEmpty()) return Fill.FAIL;
        double price = p.instaSell() + TICK;
        // A best bid far below where the shard actually trades is not a price
        // you get filled at; assume the realistic level, not the hopeful one.
        if (ref != null) price = Math.max(price, ref.bid() * (1.0 - cfg.maxPremiumOverReference()));
        double rate = flow(p.sellMovingWeek()) * cfg.queueEfficiency();
        if (rate <= 0) return Fill.FAIL;
        double minutes = units / rate * 60.0;
        if (minutes > cfg.maxFillMinutes()) return Fill.FAIL;
        return new Fill(true, price * units, minutes);
    }

    private static Fill sell(Product p, long units, Settings cfg, Brain.Ref ref) {
        if (cfg.requireReference() && ref == null) return Fill.FAIL;

        if (cfg.sellMode() == SellMode.INSTA_SELL) {
            if (p.bids().isEmpty()) return Fill.FAIL;
            double total = p.sweepRevenue(units);
            if (total < 0) return Fill.FAIL;
            double top = p.instaSell();
            double worst = total / units;
            if (top > 0 && (top - worst) / top > cfg.maxBookImpact()) return Fill.FAIL;
            return new Fill(true, total, 0);
        }

        if (p.asks().isEmpty() || p.askOrders() < cfg.minBookOrders()) return Fill.FAIL;
        double price = Math.max(TICK, p.instaBuy() - TICK);
        // The phantom-price guard. Undercutting a lone 1,000,000 coin sell
        // offer does not mean anyone buys at 999,999.9.
        if (ref != null) price = Math.min(price, ref.ask() * (1.0 + cfg.maxPremiumOverReference()));
        double rate = flow(p.buyMovingWeek()) * cfg.queueEfficiency();
        if (rate <= 0) return Fill.FAIL;
        double minutes = units / rate * 60.0;
        if (minutes > cfg.maxFillMinutes()) return Fill.FAIL;
        return new Fill(true, price * units, minutes);
    }
}
