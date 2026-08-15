package dev.squidutils.fusion.data;

import java.util.Map;

/**
 * Shards purchasable from an NPC at a fixed coin price, as a floor beneath
 * whatever fusing or the bazaar currently costs.
 *
 * <p>Hypixel does not expose NPC shop prices through any API this mod
 * already talks to (the bazaar API only covers player-traded goods), so this
 * is a small, hand-maintained list rather than fetched data. Currently just
 * Kiara's reptile/amphibian shards in the North Reaches, sourced from
 * player-reported prices rather than an official API - worth a quick
 * in-game check against her shop if a number here ever looks off, and worth
 * extending as more NPC-sold shards are found.
 */
public final class NpcPrices {

    public record Entry(int coins, String npc) {}

    private static final Map<String, Entry> PRICES = Map.of(
            "Viper", new Entry(100_000, "Kiara"),
            "Crocodile", new Entry(300_000, "Kiara"),
            "Eel", new Entry(350_000, "Kiara"),
            "Gecko", new Entry(600_000, "Kiara")
    );

    private NpcPrices() {}

    /** The NPC price for a shard by its bare name (no " Shard" suffix), or
     *  null if no NPC is known to sell it. */
    public static Entry of(String shardName) {
        return PRICES.get(shardName);
    }
}
