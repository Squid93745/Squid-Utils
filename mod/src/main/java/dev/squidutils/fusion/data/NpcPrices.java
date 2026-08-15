package dev.squidutils.fusion.data;

import java.util.Map;

/**
 * Shards purchasable from an NPC at a fixed coin price, as a floor beneath
 * whatever fusing or the bazaar currently costs.
 *
 * <p>Hypixel does not expose NPC shop prices through any API this mod
 * already talks to (the bazaar API only covers player-traded goods), so this
 * is a small, hand-maintained list rather than fetched data.
 *
 * <p>Kiara's four (reptile/amphibian, North Reaches) are sourced from
 * player-reported prices, cross-checked against this project's own
 * fusion.json shard IDs but not an official API - worth a quick in-game
 * check if a number ever looks off. Sanger's five (Elusive family, Torrhus
 * Canyon) are sourced straight from the wiki's embedded copy of the item's
 * own in-game tooltip text, the same data the wiki itself renders as a
 * hover tooltip - as reliable as this list gets without reading it off a
 * live client directly. Sanger's shop also caps how many of each you can
 * buy per visit (10/10/6/6/3 respectively, per that same tooltip) - not
 * modelled here since this list is only ever used for a per-unit price
 * comparison, not a "how many can I get" one.
 *
 * <p>Worth extending as more NPC-sold shards are found.
 */
public final class NpcPrices {

    public record Entry(int coins, String npc) {}

    private static final Map<String, Entry> PRICES = Map.of(
            "Viper", new Entry(100_000, "Kiara"),
            "Crocodile", new Entry(300_000, "Kiara"),
            "Eel", new Entry(350_000, "Kiara"),
            "Gecko", new Entry(600_000, "Kiara"),
            "Red Panda", new Entry(250_000, "Sanger"),
            "Osedax", new Entry(250_000, "Sanger"),
            "Black Widow", new Entry(500_000, "Sanger"),
            "Badger", new Entry(500_000, "Sanger"),
            "Wolverine", new Entry(750_000, "Sanger")
    );

    private NpcPrices() {}

    /** The NPC price for a shard by its bare name (no " Shard" suffix), or
     *  null if no NPC is known to sell it. */
    public static Entry of(String shardName) {
        return PRICES.get(shardName);
    }
}
