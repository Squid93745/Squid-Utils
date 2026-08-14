package dev.squidutils.fusion.data;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Tuned parameters exported by the Python lab.
 *
 * <p>Read from the game config directory when present so a re-tune takes effect
 * on the next refresh without rebuilding the jar, falling back to the copy
 * bundled at build time. Daily algorithm changes are the point of this project,
 * so requiring a recompile for each one would be the wrong trade.
 */
public final class Brain {

    /** Median top-of-book over the sampling window; the phantom-price yardstick. */
    public record Ref(double ask, double bid, int samples) {}

    /**
     * Demand measured from order-book deltas rather than inferred.
     *
     * <p>{@code boughtPerHour}/{@code soldPerHour} are units observed leaving
     * each side of the book. {@code askRivals}/{@code bidRivals} count the
     * orders queued at the front alongside you, which is what actually decides
     * your share of that flow when you rest an order.
     */
    public record Demand(double boughtPerHour, double soldPerHour,
                         double askRivals, double bidRivals, int coverage) {

        public double flow(String side, long movingWeek) {
            double measured = "ask".equals(side) ? boughtPerHour : soldPerHour;
            if (coverage >= 600) return measured;
            return Math.max(0.0, movingWeek / 168.0);
        }

        /** One over the number of orders sharing the front of the queue. */
        public double queueShare(String side) {
            double rivals = "ask".equals(side) ? askRivals : bidRivals;
            return Math.min(0.5, 1.0 / (1.0 + Math.max(0.0, rivals)));
        }

        public double rivals(String side) {
            return "ask".equals(side) ? askRivals : bidRivals;
        }
    }

    private final double[] hourProfile;
    private final Map<String, Ref> references;
    private final Map<String, Double> trends;
    private final Map<String, Demand> demands;
    private final long generated;
    private final long historyRows;

    private Brain(double[] hourProfile, Map<String, Ref> references,
                  Map<String, Double> trends, Map<String, Demand> demands,
                  long generated, long historyRows) {
        this.hourProfile = hourProfile;
        this.references = references;
        this.trends = trends;
        this.demands = demands;
        this.generated = generated;
        this.historyRows = historyRows;
    }

    /** A neutral brain: no history, no time-of-day shape, no reference prices. */
    public static Brain empty() {
        double[] flat = new double[24];
        java.util.Arrays.fill(flat, 1.0);
        return new Brain(flat, Map.of(), Map.of(), Map.of(), 0L, 0L);
    }

    public static Brain load(Reader reader) {
        JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();

        double[] hours = new double[24];
        java.util.Arrays.fill(hours, 1.0);
        if (root.has("hourProfile")) {
            var arr = root.getAsJsonArray("hourProfile");
            for (int h = 0; h < Math.min(24, arr.size()); h++) {
                hours[h] = arr.get(h).getAsDouble();
            }
        }

        Map<String, Ref> refs = new HashMap<>();
        if (root.has("reference")) {
            JsonObject r = root.getAsJsonObject("reference");
            for (String tag : r.keySet()) {
                JsonObject e = r.getAsJsonObject(tag);
                refs.put(tag, new Ref(e.get("ask").getAsDouble(),
                        e.get("bid").getAsDouble(), e.get("n").getAsInt()));
            }
        }

        Map<String, Double> trends = new HashMap<>();
        if (root.has("trend")) {
            JsonObject t = root.getAsJsonObject("trend");
            for (String tag : t.keySet()) {
                trends.put(tag, t.get(tag).getAsDouble());
            }
        }

        Map<String, Demand> demands = new HashMap<>();
        if (root.has("demand")) {
            JsonObject d = root.getAsJsonObject("demand");
            for (String tag : d.keySet()) {
                JsonObject e = d.getAsJsonObject(tag);
                demands.put(tag, new Demand(
                        e.get("b").getAsDouble(), e.get("s").getAsDouble(),
                        e.get("ao").getAsDouble(), e.get("bo").getAsDouble(),
                        e.get("cov").getAsInt()));
            }
        }

        long gen = root.has("generated") ? root.get("generated").getAsLong() : 0L;
        long rows = 0L;
        if (root.has("coverage") && root.getAsJsonObject("coverage").has("rows")) {
            rows = root.getAsJsonObject("coverage").get("rows").getAsLong();
        }
        return new Brain(hours, refs, trends, demands, gen, rows);
    }

    public static Brain loadOrEmpty(Path configFile) {
        if (configFile != null && Files.isReadable(configFile)) {
            try (Reader r = Files.newBufferedReader(configFile, StandardCharsets.UTF_8)) {
                return load(r);
            } catch (IOException | RuntimeException ignored) {
                // Fall through to empty rather than breaking startup over a
                // half-written export.
            }
        }
        return empty();
    }

    public double hourFactor(int utcHour) {
        return hourProfile[Math.floorMod(utcHour, 24)];
    }

    public double[] hourProfile() { return hourProfile; }

    public Ref reference(String tag) { return references.get(tag); }

    /**
     * Demand momentum for a shard: movingWeek now versus 24h ago. Above 1 means
     * trade is accelerating. Neutral when unknown, so a missing entry never
     * distorts the ranking.
     */
    public double trend(String tag) {
        return trends.getOrDefault(tag, 1.0);
    }

    public int trendCount() { return trends.size(); }

    /** Measured demand for a shard, or null if the collector has not seen it. */
    public Demand demand(String tag) { return demands.get(tag); }

    public int demandCount() { return demands.size(); }

    public boolean hasReferences() { return !references.isEmpty(); }

    public int referenceCount() { return references.size(); }

    public long generatedEpochSeconds() { return generated; }

    public long historyRows() { return historyRows; }
}
