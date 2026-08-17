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
 * Frozen Blaze stops dealing its passive damage once you have stood still
 * long enough - see {@link dev.squidutils.fishing.FrozenBlazeOverlay} for the
 * stillness tracking and the full-screen tint this drives. Second under
 * Fishing in the sidebar, after General.
 */
public class FrozenBlazeCategory {

    /** Runnable ids wired up in {@link SquidUtilsConfig}. */
    public static final int TEST_SOUND = 1;
    public static final int LIST_SOUNDS = 2;

    @Expose
    @ConfigOption(name = "Enable overlay",
            desc = "Tint the screen while the mouse goes still. Only tracked "
                    + "at all while wearing the full Frozen Blaze set - its "
                    + "aura is a full-set bonus, so anything less already "
                    + "deals no passive damage to lose in the first place.")
    @ConfigEditorBoolean
    @SearchTag("frozen blaze overlay standstill afk stopped damage")
    public boolean enabled = false;

    /** Hypixel's own timing, not a setting - see {@link #fadeInSeconds}. */
    public static final int STILLNESS_SECONDS = 30;

    @Expose
    @ConfigOption(name = "Fade-in time",
            desc = "How many of the 30 seconds before Frozen Blaze stops "
                    + "dealing damage are spent fading in, reaching fully dark "
                    + "right as it does. Before that window starts, the "
                    + "overlay stays invisible.")
    @ConfigEditorSlider(minValue = 1, maxValue = 30, minStep = 1)
    @SearchTag("frozen blaze fade in time ramp warning")
    public int fadeInSeconds = 5;

    @Expose
    @ConfigOption(name = "Overlay colour",
            desc = "Colour the screen fades towards while Frozen Blaze is "
                    + "still dealing damage - a warning, not the final state. "
                    + "Its own opacity is the ceiling during that fade-in; "
                    + "the setting below takes over the instant damage "
                    + "actually stops.")
    @ConfigEditorColour
    @SearchTag("frozen blaze colour color overlay tint")
    public String color = ChromaColour.special(0, 140, 120, 190, 255);

    @Expose
    @ConfigOption(name = "Opacity once stopped",
            desc = "As a percentage: how opaque the overlay colour above "
                    + "becomes the instant Frozen Blaze actually stops "
                    + "dealing damage, replacing its own opacity at that "
                    + "exact moment rather than fading further. 100 turns "
                    + "the whole screen solidly that colour.")
    @ConfigEditorSlider(minValue = 0, maxValue = 100, minStep = 5)
    @SearchTag("frozen blaze opacity stopped damage percent")
    public int stoppedOpacity = 100;

    @Expose
    @ConfigOption(name = "Sound Settings",
            desc = "A reminder sound once the overlay is fully faded in.")
    @Accordion
    public Sound sound = new Sound();

    /** Laid out like SkyHanni's own sound settings pages. */
    public static class Sound {
        @Expose
        @ConfigOption(name = "Notification Sound",
                desc = "The sound played for the reminder, e.g. "
                        + "minecraft:block.note_block.pling - see List of Sounds below.")
        @ConfigEditorText
        @SearchTag("frozen blaze sound notification id")
        public String id = "minecraft:block.note_block.pling";

        @Expose
        @ConfigOption(name = "Pitch", desc = "The pitch of the notification sound.")
        @ConfigEditorSlider(minValue = 0.5f, maxValue = 2.0f, minStep = 0.05f)
        @SearchTag("frozen blaze sound pitch")
        public float pitch = 1.0f;

        @Expose
        @ConfigOption(name = "Test Sound", desc = "Test current sound settings.")
        @ConfigEditorButton(runnableId = TEST_SOUND, buttonText = "Test")
        public boolean test = false;

        @Expose
        @ConfigOption(name = "Repeat Duration",
                desc = "Ticks between reminders while the overlay is fully "
                        + "faded in. 20 ticks is once per second; 1 plays it "
                        + "every tick.")
        @ConfigEditorSlider(minValue = 1, maxValue = 200, minStep = 1)
        @SearchTag("frozen blaze sound repeat duration ticks")
        public int repeatTicks = 60;

        @Expose
        @ConfigOption(name = "List of Sounds", desc = "A list of available sounds.")
        @ConfigEditorButton(runnableId = LIST_SOUNDS, buttonText = "Open")
        public boolean list = false;
    }
}
