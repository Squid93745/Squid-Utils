package dev.squidutils.config;

import com.google.gson.annotations.Expose;
import io.github.notenoughupdates.moulconfig.annotations.ConfigAccordionId;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorAccordion;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorBoolean;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorDropdown;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorInfoText;
import io.github.notenoughupdates.moulconfig.annotations.ConfigOption;
import io.github.notenoughupdates.moulconfig.annotations.SearchTag;

/**
 * Bestiary progress. Scaffolded ahead of the feature work, same as fishing.
 */
public class BestiaryCategory {

    private static final int PROGRESS = 0;
    private static final int OVERLAY = 1;

    @Expose
    @ConfigOption(name = "Not built yet",
            desc = "These options are scaffolding for the next feature. They save "
                    + "and load correctly, but nothing reads them yet.")
    @ConfigEditorInfoText(infoTitle = "Coming soon")
    public boolean notice = false;

    @Expose
    @ConfigOption(name = "Enable bestiary features", desc = "Master switch for this section.")
    @ConfigEditorBoolean
    @SearchTag("bestiary enable mob kill track")
    public boolean enabled = false;

    // ------------------------------------------------------------------
    @Expose
    @ConfigOption(name = "Progress", desc = "Tracking toward the next tier")
    @ConfigEditorAccordion(id = PROGRESS)
    public boolean progressAccordion = false;

    @Expose
    @ConfigOption(name = "Track kills",
            desc = "Count kills per mob and remember them between sessions.")
    @ConfigEditorBoolean
    @ConfigAccordionId(id = PROGRESS)
    @SearchTag("kills track count mob")
    public boolean trackKills = true;

    @Expose
    @ConfigOption(name = "Kills to next tier",
            desc = "Show how many more kills the current mob needs.")
    @ConfigEditorBoolean
    @ConfigAccordionId(id = PROGRESS)
    @SearchTag("tier next level remaining progress")
    public boolean showNextTier = true;

    @Expose
    @ConfigOption(name = "Estimate time remaining",
            desc = "Project time to the next tier from your recent kill rate.")
    @ConfigEditorBoolean
    @ConfigAccordionId(id = PROGRESS)
    @SearchTag("eta time remaining estimate rate")
    public boolean estimateTime = true;

    // ------------------------------------------------------------------
    @Expose
    @ConfigOption(name = "Overlay", desc = "How bestiary progress is shown")
    @ConfigEditorAccordion(id = OVERLAY)
    public boolean overlayAccordion = false;

    @Expose
    @ConfigOption(name = "Show while fighting",
            desc = "Display the panel only when you are in combat.")
    @ConfigEditorBoolean
    @ConfigAccordionId(id = OVERLAY)
    @SearchTag("combat fighting show overlay")
    public boolean onlyInCombat = true;

    @Expose
    @ConfigOption(name = "Sort order", desc = "How tracked mobs are ordered.")
    @ConfigEditorDropdown(values = {"Most recent", "Closest to tier", "Most kills"})
    @ConfigAccordionId(id = OVERLAY)
    @SearchTag("sort order arrange list")
    public int sortOrder = 0;
}
