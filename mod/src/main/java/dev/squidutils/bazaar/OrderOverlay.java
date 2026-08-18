package dev.squidutils.bazaar;

import dev.squidutils.SquidUtils;
import dev.squidutils.config.SquidUtilsConfig;
import dev.squidutils.fusion.engine.FusionEngine;
import dev.squidutils.mixin.AbstractContainerScreenAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the "Co-op Bazaar Orders" screen's own item lore - the one screen
 * that lists every order you have placed, including ones from an earlier
 * session, unlike {@link OrderTracker}'s chat-tracked list, which only ever
 * sees what it personally witnessed being placed since launch. Two things
 * consume that one scan: this class's own red/green outdated tint, and
 * {@code FusionWidgets}'s movable "Order value" panel (see {@link
 * #myOrders}), which needs the full picture - "the value of the items I
 * already have in the bazaar", not just the ones placed while it happened
 * to be running - to be worth anything.
 *
 * <p>Confirmed from a live capture, each slot's own tooltip states the side
 * ("SELL"/"BUY"), the item, the exact quantity and the "Price per unit" it
 * was placed at, e.g.
 * <pre>
 *   SELL Hideonring Shard
 *   Worth 97.2M coins
 *
 *   Offer amount: 221x
 *
 *   Price per unit: 444,974.4 coins
 *
 *   By: [VIP+] Squid93745
 *
 *   Click to view options!
 * </pre>
 * That capture is a sell offer; whether a buy order's own quantity line uses
 * the same "Offer amount:" wording is unconfirmed - {@code OrderTracker}'s
 * own "Selling:"/"Ordering:" split (a real, previously wrong guess) shows
 * Hypixel does not always keep buy/sell wording symmetric. {@link #parseOrder}
 * treats quantity as best-effort rather than required for exactly that
 * reason: a wrong guess here degrades one field to unknown ("?" in the value
 * panel) instead of hiding the whole order, which price and ownership have
 * already confirmed is real.
 *
 * <p>The "By:" line scopes every order to the current player's own, since a
 * co-op's shared Bazaar Orders screen can list a teammate's right alongside
 * yours.
 *
 * <p>Drawn from the screen's own background stage ({@code
 * ScreenEvents.afterBackground}, registered in {@code SquidUtils}) rather
 * than the HUD layer above it - the exact same reason {@code FusionHud}
 * draws there while a menu is open - so the tint sits behind the item icon
 * that then draws on top of it, not over it.
 */
public final class OrderOverlay {

    private OrderOverlay() {}

    private static final Pattern TITLE = Pattern.compile("^(?:\\(\\d+/\\d+\\) )?Co-op Bazaar Orders$");
    private static final Pattern SIDE_ITEM = Pattern.compile("^(SELL|BUY)\\s+(.+)$");
    private static final Pattern PRICE_PER_UNIT = Pattern.compile("Price per unit:\\s*([\\d,.]+)\\s*coins");
    private static final Pattern OFFER_AMOUNT = Pattern.compile("Offer amount:\\s*([\\d,]+)x");
    private static final Pattern BY_LINE = Pattern.compile("By:\\s*(.+)$");

    private static final int OUTDATED_COLOUR = 0xA0FF3333;
    private static final int UP_TO_DATE_COLOUR = 0xA033CC33;
    private static final int SLOT_SIZE = 16;

    /** One of the current player's own orders, straight off the screen's
     *  lore. {@code tag}/{@code quantity} are best-effort (see the class
     *  doc) and may be null - a consumer that cannot resolve a live price or
     *  total without one of them degrades to "?" rather than skipping the
     *  order entirely, since the order itself (side, item, price, ownership)
     *  is still confirmed real. */
    public record Order(boolean sell, String tag, String item, Long quantity, double unitPrice) {}

    /**
     * The last full scan of the Co-op Bazaar Orders screen, kept around
     * after the screen closes rather than cleared - {@code FusionWidgets}'s
     * "Order value" panel is meant to keep showing what your orders are
     * worth without needing that screen open continuously, the same way the
     * real orders themselves keep sitting in the bazaar whether or not you
     * are looking at them. Replaced wholesale by a fresh list each scan
     * rather than mutated in place, so a reader mid-iteration never sees it
     * change out from under it.
     */
    private static List<Order> lastScan = List.of();

    /** Every order the last scan of the Co-op Bazaar Orders screen found for
     *  the current player - empty until that screen has been opened at
     *  least once this session, since there is no other way to see this
     *  data at all (no Hypixel API exposes it). */
    public static List<Order> myOrders() {
        return lastScan;
    }

