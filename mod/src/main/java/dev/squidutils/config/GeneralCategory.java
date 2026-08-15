package dev.squidutils.config;

import com.google.gson.annotations.Expose;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorBoolean;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorButton;
import io.github.notenoughupdates.moulconfig.annotations.ConfigOption;
import io.github.notenoughupdates.moulconfig.annotations.SearchTag;

/** Settings that apply to every overlay the mod draws. */
public class GeneralCategory {

    /** Runnable id wired up in {@link SquidUtilsConfig}. */
    public static final int OPEN_EDITOR = 0;

    public static final int TABLES = 3;
    public static final int GRAPHS_PER_TABLE = 3;

    @Expose
    @ConfigOption(name = "Show overlays", desc = "Master switch for every on-screen panel.")
    @ConfigEditorBoolean
    @SearchTag("hud overlay panel show hide toggle")
    public boolean showHud = true;

    @Expose
    @ConfigOption(name = "Move and resize overlays",
            desc = "Opens a screen where you drag each panel into place and scroll "
                    + "to resize it. Far easier than nudging sliders blind.")
    @ConfigEditorButton(runnableId = OPEN_EDITOR, buttonText = "Open editor")
    @SearchTag("move drag position resize scale editor layout place gui")
    public boolean openEditor = false;

    @Expose
    @ConfigOption(name = "Hide in menus",
            desc = "Hide overlays entirely whenever a screen is open. Leave this "
                    + "off to keep them visible at the Fusion Box; they are drawn "
                    + "underneath the menu rather than over it.")
    @ConfigEditorBoolean
    @SearchTag("hide menu screen inventory gui")
    public boolean hideInMenus = false;

    // --- placement, set by the drag editor rather than by sliders -----------
    // One entry per table, then three graphs for each. Arrays rather than a
    // dozen named fields; normalise() repairs any config written by a build
    // with different dimensions.
    @Expose public WidgetPos trackerPos = new WidgetPos(8, 420, 1.0f);
    @Expose public WidgetPos shoppingListPos = new WidgetPos(250, 420, 1.0f);
    @Expose public WidgetPos fuseOrderPos = new WidgetPos(430, 420, 1.0f);
    @Expose public WidgetPos[] tablePos = defaultTables();
    @Expose public WidgetPos[][] graphPos = defaultGraphs();

    private static WidgetPos[] defaultTables() {
        return new WidgetPos[]{
                new WidgetPos(8, 8, 1.0f),
                new WidgetPos(8, 150, 1.0f),
                new WidgetPos(8, 300, 1.0f),
        };
    }

    private static WidgetPos[][] defaultGraphs() {
        WidgetPos[][] out = new WidgetPos[TABLES][GRAPHS_PER_TABLE];
        for (int t = 0; t < TABLES; t++) {
            for (int gi = 0; gi < GRAPHS_PER_TABLE; gi++) {
                out[t][gi] = new WidgetPos(330 + gi * 210, 8 + t * 150, 1.0f);
            }
        }
        return out;
    }

    /** Called after load: an older config may have fewer or no entries. */
    public void normalise() {
        if (tablePos == null || tablePos.length < TABLES) {
            WidgetPos[] fresh = defaultTables();
            if (tablePos != null) {
                System.arraycopy(tablePos, 0, fresh, 0, Math.min(tablePos.length, TABLES));
            }
            tablePos = fresh;
        }
        if (graphPos == null || graphPos.length < TABLES) {
            graphPos = defaultGraphs();
            return;
        }
        for (int t = 0; t < TABLES; t++) {
            if (graphPos[t] == null || graphPos[t].length < GRAPHS_PER_TABLE) {
                WidgetPos[] fresh = defaultGraphs()[t];
                if (graphPos[t] != null) {
                    System.arraycopy(graphPos[t], 0, fresh, 0,
                            Math.min(graphPos[t].length, GRAPHS_PER_TABLE));
                }
                graphPos[t] = fresh;
            }
            for (int gi = 0; gi < GRAPHS_PER_TABLE; gi++) {
                if (graphPos[t][gi] == null) {
                    graphPos[t][gi] = new WidgetPos(330 + gi * 210, 8 + t * 150, 1.0f);
                }
            }
        }
        for (int t = 0; t < TABLES; t++) {
            if (tablePos[t] == null) tablePos[t] = defaultTables()[t];
        }
    }
}
