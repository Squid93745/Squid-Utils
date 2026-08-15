package dev.squidutils.config;

import com.google.gson.annotations.Expose;
import io.github.notenoughupdates.moulconfig.Config;
import io.github.notenoughupdates.moulconfig.annotations.Category;
import io.github.notenoughupdates.moulconfig.common.text.StructuredText;
import io.github.notenoughupdates.moulconfig.gui.HorizontalAlign;
import io.github.notenoughupdates.moulconfig.processor.ProcessedCategory;

/**
 * Root config for Squid Utils.
 *
 * <p>One top-level category per feature, each broken into accordions so a
 * category page stays readable as features grow. Rendered by MoulConfig - the
 * same library NEU and SkyHanni use - so the screen matches what SkyBlock
 * players already know rather than imitating it.
 *
 * <p>Note that {@code @Expose} is mandatory on every {@code @Category} and
 * {@code @ConfigOption} field: MoulConfig throws at client init without it, and
 * Gson uses it to decide what gets persisted.
 */
public class SquidUtilsConfig extends Config {

    @Override
    public StructuredText getTitle() {
        return StructuredText.of("Squid Utils");
    }

    /** Search matches category names too, not just the options inside them. */
    @Override
    public boolean shouldSearchCategoryNames() {
        return true;
    }

    /** Open the screen ready to type - with this many options, search is the
     *  fastest way in. */
    @Override
    public boolean shouldAutoFocusSearchbar() {
        return true;
    }

    @Override
    public HorizontalAlign alignCategory(ProcessedCategory category, boolean selected) {
        // Centred rather than right-aligned: hard against the divider the names
        // read as though they belong to the options pane instead of their own.
        return HorizontalAlign.CENTER;
    }

    /** Buttons in the settings list dispatch here by id. */
    @Override
    public void executeRunnable(int runnableId) {
        switch (runnableId) {
            case GeneralCategory.OPEN_EDITOR -> net.minecraft.client.Minecraft.getInstance()
                    .setScreen(new dev.squidutils.hud.HudEditorScreen());
            default -> super.executeRunnable(runnableId);
        }
    }

    @Override
    public boolean isValidRunnable(int runnableId) {
        return runnableId == GeneralCategory.OPEN_EDITOR
                || super.isValidRunnable(runnableId);
    }

    @Expose
    @Category(name = "General", desc = "Overlay placement and mod-wide behaviour")
    public GeneralCategory general = new GeneralCategory();

    @Expose
    @Category(name = "Shard Fusion", desc = "Rank shard fusions by coins per hour")
    public FusionCategory fusion = new FusionCategory();

    @Expose
    @Category(name = "Fishing", desc = "Sea creatures, hotspots and catch tracking")
    public FishingCategory fishing = new FishingCategory();

    @Expose
    @Category(name = "Bestiary", desc = "Kill tracking and bestiary progress")
    public BestiaryCategory bestiary = new BestiaryCategory();
}
