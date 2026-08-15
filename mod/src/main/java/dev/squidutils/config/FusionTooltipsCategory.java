package dev.squidutils.config;

import com.google.gson.annotations.Expose;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorBoolean;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorKeybind;
import io.github.notenoughupdates.moulconfig.annotations.ConfigOption;
import io.github.notenoughupdates.moulconfig.annotations.SearchTag;
import org.lwjgl.glfw.GLFW;

/** Extra lines added to shard tooltips, under Shard Fusion in the sidebar. */
public class FusionTooltipsCategory {

    @Expose
    @ConfigOption(name = "Show cheapest fusion",
            desc = "Add a line to a shard's tooltip showing the cheapest way to "
                    + "fuse it, next to the bazaar prices Hypixel already lists.")
    @ConfigEditorBoolean
    @SearchTag("tooltip cheapest craft fuse hover shard")
    public boolean tooltipCheapest = true;

    @Expose
    @ConfigOption(name = "Include multi-step routes",
            desc = "Also consider fusing the inputs when that comes out cheaper "
                    + "than buying them, instead of one direct fusion.")
    @ConfigEditorBoolean
    @SearchTag("tooltip multi step recursive chain cheaper")
    public boolean tooltipMultiStep = false;

    @Expose
    @ConfigOption(name = "Open route hotkey",
            desc = "While hovering a shard with a multi-step route (needs "
                    + "\"Include multi-step routes\" above), press this to jump "
                    + "straight to the full route screen. Unbound by default.")
    @ConfigEditorKeybind(defaultKey = GLFW.GLFW_KEY_UNKNOWN)
    @SearchTag("keybind hotkey open route screen shard tooltip")
    public int openRouteKey = GLFW.GLFW_KEY_UNKNOWN;
}
