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
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Historical bazaar prices from Coflnet, used to backfill graphs at launch.
 *
 * <p>The mod only accumulates history while the game is running, so a two hour
 * graph window is empty for two hours after every login. Coflnet has the data
 * already; fetching it once at startup makes the graphs useful immediately.
 *
 * <p>No API key is needed. Documented limits are 30 requests per 10s and 100
 * per minute per IP, so calls are paced well under that - a backfill is never
 * urgent enough to risk a rate limit.
 */
public final class CoflnetClient {

    private static final String BASE = "https://sky.coflnet.com/api/bazaar";
    private static final String UA = "squidutils/0.1 (personal mod)";
    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    /** Politeness spacing between requests, in milliseconds. */
    private static final long GAP_MS = 250;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private long lastCall;

    /** One historical sample: top-of-book quotes plus weekly traded volume. */
    public record Point(long ts, double buy, double sell, long buyMovingWeek) {}

    /**
     * The last day of samples for one shard, filtered to [from, to].
     *
     * <p>Deliberately the fixed {@code /history/day} endpoint rather than a
     * custom range, for two measured reasons:
     *
     * <ul>
     *   <li>Coflnet runs roughly 90 minutes behind live. A custom range ending
     *       "now" therefore falls entirely inside that gap and comes back
     *       empty - which is exactly what the first version of this did.</li>
     *   <li>Its resolution depends on the width of the range asked for, and
     *       badly: a 24 hour range returned 11 points where a 22 hour one
     *       returned 442. The fixed endpoint gives a consistent ~450.</li>
     * </ul>
     *
     * <p>The graph window maxes out at 4 hours, so a day always covers it.
     */
    public List<Point> history(String tag, Instant from, Instant to) {
        String url = BASE + "/" + tag + "/history/day";
        try {
            pace();
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", UA)
                    .timeout(Duration.ofSeconds(30))
                    .GET().build();
            HttpResponse<java.io.InputStream> resp =
                    http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() != 200) return List.of();

            JsonArray arr;
            try (var reader = new InputStreamReader(resp.body(), StandardCharsets.UTF_8)) {
                var parsed = JsonParser.parseReader(reader);
                if (!parsed.isJsonArray()) return List.of();
                arr = parsed.getAsJsonArray();
            }

            long lo = from.getEpochSecond(), hi = to.getEpochSecond();
            List<Point> out = new ArrayList<>(arr.size());
            for (var el : arr) {
                if (!el.isJsonObject()) continue;
                JsonObject o = el.getAsJsonObject();
                Long ts = parseTs(o);
                if (ts == null || ts < lo || ts > hi) continue;
                out.add(new Point(ts,
                        num(o, "buy"), num(o, "sell"),
                        (long) num(o, "buyMovingWeek")));
            }
            out.sort((a, b) -> Long.compare(a.ts(), b.ts()));
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    private void pace() throws InterruptedException {
        long since = System.currentTimeMillis() - lastCall;
        if (since < GAP_MS) Thread.sleep(GAP_MS - since);
        lastCall = System.currentTimeMillis();
    }

    private static double num(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsDouble() : 0;
    }

    /** Coflnet emits naive UTC ISO strings, sometimes with fractional seconds. */
    private static Long parseTs(JsonObject o) {
        if (!o.has("timestamp")) return null;
        String s = o.get("timestamp").getAsString();
        if (s.endsWith("Z")) s = s.substring(0, s.length() - 1);
        int dot = s.indexOf('.');
        if (dot > 0) s = s.substring(0, dot);
        try {
            return java.time.LocalDateTime.parse(s).toEpochSecond(ZoneOffset.UTC);
        } catch (Exception e) {
            return null;
        }
    }
}
