package dev.squidutils.config;

import com.google.gson.annotations.Expose;
import io.github.notenoughupdates.moulconfig.annotations.Accordion;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorBoolean;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorDropdown;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorKeybind;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorSlider;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorText;
import io.github.notenoughupdates.moulconfig.annotations.ConfigOption;
import io.github.notenoughupdates.moulconfig.annotations.SearchTag;
import org.lwjgl.glfw.GLFW;

/**
 * How you buy and sell, what gets filtered out, and scoring internals - under
 * Shard Fusion in the sidebar. The least-visited fusion settings, grouped into
 * their own page so General/Tables/Graphs/Tooltips/Tracker stay short.
 */
public class FusionSettingsCategory {

    @Expose
    @ConfigOption(name = "Trading", desc = "How you buy and sell")
    @Accordion
    public Trading trading = new Trading();

    @Expose
    @ConfigOption(name = "Filters", desc = "What gets included and excluded")
    @Accordion
    public Filters filters = new Filters();

    @Expose
    @ConfigOption(name = "Advanced", desc = "Scoring internals")
    @Accordion
    public Advanced advanced = new Advanced();

    @Expose
    @ConfigOption(name = "Quick Fuse",
            desc = "A hotkey for the Fusion Box's own repeat/confirm prompts.")
    @Accordion
    public QuickFuse quickFuse = new QuickFuse();

    public static class Trading {
        @Expose
        @ConfigOption(name = "Buying inputs",
                desc = "Instabuy pays the ask and starts immediately. A buy order is "
                        + "cheaper but only fills as fast as people insta-sell into it.")
        @ConfigEditorDropdown(values = {"Instabuy", "Buy order"})
        @SearchTag("buy instabuy order acquire input")
        public int buyMode = 0;

        @Expose
        @ConfigOption(name = "Selling output",
                desc = "A sell offer earns the spread but waits for insta-buyers.")
        @ConfigEditorDropdown(values = {"Sell offer", "Instasell"})
        @SearchTag("sell offer instasell dispose output")
        public int sellMode = 0;

        @Expose
        @ConfigOption(name = "Bazaar Flipper level",
                desc = "Community Shop perk. Each level cuts bazaar tax by 0.125%.")
        @ConfigEditorSlider(minValue = 0, maxValue = 2, minStep = 1)
        @SearchTag("tax flipper perk fee")
        public int bazaarFlipperLevel = 2;

        @Expose
        @ConfigOption(name = "Community tax upgrade",
                desc = "The Elisabeth upgrade, worth a further 0.125% off the tax.")
        @ConfigEditorBoolean
        @SearchTag("tax community upgrade elisabeth fee")
        public boolean communityTaxUpgrade = true;

        @Expose
        @ConfigOption(name = "Market share when crossing the spread",
                desc = "Only used for instabuy and instasell. When you rest an order "
                        + "your share is measured from the queue instead.")
        @ConfigEditorSlider(minValue = 0.02f, maxValue = 1.0f, minStep = 0.01f)
        @SearchTag("capture share market competition")
        public float captureShare = 0.20f;

        @Expose
        @ConfigOption(name = "Max wait to fill",
                desc = "Reject orders whose estimated fill exceeds this many minutes.")
        @ConfigEditorSlider(minValue = 1, maxValue = 180, minStep = 1)
        @SearchTag("fill wait timeout minutes")
        public int maxFillMinutes = 30;

        @Expose
        @ConfigOption(name = "Batch profit tolerance",
                desc = "How far the average profit per fuse is allowed to drop "
                        + "from the first fuse's own, from buying/selling deeper "
                        + "into the order book, before a table's \"batch\" column "
                        + "and the shopping list stop counting more fuses as "
                        + "still worth it. 10% is a reasonable default.")
        @ConfigEditorSlider(minValue = 0.01f, maxValue = 0.50f, minStep = 0.01f)
        @SearchTag("batch depth limit tolerance profit drop order book")
        public float depthLimitThreshold = 0.10f;

        @Expose
        @ConfigOption(name = "Batch flat tolerance",
                desc = "An alternative to the profit tolerance above, checked "
                        + "per shard: how many coins past its own current top-of-book "
                        + "price one leg may drift before it - specifically - is "
                        + "treated as the batch's limiter. Whichever of the two "
                        + "tolerances allows more fuses wins, since a percentage of "
                        + "profit alone can be dragged down by a completely different, "
                        + "thinner leg even while this one's own price barely moved.")
        @ConfigEditorSlider(minValue = 0.5f, maxValue = 50f, minStep = 0.5f)
        @SearchTag("batch depth limit flat tolerance coins per shard")
        public float depthLimitFlatTolerance = 5.0f;
    }

    public static class Filters {
        @Expose
        @ConfigOption(name = "Minimum profit per fuse", desc = "Hide fusions clearing less.")
        @ConfigEditorText
        @SearchTag("minimum profit filter coins")
        public String minProfitPerFuse = "1000";