    /**
     * The last computed state per (side, item), so a state flip can be
     * logged once with the exact numbers behind it instead of every render
     * (60+/sec) or not at all.
     *
     * <p>This exists specifically to settle a real report: watching the Co-op
     * Bazaar Orders screen continuously, the overlay's colour change lags a
     * full extra refresh cycle behind {@link OrderTracker}'s own chat/sound
     * alert - which is the opposite of what tracing the code predicts, since
     * this class reads {@code engine.products()} fresh every render with no
     * dependency on {@code OrderTracker}'s own two-snapshot confirmation
     * delay. Logging every transition here, next to {@code OrderTracker}'s
     * own "suspect"/"beaten" lines (same log, same timestamps), makes the
     * actual relative timing checkable from a real capture instead of
     * theorised about further.
     */
    private static final Map<String, Boolean> lastLogged = new HashMap<>();

    public static void render(Screen screen, GuiGraphicsExtractor g) {
        SquidUtilsConfig cfg = SquidUtils.config();
        if (cfg == null || !cfg.bazaar.enabled) return;

        if (!(screen instanceof AbstractContainerScreen<?> acs)) return;
        if (!TITLE.matcher(screen.getTitle().getString()).matches()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null) return;
        var engine = SquidUtils.engine();
        if (engine == null) return;
        String myName = mc.player.getGameProfile().name().toLowerCase(Locale.ROOT);

        var accessor = (AbstractContainerScreenAccessor) acs;
        int left = accessor.squidutils$leftPos();
        int top = accessor.squidutils$topPos();

        // Scanned unconditionally, whenever this screen is open and bazaar
        // features are on at all - not gated by the tint toggles below,
        // since the Order value panel needs this same scan even when
        // neither tint colour is switched on.
        boolean showOutdated = cfg.bazaar.orderOverlay.outdated;
        boolean showUpToDate = cfg.bazaar.orderOverlay.upToDate;
        List<Order> scanned = new ArrayList<>();

        for (Slot slot : acs.getMenu().slots) {
            ItemStack stack = slot.getItem();
            if (stack == null || stack.isEmpty()) continue;

            Order order = parseOrder(stack, myName, engine);
            if (order == null) continue;
            scanned.add(order);

            if (!showOutdated && !showUpToDate) continue;   // scanned, just not tinted
            if (order.tag() == null) continue;
            var product = engine.products().get(order.tag());
            if (product == null) continue;

            double current = order.sell() ? product.instaBuy() : product.instaSell();
            boolean outdated = order.sell()
                    ? current > 0 && current < order.unitPrice() - OrderTracker.PRICE_EPSILON
                    : current > 0 && current > order.unitPrice() + OrderTracker.PRICE_EPSILON;
            logIfChanged(order, current, outdated, engine);

            if (outdated && showOutdated) {
                fillSlot(g, left, top, slot, OUTDATED_COLOUR);
            } else if (!outdated && showUpToDate) {
                fillSlot(g, left, top, slot, UP_TO_DATE_COLOUR);
            }
        }
        lastScan = scanned;
    }

    /** @return null unless this slot is clearly one of the current player's
     *  own orders - the side/item, price, and owner all matched. {@code
     *  tag}/{@code quantity} are resolved best-effort and may still be null
     *  on a returned order - see the class doc. */
    private static Order parseOrder(ItemStack stack, String myName, FusionEngine engine) {
        Matcher sm = SIDE_ITEM.matcher(stripColor(stack.getHoverName().getString()));
        if (!sm.matches()) return null;
        boolean sell = sm.group(1).equals("SELL");
        String item = sm.group(2).trim();

        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore == null) return null;

        Double unitPrice = null;
        Long quantity = null;
        boolean mine = false;
        for (Component line : lore.lines()) {
            String text = stripColor(line.getString());
            Matcher pm = PRICE_PER_UNIT.matcher(text);
            if (pm.find()) unitPrice = OrderTracker.parseDouble(pm.group(1));
            Matcher qm = OFFER_AMOUNT.matcher(text);
            if (qm.find()) quantity = OrderTracker.parseLong(qm.group(1));
            Matcher bm = BY_LINE.matcher(text);
            if (bm.find() && bm.group(1).toLowerCase(Locale.ROOT).contains(myName)) mine = true;
        }
        if (!mine || unitPrice == null) return null;
        String tag = OrderTracker.tagFor(engine.data(), item);
        return new Order(sell, tag, item, quantity, unitPrice);
    }

    private static void logIfChanged(Order order, double current, boolean outdated, FusionEngine engine) {
        String key = (order.sell() ? "sell:" : "buy:") + order.item();
        Boolean previous = lastLogged.put(key, outdated);
        if (previous != null && previous == outdated) return;
        SquidUtils.LOG.info(
                "[squidutils] order overlay {}: {} {} @ {} (book now {}) - lastRefresh={} snapshotTime={}",
                outdated ? "outdated" : "up to date", order.sell() ? "sell" : "buy", order.item(),
                order.unitPrice(), current, engine.lastRefresh(), engine.bazaarSnapshotTime());
    }

    private static void fillSlot(GuiGraphicsExtractor g, int left, int top, Slot slot, int colour) {
        int x = left + slot.x;
        int y = top + slot.y;
        g.fill(x, y, x + SLOT_SIZE, y + SLOT_SIZE, colour);
    }

    private static String stripColor(String s) {
        return s.replaceAll("§.", "");
    }
}
