package dev.squidutils.config;

import com.google.gson.annotations.Expose;
import io.github.notenoughupdates.moulconfig.ChromaColour;
import io.github.notenoughupdates.moulconfig.annotations.Accordion;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorBoolean;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorButton;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorColour;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorSlider;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorText;
import io.github.notenoughupdates.moulconfig.annotations.ConfigOption;
import io.github.notenoughupdates.moulconfig.annotations.SearchTag;

/**
 * Timers you set yourself - see {@code dev.squidutils.tracker.CustomTimers}
 * for the data model, {@code /squidtimer} for the chat command, and {@code
 * dev.squidutils.hud.TimerScreen} for the management screen this page's
 * "Manage Timers" button opens.
 */
public class CustomTimersCategory {

    /** Runnable ids wired up in {@link SquidUtilsConfig}. */
    public static final int TEST_SOUND = 7;
    public static final int LIST_SOUNDS = 8;
    public static final int OPEN_SCREEN = 9;

    @Expose
    @ConfigOption(name = "Enable", desc = "Track your own custom timers and show their panel.")
    @ConfigEditorBoolean
    @SearchTag("custom timer tracker panel countdown reminder")
    public boolean enabled = true;

    @Expose
    @ConfigOption(name = "Manage Timers",
            desc = "Add, view and remove your timers. Also addable from chat: "
                    + "/squidtimer <duration> <name...>, e.g. /squidtimer 30m Kuudra key.")
    @ConfigEditorButton(runnableId = OPEN_SCREEN, buttonText = "Open")
    @SearchTag("custom timer manage add remove screen")
    public boolean manage = false;

    @Expose
    @ConfigOption(name = "Panel colour",
            desc = "Border tint for the custom timers panel.")
    @ConfigEditorColour
    @SearchTag("custom timer colour color panel border")
    public String color = ChromaColour.special(0, 108, 210, 255, 255);

    @Expose
    @ConfigOption(name = "Chat message", desc = "Announce a timer firing in chat.")
    @ConfigEditorBoolean
    @SearchTag("custom timer chat message announce")
    public boolean chatEnabled = true;

    @Expose
    @ConfigOption(name = "Splash Text", desc = "Big on-screen text, like a title card.")
    @Accordion
    public Splash splash = new Splash();

    @Expose
    @ConfigOption(name = "Sound Settings", desc = "A sound the moment a timer fires.")
    @Accordion
    public Sound sound = new Sound();

    public static class Splash {
        @Expose
        @ConfigOption(name = "Enable", desc = "Show the big splash text.")
        @ConfigEditorBoolean
        @SearchTag("custom timer splash enable show")
        public boolean enabled = true;

        @Expose
        @ConfigOption(name = "Size", desc = "How large the splash text is.")
        @ConfigEditorSlider(minValue = 0.5f, maxValue = 4.0f, minStep = 0.1f)
        @SearchTag("custom timer splash size scale big")
        public float scale = 2.0f;

        @Expose
        @ConfigOption(name = "Duration", desc = "Seconds the splash stays on screen.")
        @ConfigEditorSlider(minValue = 1, maxValue = 10, minStep = 1)
        @SearchTag("custom timer splash duration seconds")
        public int seconds = 3;
    }

    /** Laid out like SkyHanni's own sound settings pages, same as every
     *  other tracker's. */
    public static class Sound {
        @Expose
        @ConfigOption(name = "Enable", desc = "Play a sound too.")
        @ConfigEditorBoolean
        @SearchTag("custom timer sound enable")
        public boolean enabled = true;

        @Expose
        @ConfigOption(name = "Notification Sound",
                desc = "The sound played, e.g. minecraft:block.note_block.pling "
                        + "- see List of Sounds below.")
        @ConfigEditorText
        @SearchTag("custom timer sound notification id")
        public String id = "minecraft:block.note_block.pling";

        @Expose
        @ConfigOption(name = "Pitch", desc = "The pitch of the notification sound.")
        @ConfigEditorSlider(minValue = 0.5f, maxValue = 2.0f, minStep = 0.05f)
        @SearchTag("custom timer sound pitch")
        public float pitch = 1.0f;

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
