package dev.squidutils.fusion.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Live Hypixel Bazaar reader. One request prices every shard, no API key.
 *
 * <p>Hypixel names its summaries for the action <em>you</em> would take, not the
 * side of the book they sit on: {@code buy_summary} is the ask side (other
 * players' sell offers, which you buy from) and {@code sell_summary} is the bid
 * side. Getting this backwards inverts the sign of every profit number, so the
 * mapping is done once, here, and the fields are named for the book side
 * everywhere downstream.
 */
public final class BazaarClient {

    private static final String URL = "https://api.hypixel.net/v2/skyblock/bazaar";
    private static final String USER_AGENT = "shardfuse-mod/0.1";

    public record Level(double price, long amount, int orders) {}

    public record Product(String tag, List<Level> asks, List<Level> bids,
                          long buyVolume, long sellVolume,
                          long buyMovingWeek, long sellMovingWeek) {

        /** Price paid per unit to buy right now. */
        public double instaBuy() { return asks.isEmpty() ? 0 : asks.getFirst().price(); }

        /** Price received per unit selling right now, before tax. */
        public double instaSell() { return bids.isEmpty() ? 0 : bids.getFirst().price(); }

        public int askOrders() {
            int n = 0;
            for (Level l : asks) n += l.orders();
            return n;
        }

        /**
         * Total cost to sweep {@code units} off the ask side, or -1 if the book
         * is too thin to fill. A fusion you cannot source is not a flip.
         */
        public double sweepCost(long units) {
            long remaining = units;
            double total = 0;
            for (Level l : asks) {
                long take = Math.min(remaining, l.amount());
                total += take * l.price();
                remaining -= take;
                if (remaining <= 0) return total;
            }
            return -1;
        }

        public double sweepRevenue(long units) {
            long remaining = units;
            double total = 0;
            for (Level l : bids) {
                long take = Math.min(remaining, l.amount());
                total += take * l.price();
                remaining -= take;
                if (remaining <= 0) return total;
            }
            return -1;
        }
    }

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private volatile Map<String, Product> products = Map.of();
    private volatile long lastUpdated = 0L;
    private volatile String lastError = null;

    public Map<String, Product> products() { return products; }
    public long lastUpdated() { return lastUpdated; }
    public String lastError() { return lastError; }

    /** Fetch and swap in a new snapshot. Call off the render thread. */
    public boolean refresh(Set<String> wanted) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(URL))
                    .header("User-Agent", USER_AGENT)
                    .timeout(Duration.ofSeconds(30))
                    .GET().build();
            HttpResponse<java.io.InputStream> resp =
                    http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() != 200) {
                lastError = "HTTP " + resp.statusCode();
                return false;
            }

            JsonObject root;
            try (var reader = new InputStreamReader(resp.body(), StandardCharsets.UTF_8)) {
                root = JsonParser.parseReader(reader).getAsJsonObject();
            }
            if (!root.get("success").getAsBoolean()) {
                lastError = "api reported failure";
                return false;
            }

            JsonObject raw = root.getAsJsonObject("products");
            Map<String, Product> out = new HashMap<>(wanted.size() * 2);
            for (String tag : raw.keySet()) {
                if (!wanted.contains(tag)) continue;
                JsonObject p = raw.getAsJsonObject(tag);
                JsonObject qs = p.has("quick_status")
                        ? p.getAsJsonObject("quick_status") : new JsonObject();
                out.put(tag, new Product(tag,
                        levels(p.getAsJsonArray("buy_summary")),
                        levels(p.getAsJsonArray("sell_summary")),
                        asLong(qs, "buyVolume"), asLong(qs, "sellVolume"),
                        asLong(qs, "buyMovingWeek"), asLong(qs, "sellMovingWeek")));
            }

            products = Collections.unmodifiableMap(out);
            lastUpdated = root.has("lastUpdated")
                    ? root.get("lastUpdated").getAsLong() : System.currentTimeMillis();
            lastError = null;
            return true;
        } catch (Exception e) {
            lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
            return false;
        }
    }

    private static List<Level> levels(JsonArray arr) {
        if (arr == null) return List.of();
        List<Level> out = new ArrayList<>(arr.size());
        for (var el : arr) {
            JsonObject o = el.getAsJsonObject();
            out.add(new Level(o.get("pricePerUnit").getAsDouble(),
                    o.get("amount").getAsLong(), o.get("orders").getAsInt()));
        }
        return out;
    }

    private static long asLong(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsLong() : 0L;
    }
}
