package dev.squidutils.config;

import com.google.gson.annotations.Expose;
import io.github.notenoughupdates.moulconfig.annotations.ConfigAccordionId;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorAccordion;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorBoolean;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorInfoText;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorSlider;
import io.github.notenoughupdates.moulconfig.annotations.ConfigOption;
import io.github.notenoughupdates.moulconfig.annotations.SearchTag;

/**
 * Fishing. Scaffolded ahead of the feature work so the layout and search are in
 * place; the toggles are wired to config but nothing reads them yet.
 */
public class FishingCategory {

    private static final int TRACKER = 0;
    private static final int ALERTS = 1;

    @Expose
    @ConfigOption(name = "Not built yet",
            desc = "These options are scaffolding for the next feature. They save "
                    + "and load correctly, but nothing reads them yet.")
    @ConfigEditorInfoText(infoTitle = "Coming soon")
    public boolean notice = false;

    @Expose
    @ConfigOption(name = "Enable fishing features", desc = "Master switch for this section.")
    @ConfigEditorBoolean
    @SearchTag("fishing enable sea creature rod")
    public boolean enabled = false;

    // ------------------------------------------------------------------
    @Expose
    @ConfigOption(name = "Catch tracker", desc = "What you have caught this session")
    @ConfigEditorAccordion(id = TRACKER)
    public boolean trackerAccordion = false;

    @Expose
    @ConfigOption(name = "Track sea creatures",
            desc = "Count each sea creature caught, split by rarity.")
    @ConfigEditorBoolean
    @ConfigAccordionId(id = TRACKER)
    @SearchTag("sea creature track count catch")
    public boolean trackSeaCreatures = true;

    @Expose
    @ConfigOption(name = "Show catch rate",
            desc = "Sea creatures per hour, alongside the session total.")
    @ConfigEditorBoolean
    @ConfigAccordionId(id = TRACKER)
    @SearchTag("rate per hour catch speed")
    public boolean showCatchRate = true;

    @Expose
    @ConfigOption(name = "Track coins earned",
            desc = "Value of drops at current bazaar prices.")
    @ConfigEditorBoolean
    @ConfigAccordionId(id = TRACKER)
    @SearchTag("coins profit value drops money")
    public boolean trackCoins = true;

    // ------------------------------------------------------------------
    @Expose
    @ConfigOption(name = "Alerts", desc = "Notifications while fishing")
    @ConfigEditorAccordion(id = ALERTS)
    public boolean alertsAccordion = false;

    @Expose
    @ConfigOption(name = "Rare catch alert",
            desc = "Announce legendary and rare sea creatures on screen.")
    @ConfigEditorBoolean
    @ConfigAccordionId(id = ALERTS)
    @SearchTag("alert rare legendary notify announce")
    public boolean rareCatchAlert = true;

    @Expose
    @ConfigOption(name = "Hotspot reminder",
            desc = "Warn when a fishing hotspot is about to expire.")
    @ConfigEditorBoolean
    @ConfigAccordionId(id = ALERTS)
    @SearchTag("hotspot reminder expire warn timer")
    public boolean hotspotReminder = true;

    @Expose
    @ConfigOption(name = "Alert duration", desc = "Seconds an alert stays on screen.")
    @ConfigEditorSlider(minValue = 1, maxValue = 15, minStep = 1)
    @ConfigAccordionId(id = ALERTS)
    @SearchTag("alert duration seconds timeout")
    public int alertSeconds = 4;
}
