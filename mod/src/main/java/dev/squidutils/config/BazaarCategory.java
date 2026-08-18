package dev.squidutils.config;

import com.google.gson.annotations.Expose;
import io.github.notenoughupdates.moulconfig.annotations.Accordion;
import io.github.notenoughupdates.moulconfig.annotations.Category;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorBoolean;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorButton;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorSlider;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorText;
import io.github.notenoughupdates.moulconfig.annotations.ConfigOption;
import io.github.notenoughupdates.moulconfig.annotations.SearchTag;

/**
 * Bazaar features - "Bazaar" itself is just a master switch, the same shape
 * {@link FishingCategory} uses, with three independent sub-pages beneath it:
 * {@link OrderTrackerCategory} (warn when a placed order stops being the best
 * price on its side), {@link OrderOverlayCategory} (tint a tracked order's
 * own item red/green wherever it appears), and {@link OrderValueCategory}
 * (a movable panel totalling what every tracked order is worth right now at
 * live prices). All three used to live loose on this page - see {@code
 * SquidUtils.migrateConfig} for why the split needed a one-time migration
 * rather than just moving the Java fields.
 */
public class BazaarCategory {

    /** Runnable ids wired up in {@link SquidUtilsConfig}. */
    public static final int TEST_SOUND = 3;
    public static final int LIST_SOUNDS = 4;

    @Expose
    @ConfigOption(name = "Enable bazaar features", desc = "Master switch for this section.")
    @ConfigEditorBoolean
    @SearchTag("bazaar enable master switch")
    public boolean enabled = true;

    @Expose
    @Category(name = "Order Tracker",
            desc = "Warn when a placed order stops being the best price on its side")
    public OrderTrackerCategory orderTracker = new OrderTrackerCategory();

    @Expose
    @Category(name = "Order Overlay",
            desc = "Tint a tracked order's own item red or green in whatever bazaar screen you have open")
    public OrderOverlayCategory orderOverlay = new OrderOverlayCategory();

    @Expose
    @Category(name = "Order Value",
            desc = "A movable panel totalling what your tracked orders are worth right now at live prices")
    public OrderValueCategory orderValue = new OrderValueCategory();

    /** Watches your own placed Bazaar orders and warns once one is no longer
     *  the best price on its side - see {@link dev.squidutils.bazaar.OrderTracker}
     *  for the chat lines this is built from. */
    public static class OrderTrackerCategory {
        @Expose
        @ConfigOption(name = "Track my orders",
                desc = "Watch orders you place and warn once one stops being the "
                        + "best price on its side.")
        @ConfigEditorBoolean
        @SearchTag("bazaar order track outdated outbid undercut warn")
        public boolean enabled = false;

        @Expose
        @ConfigOption(name = "Chat message", desc = "Announce it in chat.")
        @ConfigEditorBoolean
        @SearchTag("bazaar order chat message announce")
        public boolean chatEnabled = true;

        @Expose
        @ConfigOption(name = "Splash Text", desc = "Big on-screen text, like a title card.")
        @Accordion
        public Splash splash = new Splash();

        @Expose
        @ConfigOption(name = "Sound Settings", desc = "A sound the moment an order goes outdated.")
        @Accordion
        public Sound sound = new Sound();
    }

    /** Which bazaar item slots get tinted, and which colour - named
     *  differently from {@link dev.squidutils.bazaar.OrderOverlay}, the
     *  class that actually draws it, on purpose: two classes sharing one
     *  simple name across packages reads as a copy-paste accident even when
     *  it is not. */
    public static class OrderOverlayCategory {
        @Expose
        @ConfigOption(name = "Outdated order overlay",
                desc = "Red background on an item whose tracked order has been "
                        + "undercut or outbid.")
        @ConfigEditorBoolean
        @SearchTag("bazaar overlay outdated red background item slot")
        public boolean outdated = true;

        @Expose
        @ConfigOption(name = "Up to date order overlay",
                desc = "Green background on an item whose tracked order is "
                        + "still the best price on its side.")
        @ConfigEditorBoolean
        @SearchTag("bazaar overlay up to date green background item slot")
        public boolean upToDate = false;
    }

    /** The movable "Order Value" panel's own toggle - see {@code
     *  FusionWidgets}'s {@code ORDER_VALUE} case for the panel itself. */
    public static class OrderValueCategory {
        @Expose
        @ConfigOption(name = "Show order value panel",
                desc = "A movable panel listing every order currently sitting in "
                        + "the bazaar and what it is worth right now if instasold "
                        + "(a sell offer) or instabought (a buy order) instead of "
                        + "waiting for it to fill - hover a row for the exact "
                        + "breakdown, instead of hovering each order on the real "
                        + "Bazaar screen one at a time. Needs the Co-op Bazaar "
                        + "Orders screen opened at least once to see your orders "
                        + "at all - there is no other way to read them.")
        @ConfigEditorBoolean
        @SearchTag("bazaar order value panel live price instasell instabuy worth")
        public boolean enabled = false;
    }

    public static class Splash {
        @Expose
        @ConfigOption(name = "Enable", desc = "Show the big splash text.")
        @ConfigEditorBoolean
        @SearchTag("bazaar splash enable show")
        public boolean enabled = true;

        @Expose
        @ConfigOption(name = "Size", desc = "How large the splash text is.")
        @ConfigEditorSlider(minValue = 0.5f, maxValue = 4.0f, minStep = 0.1f)
        @SearchTag("bazaar splash size scale big")
        public float scale = 2.0f;

        @Expose
        @ConfigOption(name = "Duration", desc = "Seconds the splash stays on screen.")
        @ConfigEditorSlider(minValue = 1, maxValue = 10, minStep = 1)
        @SearchTag("bazaar splash duration seconds")
        public int seconds = 3;
    }

    /** Laid out like SkyHanni's own sound settings pages, same as Frozen Blaze's. */
    public static class Sound {
        @Expose
        @ConfigOption(name = "Enable", desc = "Play a sound too.")
        @ConfigEditorBoolean
        @SearchTag("bazaar sound enable")
        public boolean enabled = true;

        @Expose
        @ConfigOption(name = "Notification Sound",
                desc = "The sound played, e.g. minecraft:block.note_block.pling "
                        + "- see List of Sounds below.")
        @ConfigEditorText
        @SearchTag("bazaar sound notification id")
        public String id = "minecraft:block.note_block.bass";

        @Expose
        @ConfigOption(name = "Pitch", desc = "The pitch of the notification sound.")
        @ConfigEditorSlider(minValue = 0.5f, maxValue = 2.0f, minStep = 0.05f)
        @SearchTag("bazaar sound pitch")
        public float pitch = 0.7f;

        @Expose
        @ConfigOption(name = "Test Sound", desc = "Test current sound settings.")
        @ConfigEditorButton(runnableId = TEST_SOUND, buttonText = "Test")
        public boolean test = false;

        @Expose
        @ConfigOption(name = "List of Sounds", desc = "A list of available sounds.")
        @ConfigEditorButton(runnableId = LIST_SOUNDS, buttonText = "Open")
        public boolean list = false;
    }
}
