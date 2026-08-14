package dev.squidutils.fusion.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The recipe book, loaded from the compact export the Python lab produces.
 *
 * <p>Recipes are held as a flat int array of (result, qty, inputA, inputB)
 * quadruples indexing into {@link #shards}. There are roughly 135,000 of them;
 * as objects that would be 135k allocations to walk on every rescore, whereas
 * a flat array scans in a few milliseconds and stays cache-friendly.
 */
public final class FusionData {

    public record Shard(String id, String name, String tag, String rarity,
                        String type, int fuseAmount) {}

    private final List<Shard> shards;
    private final int[] recipes;
    private final Map<String, Integer> byTag;

    private FusionData(List<Shard> shards, int[] recipes) {
        this.shards = shards;
        this.recipes = recipes;
        this.byTag = new HashMap<>(shards.size() * 2);
        for (int i = 0; i < shards.size(); i++) {
            byTag.put(shards.get(i).tag(), i);
        }
    }

    public static FusionData load(InputStream in) {
        JsonObject root = JsonParser.parseReader(
                new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();

        JsonArray rawShards = root.getAsJsonArray("shards");
        List<Shard> shards = new ArrayList<>(rawShards.size());
        for (var el : rawShards) {
            JsonObject s = el.getAsJsonObject();
            shards.add(new Shard(
                    s.get("i").getAsString(),
                    s.get("n").getAsString(),
                    s.get("t").getAsString(),
                    s.get("r").getAsString(),
                    s.get("y").getAsString(),
                    s.get("f").getAsInt()));
        }

        JsonArray rawRecipes = root.getAsJsonArray("recipes");
        int[] flat = new int[rawRecipes.size()];
        for (int i = 0; i < flat.length; i++) {
            flat[i] = rawRecipes.get(i).getAsInt();
        }
        return new FusionData(shards, flat);
    }

    public List<Shard> shards() { return shards; }

    public int shardCount() { return shards.size(); }

    public int recipeCount() { return recipes.length / 4; }

    public Shard shard(int index) { return shards.get(index); }

    public int indexOfTag(String tag) { return byTag.getOrDefault(tag, -1); }

    // --- flat recipe accessors, by recipe ordinal ---
    public int result(int r) { return recipes[r * 4]; }
    public int qty(int r)    { return recipes[r * 4 + 1]; }
    public int inputA(int r) { return recipes[r * 4 + 2]; }
    public int inputB(int r) { return recipes[r * 4 + 3]; }
}
