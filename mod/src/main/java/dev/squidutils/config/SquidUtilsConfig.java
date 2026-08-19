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

    /**
     * Reads the version straight from the mod's own metadata rather than a
     * second hardcoded copy here, so bumping {@code mod_version} in {@code
     * gradle.properties} is the only place that needs to change.
     */
    @Override
    public StructuredText getTitle() {
        String version = net.fabricmc.loader.api.FabricLoader.getInstance()
                .getModContainer(dev.squidutils.SquidUtils.MOD_ID)
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("");
        return StructuredText.of("Squid Utils " + version + " by Squid93745");
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
            case FrozenBlazeCategory.TEST_SOUND -> dev.squidutils.fishing.FrozenBlazeOverlay.testSound();
            case FrozenBlazeCategory.LIST_SOUNDS -> dev.squidutils.fishing.FrozenBlazeOverlay.listSounds();
            case BazaarCategory.TEST_SOUND -> dev.squidutils.bazaar.OrderTracker.testSound();
            case BazaarCategory.LIST_SOUNDS -> dev.squidutils.bazaar.OrderTracker.listSounds();
            case MiriaContestCategory.TEST_SOUND -> dev.squidutils.hud.Sounds.play(
                    tracker.miriaContest.sound.id, tracker.miriaContest.sound.pitch);
            case MiriaContestCategory.LIST_SOUNDS -> dev.squidutils.hud.Sounds.list();
            case CustomTimersCategory.TEST_SOUND -> dev.squidutils.hud.Sounds.play(
                    tracker.customTimers.sound.id, tracker.customTimers.sound.pitch);
            case CustomTimersCategory.LIST_SOUNDS -> dev.squidutils.hud.Sounds.list();
            case CustomTimersCategory.OPEN_SCREEN -> net.minecraft.client.Minecraft.getInstance()
                    .setScreen(new dev.squidutils.hud.TimerScreen());
            default -> super.executeRunnable(runnableId);
        }
    }

    @Override
    public boolean isValidRunnable(int runnableId) {
        return runnableId == GeneralCategory.OPEN_EDITOR
                || runnableId == FrozenBlazeCategory.TEST_SOUND
                || runnableId == FrozenBlazeCategory.LIST_SOUNDS
                || runnableId == BazaarCategory.TEST_SOUND
                || runnableId == BazaarCategory.LIST_SOUNDS
                || runnableId == MiriaContestCategory.TEST_SOUND
                || runnableId == MiriaContestCategory.LIST_SOUNDS
                || runnableId == CustomTimersCategory.TEST_SOUND
                || runnableId == CustomTimersCategory.LIST_SOUNDS
                || runnableId == CustomTimersCategory.OPEN_SCREEN
                || super.isValidRunnable(runnableId);
    }

    @Expose
    @Category(name = "General", desc = "Overlay placement and mod-wide behaviour")
    public GeneralCategory general = new GeneralCategory();

    @Expose
    @Category(name = "Shard Fusion", desc = "Rank shard fusions by coins per hour")
    public FusionCategory fusion = new FusionCategory();

    @Expose
    @Category(name = "Fishing", desc = "Frozen Blaze and other fishing quality-of-life")
    public FishingCategory fishing = new FishingCategory();

    @Expose
    @Category(name = "Bestiary", desc = "Kill tracking and bestiary progress")
    public BestiaryCategory bestiary = new BestiaryCategory();

    @Expose
    @Category(name = "Bazaar", desc = "Warn when a placed order is no longer the best price")
    public BazaarCategory bazaar = new BazaarCategory();

    @Expose
    @Category(name = "Tracker", desc = "Live HUD trackers for in-game events, and your own custom timers")
    public TrackerCategory tracker = new TrackerCategory();
}
