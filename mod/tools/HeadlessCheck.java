import dev.squidutils.fusion.data.BazaarClient;
import dev.squidutils.fusion.data.Brain;
import dev.squidutils.fusion.data.FusionData;
import dev.squidutils.fusion.engine.Recommender;
import dev.squidutils.fusion.engine.Scorer;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Runs the mod's engine outside Minecraft so the Java port can be compared
 * against the Python lab, and so the three tables can be eyeballed without
 * launching the game. None of these classes touch a Minecraft API.
 */
public class HeadlessCheck {
    public static void main(String[] args) throws Exception {
        Path root = Path.of("C:\\Users\\thesh\\Downloads\\shardfuse");
        Path assets = root.resolve("mod/src/main/resources/assets/squidutils");

        FusionData data;
        try (InputStream in = Files.newInputStream(assets.resolve("fusion.json"))) {
            data = FusionData.load(in);
        }
        Brain brain = Brain.loadOrEmpty(assets.resolve("brain.json"));
        System.out.println("shards=" + data.shardCount() + " recipes=" + data.recipeCount()
                + " refs=" + brain.referenceCount() + " demand=" + brain.demandCount());

        BazaarClient client = new BazaarClient();
        Set<String> tags = new HashSet<>();
        for (var s : data.shards()) tags.add(s.tag());
        if (!client.refresh(tags)) {
            System.out.println("bazaar refresh FAILED: " + client.lastError());
            return;
        }

        int hour = ZonedDateTime.now(ZoneOffset.UTC).getHour();
        Scorer.Settings cfg = new Scorer.Settings(
                0.00875, Scorer.BuyMode.INSTA_BUY, Scorer.SellMode.SELL_OFFER,
                0.20, 0.5, 1000, 0, 5000, 0.35, 0.20, 3, true, 30.0, 0.7,
                Set.of(), Set.of(), Set.of(), false, 34);

        long t0 = System.nanoTime();
        var all = Scorer.evaluate(data, client.products(), brain, cfg, hour);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        System.out.println("viable=" + all.size() + "  scored in " + ms + "ms\n");

        var coins = new ArrayList<>(all);
        coins.sort(Comparator.comparingDouble(Scorer.Opportunity::coinsPerHour).reversed());
        table("COINS PER FUSE", Scorer.dedupe(coins, 1, 8), o ->
                String.format("%9s %9s %5.0f%% %10s/hr",
                        fmt(o.cost()), fmt(o.profit()), o.roi() * 100, fmt(o.coinsPerHour())));

        var xp = new ArrayList<>(all);
        xp.sort(Comparator.comparingDouble(Scorer.Opportunity::xpPerHour).reversed());
        table("XP PER FUSE", Scorer.dedupe(xp, 1, 8), o ->
                String.format("%9s %6.0f xp %10s xp/hr %9s",
                        fmt(o.cost()), o.xpPerFuse(), fmt(o.xpPerHour()), fmt(o.profit())));

        var rec = Recommender.rank(all, 8);
        System.out.println("\n=== RECOMMENDED (profit/fuse 50% / speed 30% / volume 20%) ===");
        System.out.printf("%-46s %9s %9s %8s %9s %6s%n",
                "FUSE", "COST", "PROFIT", "FILL", "SOLD/H", "FIT");
        for (var s : rec) {
            var o = s.opportunity();
            double secs = Recommender.fillSeconds(o);
            System.out.printf("%-46s %9s %9s %8s %9s %5.0f%%%n",
                    trim(o.label(), 46), fmt(o.cost()), fmt(o.profit()),
                    secs < 1 ? "instant" : Math.round(secs) + "s",
                    fmt(o.salesPerHour()), s.score() * 100);
        }

        // Does the recommender actually favour cheap, fast, liquid fusions?
        System.out.println("\n=== sanity: recommended vs coins-per-hour ranked ===");
        var coinsTop = Scorer.dedupe(coins, 1, 8);
        System.out.printf("  median profit/fuse  recommended %10s   coins/hr %10s%n",
                fmt(medianProfit(ops(rec))), fmt(medianProfit(coinsTop)));
        System.out.printf("  median sold/h       recommended %10s   coins/hr %10s%n",
                fmt(medianSales(ops(rec))), fmt(medianSales(coinsTop)));
        System.out.printf("  median fill secs    recommended %10.0f   coins/hr %10.0f%n",
                medianFill(ops(rec)), medianFill(coinsTop));
    }

    static List<Scorer.Opportunity> ops(List<Recommender.Scored> in) {
        List<Scorer.Opportunity> out = new ArrayList<>();
        for (var s : in) out.add(s.opportunity());
        return out;
    }

    static double medianProfit(List<Scorer.Opportunity> in) {
        var v = in.stream().mapToDouble(Scorer.Opportunity::profit).sorted().toArray();
        return v.length == 0 ? 0 : v[v.length / 2];
    }

    static double medianFill(List<Scorer.Opportunity> in) {
        var v = in.stream().mapToDouble(Recommender::fillSeconds).sorted().toArray();
        return v.length == 0 ? 0 : v[v.length / 2];
    }

    static double medianSales(List<Scorer.Opportunity> in) {
        var v = in.stream().mapToDouble(Scorer.Opportunity::salesPerHour).sorted().toArray();
        return v.length == 0 ? 0 : v[v.length / 2];
    }

    interface Detail { String of(Scorer.Opportunity o); }

    static void table(String heading, List<Scorer.Opportunity> rows, Detail d) {
        System.out.println("=== " + heading + " ===");
        for (var o : rows) {
            System.out.printf("%-46s %s%n", trim(o.label(), 46), d.of(o));
        }
        System.out.println();
    }

    static String trim(String s, int n) {
        return s.length() > n ? s.substring(0, n - 3) + "..." : s;
    }

    static String fmt(double n) {
        double a = Math.abs(n);
        if (a >= 1e9) return String.format("%.2fb", n / 1e9);
        if (a >= 1e6) return String.format("%.2fm", n / 1e6);
        if (a >= 1e3) return String.format("%.1fk", n / 1e3);
        return String.format("%.0f", n);
    }
}
