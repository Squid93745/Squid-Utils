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

    /**
     * Every shard in the Reptile family (its Lizard/Serpent/Turtle/Croco/
     * Scaled sub-families included) - the set of inputs that can trigger the
     * Pure Reptile Attribute's chance to double a fusion's output, per the
     * SkyBlock wiki's Attribute Fusion page. Hardcoded rather than sourced
     * from {@code fusion.json}, which does not carry a family field at all
     * (only {@code fuseAmount}, and that alone cannot tell Reptile apart
     * from Amphibian or Elemental - all three get the same fuse_amount=2
     * discount). Family membership is essentially static game content, so a
     * fixed set here is far less to maintain than a data-pipeline change for
     * one feature; {@link #REPTILE_TAGS} is exactly the shard list the raw
     * lab data tags with a "Reptile" family, Chameleon included even though
     * its own fuse_amount is 1, since the wiki explicitly calls out Chameleon
     * Shards as still counting.
     */
    private static final Set<String> REPTILE_TAGS = Set.of(
            "SHARD_NEWT", "SHARD_SALAMANDER", "SHARD_CUBOA", "SHARD_VIPER",
            "SHARD_WATER_SNAKE", "SHARD_TEWTIL", "SHARD_LIZARD_KING", "SHARD_PYTHON",
            "SHARD_CROCODILE", "SHARD_KING_COBRA", "SHARD_GECKO", "SHARD_CHUCKWALLA",
            "SHARD_LEVIATHAN", "SHARD_ALLIGATOR", "SHARD_BASILISK", "SHARD_IGUANA",
            "SHARD_KOMODO_DRAGON", "SHARD_SHELLWISE", "SHARD_CAIMAN", "SHARD_LEATHERBACK",
            "SHARD_CHAMELEON", "SHARD_NESSIE", "SHARD_TIAMAT", "SHARD_WYVERN",
            "SHARD_TORTOISE", "SHARD_MEGALITH", "SHARD_QUEEN_SNAKE", "SHARD_TITANOBOA");

    /**
     * Whether a recipe's own <em>result</em> is a "Reptile Fusion" - the
     * SkyBlock wiki's own exact phrase for what Pure Reptile actually keys
     * off ("Grants a +2%-20% chance to double shards from a Reptile
     * Fusion"), not "any fusion that happens to consume a Reptile-family
     * input". A first version of this method checked the two input tags
     * instead - it looked plausible (Pure Reptile's own trigger text talks
     * about "using" a Reptile shard) and even matched one real report
     * correctly (Python into King Cobra into Basilisk, where the result is
     * Reptile at every tier anyway) - but a second report caught it
     * overreaching: Hideonfloor (Shulker Family) + Shellwise (Reptile and
     * Turtle Family) fusing into Hideonring (Shulker Family) priced in the
     * full bonus purely because Shellwise happened to be one of the two
     * inputs, projecting real, substantial profit for a fusion the player
     * found barely broke even. The data backs "result family" as the actual
     * rule, not "either input": King Cobra's own real recipe includes Python
     * (Reptile) + Quake (Elemental Family) as valid inputs, and that pairing
     * is exactly the kind of tier the class doc above already cites as a
     * confirmed Pure Reptile proc - a non-Reptile input alongside a Reptile
     * one is fine there specifically because the <em>result</em>, King
     * Cobra, is still Reptile-family. Hideonring is not. Public so {@link
     * RouteSolver} can apply the same check to every tier of a multi-step
     * route, not just the single fuse {@link #evaluate} scores directly -
     * Pure Reptile can still proc on any Reptile-result tier along a chain,
     * not only the one producing the chain's own final shard.
     */
    public static boolean reptileEligible(String resultTag) {
        return REPTILE_TAGS.contains(resultTag);
    }

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
            double depthLimitThreshold,
            /**
             * The player's current chance (0.0-0.20) for the Pure Reptile
             * Attribute to double a fuse's output, for recipes with a
             * Reptile-family input - a plain {@code double} for the same
             * reason {@code huntingWisdom} is: {@link Scorer} touches no
             * Minecraft API so it can run headless against the Python lab
             * (see {@code check_parity.ps1}), so the live value has to be
             * read by a caller that CAN touch the game (see {@code
             * AttributeDetector}) and handed in here, not read from inside
             * this class.
             */
            double pureReptileChance,
            /**
             * An alternative floor for {@link #depthLimit}, in coins per
             * unit rather than a percentage of profit: how far past its own
             * current top-of-book price a leg's average may drift before
             * that leg itself is considered the problem. {@link
             * #depthLimit} takes whichever of the two floors allows more,
             * since a percentage-of-profit floor alone can be dominated by
             * whichever leg has the worst relative liquidity, capping a
             * cheap, deep-liquidity leg's own usable quantity down to match
             * even though that leg's own price barely moved - see {@link
             * #depthLimit}'s own doc for the real report this came from.
             */
            double depthLimitFlatTolerance
    ) {
        public static Settings defaults() {
            return new Settings(0.00875, BuyMode.INSTA_BUY, SellMode.SELL_OFFER,
                    0.20, 0.5, 1000, 0, 5000, 0.35, 0.20, 3, true, 30.0, 0.7,
                    Set.of(), Set.of(), Set.of(), false, 0, 0.10, 0, 5.0);
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
                    rarityFilter, rankByXp, huntingWisdom, depthLimitThreshold, pureReptileChance,
                    depthLimitFlatTolerance);
        }
    }

    public record Opportunity(
            int recipeIndex, String label, String resultTag, String resultName,
            String rarity,
            double cost, double profit, double roi,
            double fusesPerHour, double coinsPerHour, double score,
            /** The shard whose own order-book depth is actually what caps
             *  {@link #depthLimitFuses} - buying/selling it any further past
             *  that point is what pushes average profit per fuse below the
             *  {@code Settings.depthLimitThreshold} floor. Paired with
             *  {@link #limiterImpact}, this answers "why is batch only
             *  N?" - see {@link #depthLimit}. */
            String limiter,
            /** How far past its own top-of-book price this leg had moved by
             *  the {@link #depthLimitFuses} boundary, as a fraction (0.083
             *  for "8.3%") - always positive regardless of whether {@link
             *  #limiter} names a buy leg (price rose) or the sell leg (price
             *  fell), since either way it is the magnitude that matters. */
            double limiterImpact, double fillMinutes,
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
     * the current top-of-book price, with no idea whether the fusion it
     * feeds is even still profitable at that point - unlike {@link
     * #depthLimit}, which is the real question a batch limit is supposed to
     * answer. This is the fallback for when no such recipe is actually
     * known (see {@link #depthLimitForRecipe}), not the first choice - a
     * shopping list line whose route is known should always prefer that
     * instead, the same way {@link dev.squidutils.hud.MultiStepScreen}'s own
     * "Max" preset does.
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

    /** Which side of a fusion actually caps its batch size - see {@link
     *  #depthLimit}. */
    public enum Leg { A, B, R }

    /** {@code fuses}: {@link #depthLimit}'s own answer. {@code leg}/{@code
     *  impact}: which side is responsible and by how much - see {@link
     *  Opportunity#limiter}/{@link Opportunity#limiterImpact}, which this
     *  feeds directly. */
    public record DepthResult(long fuses, Leg leg, double impact) {}

    /**
     * The largest number of consecutive fuses of one recipe that either of
     * two floors still allows, whichever is more generous: {@code
     * cfg.depthLimitThreshold()} of {@code firstFuseProfit} (average profit
     * per fuse, real order-book depth on every leg, not the first fuse's
     * top-of-book price scaled up), or {@code cfg.depthLimitFlatTolerance()}
     * coins per unit on every individual leg's own price. This is the one
     * formula every "batch" figure in the mod is meant to share - the
     * shopping list's clamp button, the route screen's Max preset, and this
     * table column all answer the same question: how far can trading go
     * before it stops being worth it.
     *
     * <p>The percentage-of-profit floor alone has a real failure mode: it
     * can be dominated by whichever leg has the worst relative liquidity,
     * which caps every leg's usable quantity down to match - including a
     * cheap, deep-liquidity leg whose own price barely moved at all. A real
     * report: 700x of a ~3,085-coin shard flagged over budget at 610x,
     * despite its own order book sitting flat (a ~2 coin spread) across
     * thousands of units - a different leg entirely was the true
     * constraint. The flat floor answers the more direct question a player
     * actually has about any one leg - "has this shard's own price moved by
     * a sane amount" - so taking whichever of the two allows more means a
     * technicality on one leg's relative percentage no longer strangles a
     * different leg that is plainly fine in absolute terms.
     *
     * <p>Both floors are monotonic in quantity - buying deeper into the ask
     * side or selling deeper into the bid side is never cheaper than the
     * level before it - so an exponential bracket followed by a binary
     * search finds each exact boundary in a handful of book sweeps rather
     * than checking every quantity one at a time.
     *
     * <p>Once the final boundary is found, {@link #attributeLeg} answers
     * *why* it landed there: whichever leg's own price has drifted furthest
     * (in coins) from what it would have cost at the first fuse's own rate,
     * scaled up honestly, is the one actually eating the margin - regardless
     * of which of the two floors produced the winning boundary.
     */
    public static DepthResult depthLimit(Product pa, int ua, Product pb, int ub, boolean sameShard,
                                         Product pr, int oq, Settings cfg,
                                         Brain.Ref refA, Brain.Ref refB, Brain.Ref refR,
                                         double firstFuseProfit) {
        long byFlat = findBoundary(n -> flatOk(pa, ua, pb, ub, sameShard, pr, oq, cfg, refA, refB, refR, n));
        if (firstFuseProfit <= 0) {
            var attribution = attributeLeg(pa, ua, pb, ub, sameShard, pr, oq, cfg, refA, refB, refR, byFlat);
            return new DepthResult(Math.max(1, byFlat), attribution.leg(), attribution.impact());
        }
        double floor = firstFuseProfit * (1.0 - cfg.depthLimitThreshold());
        long byProfit = findBoundary(n -> {
            Double avg = avgProfitAt(pa, ua, pb, ub, sameShard, pr, oq, cfg, refA, refB, refR, n);
            return avg != null && avg >= floor;
        });

        long best = Math.max(byProfit, byFlat);
        var attribution = attributeLeg(pa, ua, pb, ub, sameShard, pr, oq, cfg, refA, refB, refR, best);
        return new DepthResult(best, attribution.leg(), attribution.impact());
    }

    /** Shared exponential-bracket-then-binary-search shell for {@link
     *  #depthLimit}'s two floors - they differ only in what counts as
     *  "still acceptable" at a given quantity, not in how the boundary
     *  itself is found. */
    private static long findBoundary(java.util.function.LongPredicate acceptable) {
        long lo = 1, hi = 1;
        while (true) {
            long next = hi * 2;
            if (!acceptable.test(next)) { hi = next; break; }
            lo = next;
            hi = next;
            if (next > 1_000_000) break;   // no realistic order book is this deep
        }
        while (lo < hi) {
            long mid = lo + (hi - lo + 1) / 2;
            if (acceptable.test(mid)) lo = mid; else hi = mid - 1;
        }
        return lo;
    }

    /** Whether every leg's own average price, at {@code n} fuses, still sits
     *  within {@code cfg.depthLimitFlatTolerance()} coins of that leg's own
     *  current top-of-book price - {@link #depthLimit}'s flat-coins floor,
     *  checked leg by leg rather than as one combined profit figure. */
    private static boolean flatOk(Product pa, int ua, Product pb, int ub, boolean sameShard,
                                  Product pr, int oq, Settings cfg,
                                  Brain.Ref refA, Brain.Ref refB, Brain.Ref refR, long n) {
        double tol = cfg.depthLimitFlatTolerance();
        if (sameShard) {
            if (!legWithinFlat(pa, (long) (ua + ub) * n, pa.instaBuy(), tol, cfg, refA, true)) return false;
        } else {
            if (!legWithinFlat(pa, (long) ua * n, pa.instaBuy(), tol, cfg, refA, true)) return false;
            if (!legWithinFlat(pb, (long) ub * n, pb.instaBuy(), tol, cfg, refB, true)) return false;
        }
        return legWithinFlat(pr, (long) oq * n, pr.instaSell(), tol, cfg, refR, false);
    }

    /** One leg's own half of {@link #flatOk} - buying may drift up to {@code
     *  tolerance} above the top ask; selling may drift down to {@code
     *  tolerance} below the top bid. A leg with no real top-of-book price at
     *  all is not this floor's problem to flag - {@link #depthLimit}'s other
     *  (profit) floor already screens that case out via {@code firstFuseProfit}. */
    private static boolean legWithinFlat(Product p, long units, double top, double tolerance,
                                         Settings cfg, Brain.Ref ref, boolean isBuy) {
        if (top <= 0) return true;
        double total = isBuy ? totalBuyCost(p, units, cfg, ref) : totalSellRevenue(p, units, cfg, ref);
        if (total < 0) return false;
        double avg = total / units;
        return isBuy ? avg <= top + tolerance : avg >= top - tolerance;
    }

    /**
     * {@link #depthLimit}, resolved directly from one recipe's own index
     * rather than pre-split Product/fuseAmount/output-quantity arguments -
     * the shape every caller outside {@link #evaluate} actually has on
     * hand: the route screen's "Max" preset, and the shopping list's own
     * batch clamp once it knows which recipe a queued shard feeds (see
     * {@code ShoppingList.viaRecipe}). Having one shared place for this
     * means both read the real fusion economics the same way {@link
     * #evaluate} itself does, instead of a shard's own price in isolation.
     *
     * @return null if either input's or the output's current price is
     *         unknown - the same "nothing to say yet" case {@link #evaluate}
     *         itself skips over, rather than a misleading number.
     */
    public static DepthResult depthLimitForRecipe(FusionData data, int recipeIndex,
                                                  Map<String, Product> products, Brain brain, Settings cfg) {
        int ai = data.inputA(recipeIndex), bi = data.inputB(recipeIndex), ri = data.result(recipeIndex);
        var sa = data.shard(ai);
        var sb = data.shard(bi);
        var sr = data.shard(ri);
        boolean sameShard = ai == bi;
        int ua = sa.fuseAmount(), ub = sb.fuseAmount(), oq = data.qty(recipeIndex);

        Product pa = products.get(sa.tag());
        Product pb = products.get(sb.tag());
        Product pr = products.get(sr.tag());
        if (pa == null || pb == null || pr == null) return null;

        Brain.Ref refA = brain.reference(sa.tag());
        Brain.Ref refB = brain.reference(sb.tag());
        Brain.Ref refR = brain.reference(sr.tag());

        double costA = totalBuyCost(pa, sameShard ? ua + ub : ua, cfg, refA);
        if (costA < 0) return null;
        double cost = costA;
        if (!sameShard) {
            double costB = totalBuyCost(pb, ub, cfg, refB);
            if (costB < 0) return null;
            cost += costB;
        }
        double revenue = totalSellRevenue(pr, oq, cfg, refR);
        if (revenue < 0) return null;

        return depthLimit(pa, ua, pb, ub, sameShard, pr, oq, cfg, refA, refB, refR, revenue - cost);
    }

    private record LegImpact(Leg leg, double impact) {}

    /**
     * At the batch size {@link #depthLimit} already found, compares each
     * leg's actual cost/revenue against what it would have been if that
     * leg's own price never moved past the first fuse's rate - the leg with
     * the largest gap, as a fraction of that honest baseline, is the one
     * whose order book actually ran out of room first. The other leg(s)
     * could support buying/selling further; this one is what stops you.
     */
    private static LegImpact attributeLeg(Product pa, int ua, Product pb, int ub, boolean sameShard,
                                          Product pr, int oq, Settings cfg,
                                          Brain.Ref refA, Brain.Ref refB, Brain.Ref refR, long n) {
        double bestImpact = -1;
        Leg bestLeg = Leg.R;

        if (sameShard) {
            Double impact = degradeFraction(
                    totalBuyCost(pa, ua + ub, cfg, refA), totalBuyCost(pa, (long) (ua + ub) * n, cfg, refA), n, true);
            if (impact != null && impact > bestImpact) { bestImpact = impact; bestLeg = Leg.A; }
        } else {
            Double impactA = degradeFraction(
                    totalBuyCost(pa, ua, cfg, refA), totalBuyCost(pa, (long) ua * n, cfg, refA), n, true);
            if (impactA != null && impactA > bestImpact) { bestImpact = impactA; bestLeg = Leg.A; }

            Double impactB = degradeFraction(
                    totalBuyCost(pb, ub, cfg, refB), totalBuyCost(pb, (long) ub * n, cfg, refB), n, true);
            if (impactB != null && impactB > bestImpact) { bestImpact = impactB; bestLeg = Leg.B; }
        }

        Double impactR = degradeFraction(
                totalSellRevenue(pr, oq, cfg, refR), totalSellRevenue(pr, (long) oq * n, cfg, refR), n, false);
        if (impactR != null && impactR > bestImpact) { bestImpact = impactR; bestLeg = Leg.R; }

        return new LegImpact(bestLeg, Math.max(0, bestImpact));
    }

    /**
     * How far {@code actualAtN} has drifted from {@code n * atOne} (the
     * honest, no-depth-impact baseline), as a positive fraction of that
     * baseline - for a buy leg {@code actualAtN} costing more than the
     * baseline is the drift; for the sell leg, fetching less than the
     * baseline is. Null if either quantity could not be filled at all,
     * which the caller treats as "this leg is not the one to blame" rather
     * than crashing the comparison.
     */
    private static Double degradeFraction(double atOne, double actualAtN, long n, boolean isBuy) {
        if (atOne <= 0 || actualAtN < 0) return null;
        double baseline = atOne * n;
        double drift = isBuy ? actualAtN - baseline : baseline - actualAtN;
        return baseline > 0 ? Math.max(0, drift) / baseline : null;
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

            // Pure Reptile Attribute: fusing a Reptile Fusion - one whose
            // own result is Reptile-family, see reptileEligible's own doc -
            // has a player-specific chance to double this fuse's output.
            // Modeled as a weighted average of the normal and the doubled
            // sale rather than a flat revenue x(1+chance) scale-up, since
            // selling twice the output can walk into worse order-book depth
            // than the normal-size sale did - the same reasoning
            // totalBuyCost already documents for why a bulk price is never
            // just a per-unit price scaled up. See AttributeDetector for
            // where the chance itself comes from (read off the Attribute
            // Menu, not guessed) and why nothing beyond its confirmed base
            // formula (2% per level, 1-10) is applied.
            double pureReptileChance = reptileEligible(sr.tag()) ? cfg.pureReptileChance() : 0.0;
            if (pureReptileChance > 0) {
                Fill fsDouble = sell(pr, oq * 2L, cfg, brain.reference(sr.tag()));
                if (fsDouble.ok) {
                    double revenueDouble = fsDouble.total * (1.0 - cfg.tax());
                    revenue = revenue * (1.0 - pureReptileChance) + revenueDouble * pureReptileChance;
                }
                // If the book cannot fill double the sale, the normal-size
                // revenue above stands unmodified - understating a rare
                // doubled fuse's revenue is the safe direction, not
                // pretending the market can always absorb twice as much.
            }

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

            DepthResult depth = depthLimit(pa, ua, pb, ub, sameShard, pr, oq, cfg,
                    brain.reference(sa.tag()), brain.reference(sb.tag()), brain.reference(sr.tag()),
                    profit);
            String limiter = switch (depth.leg()) {
                case A -> sa.name();
                case B -> sb.name();
                case R -> sr.name();
            };

            out.add(new Opportunity(r, label(sameShard, ua, ub, oq, sa, sb, sr),
                    sr.tag(), sr.name(), sr.rarity(),
                    cost, profit, profit / cost,
                    fusesPerHour, coinsPerHour, rank,
                    limiter, depth.impact(), fillIn + fs.minutes, capture, measured,
                    xpPerFuse, xpPerHour, salesPerHour, depth.fuses(), boughtPerHour));
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
