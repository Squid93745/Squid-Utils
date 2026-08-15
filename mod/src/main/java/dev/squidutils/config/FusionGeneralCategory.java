package dev.squidutils.config;

import com.google.gson.annotations.Expose;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorBoolean;
import io.github.notenoughupdates.moulconfig.annotations.ConfigOption;
import io.github.notenoughupdates.moulconfig.annotations.SearchTag;

/** Master switches and shared display, under Shard Fusion in the sidebar. */
public class FusionGeneralCategory {

    @Expose
    @ConfigOption(name = "Enable shard fusion", desc = "Rank fusions and draw the panels.")
    @ConfigEditorBoolean
    @SearchTag("fusion shard enable bazaar profit")
    public boolean enabled = true;

    @Expose
    @ConfigOption(name = "Color legend",
            desc = "A key along the bottom of each table showing what each color "
                    + "of number means.")
    @ConfigEditorBoolean
    @SearchTag("legend key color colour explain numbers")
    public boolean showLegend = true;

    @Expose
    @ConfigOption(name = "Compact rows",
            desc = "Drop to one line per entry. Useful at small GUI scales.")
    @ConfigEditorBoolean
    @SearchTag("compact dense small lines")
    public boolean compact = false;

    @Expose
    @ConfigOption(name = "Show shopping list",
            desc = "Draw the accumulated shopping list panel, moveable in the "
                    + "overlay editor like every other panel. Add to it from a "
                    + "route screen's \"Add to shopping list\" button.")
    @ConfigEditorBoolean
    @SearchTag("shopping list visitor panel show")
    public boolean shoppingListShow = false;

    @Expose
    @ConfigOption(name = "Show fuse order",
            desc = "Draw a panel listing every fusion step queued up in the "
                    + "shopping list, dependency-ordered. Stays visible under the "
                    + "Fusion Box like every other panel, so you can read it while "
                    + "actually working through the fusions.")
    @ConfigEditorBoolean
    @SearchTag("fuse order shopping list panel show fusion box")
    public boolean fuseOrderShow = false;

    @Expose
    @ConfigOption(name = "Read wisdom automatically",
            desc = "Pick up Hunting Wisdom from the SkyBlock stats menu whenever "
                    + "you open it, and refine it further from every fusion's "
                    + "actual XP gain. Off freezes it at its last detected value.")
    @ConfigEditorBoolean
    @SearchTag("wisdom auto detect automatic stats read player")
    public boolean autoDetectWisdom = true;

    /**
     * Fusion XP scales as {@code base x (1 + wisdom / 100)}. No longer a
     * config editor field - it was a manual slider before auto-detection
     * existed, and a value the player can accidentally overwrite defeats
     * the point of detecting it automatically. {@link
     * dev.squidutils.fusion.WisdomDetector} and {@link
     * dev.squidutils.fusion.SessionTracker#reverseEngineerWisdom} both write
     * this field directly, and it is still {@code @Expose}d so its detected
     * value survives a restart.
     */
    @Expose
    public float huntingWisdom = 41.5f;
}
