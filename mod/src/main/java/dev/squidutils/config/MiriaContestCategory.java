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
 * A movable panel showing Miria's Contest's own countdown - calculated from
 * the real-world clock, so it works from anywhere, not just at Torrhus
 * Canyon - plus current tier and score, read straight off the scoreboard
 * whenever those lines happen to be visible. See {@code
 * dev.squidutils.tracker.MiriaContest} for both halves.
 */
public class MiriaContestCategory {

    /** Runnable ids wired up in {@link SquidUtilsConfig}. */
    public static final int TEST_SOUND = 5;
    public static final int LIST_SOUNDS = 6;

    @Expose
    @ConfigOption(name = "Enable", desc = "Track Miria's Contest and show its own panel.")
    @ConfigEditorBoolean
    @SearchTag("miria contest tracker panel scoreboard tier score")
    public boolean enabled = true;

    @Expose
    @ConfigOption(name = "Panel colour",
            desc = "Border tint for the contest panel.")
    @ConfigEditorColour
    @SearchTag("miria contest colour color panel border")
    public String color = ChromaColour.special(0, 255, 184, 77, 255);

    @Expose
    @ConfigOption(name = "Toggle background",
            desc = "Show the panel's background fill and border. Turn off "
                    + "for plain floating text with nothing behind it.")
    @ConfigEditorBoolean
    @SearchTag("miria contest toggle background border panel transparent")
    public boolean toggleBackground = true;

    @Expose
    @ConfigOption(name = "Alert on contest start",
            desc = "Splash/chat/sound the moment Miria's Contest begins.")
    @ConfigEditorBoolean
    @SearchTag("miria contest alert start begin")
    public boolean notifyStart = true;

    @Expose
    @ConfigOption(name = "Alert on tier change",
            desc = "Splash/chat/sound every time your own tier in the contest changes.")
    @ConfigEditorBoolean
    @SearchTag("miria contest alert tier change rank up")
    public boolean notifyTierChange = true;

    @Expose
    @ConfigOption(name = "Chat message", desc = "Announce alerts in chat.")
    @ConfigEditorBoolean
    @SearchTag("miria contest chat message announce")
    public boolean chatEnabled = true;

    @Expose
    @ConfigOption(name = "Splash Text", desc = "Big on-screen text, like a title card.")
    @Accordion
    public Splash splash = new Splash();

    @Expose
    @ConfigOption(name = "Sound Settings", desc = "A sound the moment an alert fires.")
    @Accordion
    public Sound sound = new Sound();

    public static class Splash {
        @Expose
        @ConfigOption(name = "Enable", desc = "Show the big splash text.")
        @ConfigEditorBoolean
        @SearchTag("miria contest splash enable show")
        public boolean enabled = true;

        @Expose
        @ConfigOption(name = "Size", desc = "How large the splash text is.")
        @ConfigEditorSlider(minValue = 0.5f, maxValue = 4.0f, minStep = 0.1f)
        @SearchTag("miria contest splash size scale big")
        public float scale = 2.0f;

        @Expose
        @ConfigOption(name = "Duration", desc = "Seconds the splash stays on screen.")
        @ConfigEditorSlider(minValue = 1, maxValue = 10, minStep = 1)
        @SearchTag("miria contest splash duration seconds")
        public int seconds = 3;
    }

    /** Laid out like SkyHanni's own sound settings pages, same as every
     *  other tracker's. */
    public static class Sound {
        @Expose
        @ConfigOption(name = "Enable", desc = "Play a sound too.")
        @ConfigEditorBoolean
        @SearchTag("miria contest sound enable")
        public boolean enabled = true;

        @Expose
        @ConfigOption(name = "Notification Sound",
                desc = "The sound played, e.g. minecraft:block.note_block.pling "
                        + "- see List of Sounds below.")
        @ConfigEditorText
        @SearchTag("miria contest sound notification id")
        public String id = "minecraft:block.note_block.bell";

        @Expose
        @ConfigOption(name = "Pitch", desc = "The pitch of the notification sound.")
        @ConfigEditorSlider(minValue = 0.5f, maxValue = 2.0f, minStep = 0.05f)
        @SearchTag("miria contest sound pitch")
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