        @Expose
        @ConfigOption(name = "Price limit per fuse",
                desc = "Skip fusions whose inputs cost more than this. 0 means no limit.")
        @ConfigEditorText
        @SearchTag("price limit budget cost cap")
        public String maxCostPerFuse = "0";

        @Expose
        @ConfigOption(name = "Minimum weekly volume",
                desc = "Ignore output shards trading below this per week.")
        @ConfigEditorSlider(minValue = 0, maxValue = 100000, minStep = 1000)
        @SearchTag("volume liquidity minimum weekly")
        public int minMovingWeek = 5000;

        @Expose
        @ConfigOption(name = "Blacklisted inputs", desc = "Shards you refuse to buy.")
        @ConfigEditorText
        @SearchTag("blacklist input exclude ban ignore")
        public String inputBlacklist = "";

        @Expose
        @ConfigOption(name = "Blacklisted outputs", desc = "Shards you refuse to produce.")
        @ConfigEditorText
        @SearchTag("blacklist output exclude ban ignore")
        public String outputBlacklist = "";

        @Expose
        @ConfigOption(name = "Rarities", desc = "Restrict outputs by rarity. Empty means all.")
        @ConfigEditorText
        @SearchTag("rarity common uncommon rare epic legendary filter")
        public String rarityFilter = "";
    }

    public static class Advanced {
        @Expose
        @ConfigOption(name = "Backfill graphs on launch",
                desc = "Fetch missing history from Coflnet so graphs are useful "
                        + "immediately instead of after an hour of sitting still.")
        @ConfigEditorBoolean
        @SearchTag("backfill history launch coflnet startup")
        public boolean backfillHistory = true;

        @Expose
        @ConfigOption(name = "Time-of-day weight",
                desc = "How much the hourly activity curve matters, as an exponent.")
        @ConfigEditorSlider(minValue = 0.0f, maxValue = 2.0f, minStep = 0.05f)
        @SearchTag("hour time peak clock weight alpha")
        public float hourAlpha = 0.5f;

        @Expose
        @ConfigOption(name = "Trust quotes up to",
                desc = "How far above the trailing median a quote is believed.")
        @ConfigEditorSlider(minValue = 0.05f, maxValue = 1.0f, minStep = 0.05f)
        @SearchTag("phantom price guard reference median trust")
        public float maxPremiumOverReference = 0.20f;

        @Expose
        @ConfigOption(name = "Minimum resting orders",
                desc = "Orders a side of the book needs before its price is trusted.")
        @ConfigEditorSlider(minValue = 1, maxValue = 20, minStep = 1)
        @SearchTag("orders depth book minimum trust")
        public int minBookOrders = 3;

        @Expose
        @ConfigOption(name = "Require price history",
                desc = "Skip shards with no trustworthy reference price.")
        @ConfigEditorBoolean
        @SearchTag("history reference require price")
        public boolean requireReference = true;

        @Expose
        @ConfigOption(name = "Max order book impact",
                desc = "Reject a fusion if filling it moves the price more than this.")
        @ConfigEditorSlider(minValue = 0.05f, maxValue = 1.0f, minStep = 0.05f)
        @SearchTag("impact slippage book depth")
        public float maxBookImpact = 0.35f;

        @Expose
        @ConfigOption(name = "Queue efficiency",
                desc = "Share of opposing flow that reaches your order.")
        @ConfigEditorSlider(minValue = 0.1f, maxValue = 1.0f, minStep = 0.05f)
        @SearchTag("queue efficiency competition fill")
        public float queueEfficiency = 0.7f;

        @Expose
        @ConfigOption(name = "Refresh interval",
                desc = "Seconds between bazaar refreshes - affects the tables, "
                        + "the graphs, and how quickly a Bazaar order alert can "
                        + "fire. 20 is the floor: Hypixel's own bazaar data does "
                        + "not update meaningfully faster than that, so polling "
                        + "quicker would not get you fresher numbers, just more "
                        + "requests for the same ones.")
        @ConfigEditorSlider(minValue = 20, maxValue = 300, minStep = 5)
        @SearchTag("refresh interval poll seconds update")
        public int refreshSeconds = 20;
    }

    public static class QuickFuse {
        @Expose
        @ConfigOption(name = "Quick fuse hotkey",
                desc = "Press while the Fusion Box shows \"Click to repeat this "
                        + "fusion!\" or \"Click to fuse!\" to click it - exactly "
                        + "the same as clicking it with the mouse. Does nothing "
                        + "otherwise, and never fires on its own. Unbound by "
                        + "default.")
        @ConfigEditorKeybind(defaultKey = GLFW.GLFW_KEY_UNKNOWN)
        @SearchTag("quick fuse repeat confirm hotkey keybind fusion box")
        public int key = GLFW.GLFW_KEY_UNKNOWN;
    }
}
