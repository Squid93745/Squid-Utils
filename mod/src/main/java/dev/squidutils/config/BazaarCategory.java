package dev.squidutils.config;

import com.google.gson.annotations.Expose;
import io.github.notenoughupdates.moulconfig.annotations.Accordion;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorBoolean;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorButton;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorSlider;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorText;
import io.github.notenoughupdates.moulconfig.annotations.ConfigOption;
import io.github.notenoughupdates.moulconfig.annotations.SearchTag;

/**
 * Warns when a placed Bazaar order is no longer the best price on its side -
 * someone undercut a sell offer, or outbid a buy order - so it will sit
 * unfilled behind theirs until replaced. See {@link
 * dev.squidutils.bazaar.OrderTracker} for the chat lines this is built from.
 */
public class BazaarCategory {

    /** Runnable ids wired up in {@link SquidUtilsConfig}. */
    public static final int TEST_SOUND = 3;
    public static final int LIST_SOUNDS = 4;

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
