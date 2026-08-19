package dev.squidutils.bazaar;

import dev.squidutils.SquidUtils;
import dev.squidutils.config.SquidUtilsConfig;
import dev.squidutils.fusion.data.BazaarClient;
import dev.squidutils.fusion.data.FusionData;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Watches your own placed Bazaar orders and warns once one is no longer the
 * best price on its side - someone has undercut a sell offer, or outbid a
 * buy order, so it will sit unfilled behind theirs until replaced.
 *
 * <p>Every pattern below is confirmed from a live capture of the real chat
 * lines Hypixel sends, not assumed from a wiki: placing an order reads
 * {@code "[Bazaar] Sell Offer Setup! 38x Galaxy Fish Shard for 64,022,338
 * coins."} or {@code "[Bazaar] Buy Order Setup! 10,000x Glacite Walker Shard
 * for 65,024,000 coins."}; cancelling reads {@code "[Bazaar] Cancelled!
 * Refunded 38x Galaxy Fish Shard from cancelling Sell Offer!"} (a sell offer
 * refunds the items) or {@code "[Bazaar] Cancelled! Refunded 65,024,000 coins
 * from cancelling Buy Order!"} (a buy order refunds the coins, and does not
 * name the item at all - matched back to a tracked order by its total
 * instead). A filled order reuses the same "Claimed ..." lines {@code
 * SessionTracker} already parses, copied here rather than shared, since the
 * two classes have no other reason to depend on each other.
 *
 * <p>The chat line's own "for 64,022,338 coins" is a total, and Hypixel's
 * unit price carries a fractional part ("621,158.5 coins", confirmed from a
 * live capture of the Sell Offer Setup screen) that dividing total/quantity
 * back out does not reliably reconstruct - the total is itself already
 * rounded to a whole coin, so the division can land a fraction off the real
 * per-unit price. {@link #scanOrderScreen} reads the exact "Unit price:"
 * line straight off that confirmation screen's own item lore instead, the
 * same slot-lore technique {@code QuickFuse} uses, and {@link #onChat}
 * prefers that exact figure over the chat-derived one whenever a matching
 * scan is still pending when the order actually goes through.
 *
 * <p>{@link #tick} never checks an order against {@code engine.products()}
 * until a bazaar refresh has actually happened since it was placed - real
 * capture: cancelling and relisting four items inside a few seconds produced
 * three simultaneous false "outdated" alerts, all landing on the same
 * refresh tick, because that refresh's snapshot still predated the orders it
 * was being compared against. A snapshot older than the order it is checking
 * proves nothing about whether the order has really been undercut.
 *
 * <p>Session-only, like {@code ShoppingList} - not worth persisting an order
 * that will resolve one way or another within the same sitting.
 *
 * <p>Safe Tracking ({@link #beatenBySingleItem}) skips the alert - but keeps
 * watching - when the only thing currently beating an order's price is a
 * single-item listing: reacting to one is a coin flip between "a real
 * competitor" and "someone's leftover 1x test order that clears itself
 * within a minute", and relisting on every false alarm burns through the
 * 15b Bazaar order cap for no reason on the second kind.
 */
public final class OrderTracker {

    private OrderTracker() {}

    private static final Pattern SETUP = Pattern.compile(
            "(Sell Offer|Buy Order) Setup!\\s+([\\d,]+)x\\s+(.+?)\\s+for\\s+([\\d,.]+)\\s+coins");
    private static final Pattern CANCEL_ITEMS = Pattern.compile(
            "Cancelled!\\s+Refunded\\s+([\\d,]+)x\\s+(.+?)\\s+from cancelling (Sell Offer|Buy Order)");
    private static final Pattern CANCEL_COINS = Pattern.compile(
            "Cancelled!\\s+Refunded\\s+([\\d,.]+)\\s+coins\\s+from cancelling (Sell Offer|Buy Order)");
    // Copied from SessionTracker, which already confirmed these against a
    // live capture - a claimed sell offer's proceeds, or a claimed buy
    // order's items.
    private static final Pattern CLAIMED_COINS = Pattern.compile(
            "Claimed\\s+([\\d,.]+)\\s+coins?\\s+from\\s+selling\\s+([\\d,]+)x?\\s+(.+?)\\s+at");
    private static final Pattern CLAIMED_ITEMS = Pattern.compile(
            "Claimed\\s+([\\d,]+)x?\\s+(.+?)\\s+worth\\s+([\\d,.]+)\\s+coins");

    // The Sell/Buy Order Setup screen's own item lore - confirmed from a live
    // capture: "Sell Offer Setup" (or "Buy Order Setup"), "Selling: 128x",
    // "Unit price: 621,158.5 coins", each its own lore line.
    private static final Pattern LORE_TITLE = Pattern.compile("(Sell Offer|Buy Order) Setup\\b");
    // "Selling:" on a Sell Offer Setup screen; a Buy Order Setup screen
    // reads "Ordering:", not "Buying:" - confirmed from a live capture
    // ("Top Order +0.1 / Buy Order Setup / ... / Ordering: 1,024x") after
    // "Buying" (an untested guess at the parallel wording) turned out wrong
    // and silently meant no Buy Order ever matched the precise scan at all.
    private static final Pattern LORE_QTY = Pattern.compile("(Selling|Ordering):\\s*([\\d,]+)x");
    private static final Pattern LORE_UNIT_PRICE = Pattern.compile("Unit price:\\s*([\\d,.]+)\\s*coins");

    private static final class Tracked {
        final String tag;
        final String itemName;
        final boolean sell;
        final double unitPrice;
        final long quantity;
        // Real time the order was placed, not a tick count - compared
        // against the engine's own lastRefresh() so a check never runs
        // against a bazaar snapshot older than the order itself. See tick()
        // for why that matters.
        final long placedAtMillis;
        boolean alerted;
        // Hypixel's own bazaar snapshot timestamp (engine.bazaarSnapshotTime(),
        // not our local poll clock) at the first reading that looked beaten,
        // or -1 if none is pending. Confirmed from a real capture: two of our
        // own polls 21 seconds apart reported the exact same "book now" price
        // for the same order - Hypixel's backend does not actually advance
        // its own snapshot every time we ask, so requiring a second *local*
        // refresh (what this used to check) can just re-read the identical
        // cached reply and rubber-stamp it. Bazaar-Utils (github.com/mkram17/
        // Bazaar-Utils, BazaarDataManager) hits the same public endpoint we do
        // and explicitly tracks Hypixel's reply-level lastUpdated for exactly
        // this reason, skipping any check whose snapshot did not actually
        // change - requiring this to differ before confirming "beaten" is the
        // same fix.
        long suspectSnapshotTime = -1;

        Tracked(String tag, String itemName, boolean sell, double unitPrice, long quantity) {
            this.tag = tag;
            this.itemName = itemName;
            this.sell = sell;
            this.unitPrice = unitPrice;
            this.quantity = quantity;
            this.placedAtMillis = System.currentTimeMillis();
        }
    }

    private static final List<Tracked> orders = new ArrayList<>();
    // A tick of noise either way is not worth flagging as "beaten" - the
    // same tolerance Scorer's own book-impact math uses. Package-visible so
    // OrderOverlay's own live re-check (of an order read straight off the
    // Co-op Bazaar Orders screen's own lore, not this class's chat-tracked
    // list) uses the exact same tolerance, rather than a second copy that
    // could silently drift from it.
    static final double PRICE_EPSILON = 0.05;

    /** Whether the level currently beating this order's price is itself just
     *  a single item - Safe Tracking's own definition of "not real
     *  competition yet" (see {@code BazaarCategory.OrderTrackerCategory
     *  #safeTracking}'s own description): a lone 1-unit listing a fraction
     *  of a coin better is likely to clear on its own well before a
     *  relisted order would even finish going through, and reacting to it
     *  risks nothing but burning through the 15b Bazaar order cap relisting
     *  against something that was never really competition. Package-visible
     *  so {@link OrderOverlay} tints the exact same case this class quietly
     *  tolerates, rather than a second guess at what "safe" means. */
    static boolean beatenBySingleItem(BazaarClient.Product p, boolean sell) {
        List<BazaarClient.Level> levels = sell ? p.asks() : p.bids();
        return !levels.isEmpty() && levels.getFirst().amount() <= 1;
    }

    // The most recent exact unit price read off an open Setup screen, paired
    // with the chat confirmation that follows a moment after the player
    // actually clicks through - see the class doc. Generous window: nothing
    // else produces this exact (side, quantity) pairing, so a slow click
    // costs nothing by widening it.
    private static Boolean pendingSell;
    private static Long pendingQuantity;
    private static Double pendingUnitPrice;
    private static long pendingAtMillis;
    private static final long PENDING_WINDOW_MILLIS = 8000;

    public static void onChat(String text) {
        if (text == null || text.isEmpty()) return;
        text = text.replaceAll("§.", "");

        Matcher m = SETUP.matcher(text);
        if (m.find()) {
            var engine = SquidUtils.engine();
            if (engine == null) return;
            boolean sell = m.group(1).equals("Sell Offer");
            long qty = parseLong(m.group(2));
            String itemName = m.group(3);
            double total = parseDouble(m.group(4));
            if (qty <= 0) return;
            String tag = tagFor(engine.data(), itemName);
            if (tag == null) return;

            // The exact per-unit price if a matching screen scan is still
            // pending; the chat total divided back down otherwise, which is
            // only ever an approximation - see the class doc.
            double unitPrice = total / (double) qty;
            if (pendingUnitPrice != null && Boolean.valueOf(sell).equals(pendingSell)
                    && pendingQuantity != null && pendingQuantity == qty
                    && System.currentTimeMillis() - pendingAtMillis <= PENDING_WINDOW_MILLIS) {
                unitPrice = pendingUnitPrice;
            }
            pendingSell = null;
            pendingQuantity = null;
            pendingUnitPrice = null;

            orders.add(new Tracked(tag, itemName, sell, unitPrice, qty));
            return;
        }

        m = CANCEL_ITEMS.matcher(text);
        if (m.find()) {
            boolean sell = m.group(3).equals("Sell Offer");
            String itemName = m.group(2);
            orders.removeIf(o -> o.sell == sell && o.itemName.equalsIgnoreCase(itemName));
            return;
        }

        m = CANCEL_COINS.matcher(text);
        if (m.find()) {
            boolean sell = m.group(2).equals("Sell Offer");
            double refunded = parseDouble(m.group(1));
            removeClosestByTotal(sell, refunded);
            return;
        }

        m = CLAIMED_COINS.matcher(text);
        if (m.find()) {
            // A claimed sell offer - "selling" names the item directly.
            String soldName = m.group(3);
            orders.removeIf(o -> o.sell && o.itemName.equalsIgnoreCase(soldName));
            return;
        }

        m = CLAIMED_ITEMS.matcher(text);
        if (m.find()) {
            // A claimed buy order's items - no side is named, so this is
            // only trusted when it is unambiguous which tracked order it is.
            String itemName = m.group(2);
            orders.removeIf(o -> !o.sell && o.itemName.equalsIgnoreCase(itemName));
        }
    }

    /** A buy order's own cancel message never names the item, only the
     *  coins refunded - matched back to whichever tracked buy order's own
     *  total (unit price x quantity) is closest, since ties are rare and an
     *  imperfect match here only means a resolved order lingers briefly. */
    private static void removeClosestByTotal(boolean sell, double refunded) {
        Tracked best = null;
        double bestDiff = Double.MAX_VALUE;
        for (Tracked o : orders) {
            if (o.sell != sell) continue;
            double diff = Math.abs(o.unitPrice * o.quantity - refunded);
            if (diff < bestDiff) {
                bestDiff = diff;
                best = o;
            }
        }
        if (best != null) orders.remove(best);
    }

    /**
     * Scans whatever container screen is open for the Setup item's exact
     * "Sell Offer Setup" / "Selling: 128x" / "Unit price: 621,158.5 coins"
     * lore lines together, the same slot-by-slot lore search {@code
     * QuickFuse} already uses to find its own known phrases.
     *
     * <p>The title line is a deliberate third requirement, not just the
     * quantity and price lines: a real false-outdated report traced back to
     * this capturing the wrong number, and the confirmation screen is
     * usually reached by first browsing a "Top Offers" list of other
     * players' competing listings - a screen a loose match against only
     * "Selling: Nx" could plausibly mistake one of those rows for the
     * order actually about to be placed. Requiring the title text pins the
     * match to the one item that is unambiguously the Setup confirmation.
     */
    private static void scanOrderScreen(Minecraft mc) {
        if (!(mc.screen instanceof AbstractContainerScreen<?> screen)) return;
        for (Slot slot : screen.getMenu().slots) {
            if (tryParseOrderLore(slot.getItem())) return;
        }
    }

    private static boolean tryParseOrderLore(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore == null) return false;
        return tryParseOrderLore(lore.lines()) || tryParseOrderLore(lore.styledLines());
    }

    private static boolean tryParseOrderLore(List<Component> lines) {
        if (lines == null) return false;
        boolean isSetupScreen = false;
        Boolean sell = null;
        Long qty = null;
        Double unitPrice = null;
        for (Component line : lines) {
            String text = line.getString();
            if (LORE_TITLE.matcher(text).find()) isSetupScreen = true;
            Matcher qm = LORE_QTY.matcher(text);
            if (qm.find()) {
                sell = qm.group(1).equalsIgnoreCase("Selling");
                qty = parseLong(qm.group(2));
            }
            Matcher pm = LORE_UNIT_PRICE.matcher(text);
            if (pm.find()) unitPrice = parseDouble(pm.group(1));
        }
        if (!isSetupScreen || sell == null || qty == null || unitPrice == null) return false;

        pendingSell = sell;
        pendingQuantity = qty;
        pendingUnitPrice = unitPrice;
        pendingAtMillis = System.currentTimeMillis();
        // Unconditional, not just on a failed match - this is the one place
        // a *successful* scan's exact numbers were never actually visible
        // before, only inferred later from whether the chat pairing used it.
        SquidUtils.LOG.info("[squidutils] bazaar setup scan: {} {}x @ {}",
                sell ? "sell" : "buy", qty, unitPrice);
        return true;
    }

    /** Package-visible so {@link OrderOverlay} can resolve the same item ->
     *  tag mapping for an order read straight off a bazaar screen's lore. */
    static String tagFor(FusionData data, String itemName) {
        String bare = itemName.endsWith(" Shard")
                ? itemName.substring(0, itemName.length() - " Shard".length()) : itemName;
        for (int i = 0; i < data.shardCount(); i++) {
            if (data.shard(i).name().equalsIgnoreCase(bare)) return data.shard(i).tag();
        }
        return null;
    }

    /** Called once a client tick from {@code SquidUtils}. */
    public static void tick(Minecraft mc) {
        if (mc == null) return;
        SquidUtilsConfig cfg = SquidUtils.config();
        if (cfg == null || !cfg.bazaar.enabled || !cfg.bazaar.orderTracker.enabled) return;

        // Runs regardless of whether anything is tracked yet - this is what
        // captures the exact unit price for the order about to be placed,
        // before onChat has anything to pair it with.
        scanOrderScreen(mc);

        if (orders.isEmpty()) return;
        var engine = SquidUtils.engine();
        if (engine == null) return;
        var products = engine.products();

        for (Tracked o : orders) {
            if (o.alerted) continue;
            // engine.products() only updates once a refresh cycle (60s by
            // default), not the instant an order is placed - a check that
            // ran before the first refresh after placing it would be
            // comparing against a snapshot that predates the order
            // entirely, which can read as "beaten" for no real reason. Wait
            // for a snapshot genuinely newer than the order itself.
            if (engine.lastRefresh() <= o.placedAtMillis) continue;

            BazaarClient.Product p = products.get(o.tag);
            if (p == null) continue;

            double current = o.sell ? p.instaBuy() : p.instaSell();
            boolean beatenNow = o.sell
                    ? current > 0 && current < o.unitPrice - PRICE_EPSILON
                    : current > 0 && current > o.unitPrice + PRICE_EPSILON;
            if (beatenNow && cfg.bazaar.orderTracker.safeTracking && beatenBySingleItem(p, o.sell)) {
                // A single-item undercut does not count under Safe Tracking
                // - reset any pending "suspect" state too, rather than
                // letting a since-cleared trivial listing linger toward an
                // alert once it is already gone.
                o.suspectSnapshotTime = -1;
                continue;
            }
            if (!beatenNow) {
                o.suspectSnapshotTime = -1;
                continue;
            }
            long snapshotTime = engine.bazaarSnapshotTime();
            if (o.suspectSnapshotTime < 0) {
                // First bad reading - note it but don't act yet, in case
                // this snapshot is a phantom order rather than a real
                // undercut.
                o.suspectSnapshotTime = snapshotTime;
                SquidUtils.LOG.info("[squidutils] bazaar order suspect: {} {}x tracked @ {}, book now @ {}",
                        o.sell ? "sell" : "buy", o.quantity, o.unitPrice, current);
                continue;
            }
            // Hypixel's own snapshot has not actually moved since the first
            // bad reading - re-checking now would just re-read the same
            // cached reply, not get a second opinion. Wait for a genuinely
            // new one.
            if (snapshotTime == o.suspectSnapshotTime) continue;

            o.alerted = true;
            SquidUtils.LOG.info("[squidutils] bazaar order beaten: {} {}x tracked @ {}, book now @ {}",
                    o.sell ? "sell" : "buy", o.quantity, o.unitPrice, current);
            alert(mc, cfg, o);
        }
    }

    private static void alert(Minecraft mc, SquidUtilsConfig cfg, Tracked o) {
        if (cfg.bazaar.orderTracker.chatEnabled && mc.player != null) {
            mc.player.sendSystemMessage(Component.literal("")
                    .append(Component.literal("§c§lBAZAAR §7» "))
                    .append(Component.literal("§fOrder for §6" + o.itemName + " §fis §c§loutdated§r§f!")));
        }
        if (cfg.bazaar.orderTracker.sound.enabled) {
            play(mc, cfg.bazaar.orderTracker.sound.id, cfg.bazaar.orderTracker.sound.pitch);
        }
        if (cfg.bazaar.orderTracker.splash.enabled) {
            dev.squidutils.hud.Splash.show("Order for " + o.itemName + " is outdated",
                    cfg.bazaar.orderTracker.splash.scale, cfg.bazaar.orderTracker.splash.seconds);
        }
    }

    /** Wired to the config screen's "Test Sound" button. */
    public static void testSound() {
        Minecraft mc = Minecraft.getInstance();
        SquidUtilsConfig cfg = SquidUtils.config();
        if (mc == null || cfg == null) return;
        play(mc, cfg.bazaar.orderTracker.sound.id, cfg.bazaar.orderTracker.sound.pitch);
    }

    private static void play(Minecraft mc, String soundId, float pitch) {
        Identifier id = Identifier.tryParse(soundId);
        SoundEvent event = id == null ? null : BuiltInRegistries.SOUND_EVENT.getValue(id);
        if (event == null) {
            if (mc.player != null) {
                mc.player.sendSystemMessage(Component.literal(
                        "§d[Squid Utils] §7Unknown sound id: §f" + soundId));
            }
            return;
        }
        mc.getSoundManager().play(SimpleSoundInstance.forUI(event, pitch));
    }

    /** Wired to the config screen's "List of Sounds" button. */
    public static void listSounds() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null) return;
        try {
            Path dir = FabricLoader.getInstance().getConfigDir().resolve(SquidUtils.MOD_ID);
            Files.createDirectories(dir);
            Path file = dir.resolve("sounds.txt");

            var ids = new ArrayList<String>();
            for (Identifier id : BuiltInRegistries.SOUND_EVENT.keySet()) ids.add(id.toString());
            Collections.sort(ids);
            Files.write(file, ids, StandardCharsets.UTF_8);

            mc.player.sendSystemMessage(Component.literal(
                    "§d[Squid Utils] §7" + ids.size() + " sound ids written to §f" + file));
        } catch (Exception e) {
            SquidUtils.LOG.warn("[squidutils] could not write sounds.txt", e);
        }
    }

    static long parseLong(String s) {
        try {
            return Long.parseLong(s.replace(",", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static double parseDouble(String s) {
        try {
            return Double.parseDouble(s.replace(",", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
