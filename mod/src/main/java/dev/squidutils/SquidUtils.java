package dev.squidutils;

import com.mojang.blaze3d.platform.InputConstants;
import dev.squidutils.config.FusionCategory;
import dev.squidutils.config.SquidUtilsConfig;
import dev.squidutils.fusion.data.FusionData;
import dev.squidutils.fusion.engine.Scorer;
import dev.squidutils.fusion.engine.FusionEngine;
import dev.squidutils.fusion.hud.FusionHud;
import io.github.notenoughupdates.moulconfig.managed.ManagedConfig;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Set;

public class SquidUtils implements ClientModInitializer {

    public static final String MOD_ID = "squidutils";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    private static ManagedConfig<SquidUtilsConfig> config;
    private static FusionEngine engine;
    private static final dev.squidutils.fusion.SessionTracker TRACKER =
            new dev.squidutils.fusion.SessionTracker();

    public static dev.squidutils.fusion.SessionTracker tracker() {
        return TRACKER;
    }

    // Toggled by /squid debug sound - see SoundEngineMixin.
    private static volatile boolean logSounds;

    public static boolean logSounds() {
        return logSounds;
    }

    // Toggled by /squid debug exp - see ExperiencePacketMixin. Separate
    // diagnostic from the chat/action-bar hooks: this is the raw vanilla
    // XP bar packet, which carries no text at all, so a mod repurposing it
    // for skill display would be completely invisible to every other hook
    // this project has - worth ruling in or out on its own.
    private static volatile boolean logExp;

    public static boolean logExp() {
        return logExp;
    }

    public static SquidUtilsConfig config() {
        return config == null ? null : config.getInstance();
    }

    /** Exposed for the Mod Menu entrypoint, which needs the editor. */
    public static ManagedConfig<SquidUtilsConfig> managedConfig() {
        return config;
    }

    public static FusionEngine engine() {
        return engine;
    }

    @Override
    public void onInitializeClient() {
        Path dir = FabricLoader.getInstance().getConfigDir().resolve(MOD_ID);
        try {
            Files.createDirectories(dir);
        } catch (Exception e) {
            LOG.warn("[squidutils] could not create config dir", e);
        }

        File configFile = dir.resolve("config.json").toFile();
        migrateConfig(configFile);
        config = ManagedConfig.create(configFile, SquidUtilsConfig.class);

        FusionData data;
        try (InputStream in = SquidUtils.class
                .getResourceAsStream("/assets/squidutils/fusion.json")) {
            if (in == null) {
                LOG.error("[squidutils] fusion.json missing from the jar; disabled");
                return;
            }
            data = FusionData.load(in);
        } catch (Exception e) {
            LOG.error("[squidutils] could not read fusion.json; disabled", e);
            return;
        }
        LOG.info("[squidutils] {} shards, {} recipes",
                data.shardCount(), data.recipeCount());

        try (InputStream in = SquidUtils.class
                .getResourceAsStream("/assets/squidutils/shard-atlas.json")) {
            dev.squidutils.fusion.hud.ShardIcons.load(in);
        } catch (Exception e) {
            LOG.warn("[squidutils] could not read shard atlas index", e);
        }
        LOG.info("[squidutils] {} shard icons",
                dev.squidutils.fusion.hud.ShardIcons.count());

        // Seed the tuned model from the bundled copy so a fresh install has
        // reference prices immediately. The lab overwrites this file on each
        // re-tune, and the engine re-reads it every refresh.
        Path brainPath = dir.resolve("brain.json");
        if (!Files.exists(brainPath)) {
            try (InputStream in = SquidUtils.class
                    .getResourceAsStream("/assets/squidutils/brain.json")) {
                if (in != null) Files.copy(in, brainPath, StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception e) {
                LOG.warn("[squidutils] could not seed brain.json", e);
            }
        }

        engine = new FusionEngine(data, brainPath,
                SquidUtils::settings,
                SquidUtils::profitVariantSettings,
                () -> config.getInstance().fusion.maxRows());
        // Backfill runs on the engine's own worker after a refresh, so it never
        // touches the render thread and always has a populated table to work
        // out which shards matter.
        var backfill = new dev.squidutils.fusion.engine.HistoryBackfill(engine, data);
        engine.setBackfill(() -> {
            var cfg = config.getInstance();
            if (backfill.needsRun(cfg)) backfill.run(cfg, settings());
        });

        engine.start(() -> config.getInstance().fusion.settings.advanced.refreshSeconds);

        FusionHud hud = new FusionHud(engine, SquidUtils::config);
        HudElementRegistry.addLast(
                Identifier.fromNamespaceAndPath(MOD_ID, "panel"), hud);

        // Drawn last so the stillness tint sits over everything else,
        // including the fusion panels above.
        HudElementRegistry.addLast(
                Identifier.fromNamespaceAndPath(MOD_ID, "frozen-blaze"),
                dev.squidutils.fishing.FrozenBlazeOverlay::render);

        // Drawn after that too - a title card, not a positioned panel, so
        // it belongs on top of everything else this mod draws.
        HudElementRegistry.addLast(
                Identifier.fromNamespaceAndPath(MOD_ID, "order-splash"),
                dev.squidutils.bazaar.OrderSplash::render);

        // The "cheapest fusion" line on a shard's own bazaar tooltip - a
        // completely different mechanism from the panel-hover tooltip above,
        // fired whenever the game renders a tooltip for an actual item.
        net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback.EVENT
                .register(dev.squidutils.fusion.hud.ShardTooltip::append);

        // Draw the overlays from the screen's background stage while a menu is
        // open. The HUD layer renders above screens, so panels drawn there sit
        // on top of the Fusion Box; hooking the background puts them under it.
        net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.AFTER_INIT.register(
                (client, screen, w, h) -> {
                    // If the shopping list just told the player to buy some
                    // amount and this happens to be the bazaar's sign prompt
                    // for it, fill the number in - see SignFill for why this
                    // is the only way to do that.
                    dev.squidutils.hud.SignFill.tryFill(screen);

                    net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
                            .afterBackground(screen)
                            .register((s, gfx, mx, my, tick) -> {
                                hud.drawUnderScreen(gfx, mx, my);
                                dev.squidutils.bazaar.OrderOverlay.render(s, gfx);
                            });

                    // Clicking a legend header sorts by it, a multi-step row
                    // opens its route, and a shard name opens its bazaar page
                    // (a shopping list row also arms the sign fill, and can be
                    // right-clicked to remove instead) - checked in that order
                    // since only the last needs nothing more than the click
                    // itself. Returning false swallows the click so it does not
                    // also land on whatever menu slot happens to be underneath.
                    net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents
                            .allowMouseClick(screen)
                            .register((s, event) -> {
                                var cfg = config();
                                if (cfg == null || cfg.general.hideInMenus
                                        || !cfg.general.showHud) return true;
                                // Only a container screen (or the bazaar's own
                                // sign prompt) gets full interaction. Everything
                                // else (pause, Options, chat, the title screen,
                                // ...) passes the click through untouched.
                                if (dev.squidutils.fusion.hud.FusionHud.visibilityOf(s)
                                        != dev.squidutils.fusion.hud.FusionHud.ScreenVisibility.FULL) {
                                    return true;
                                }
                                if (dev.squidutils.fusion.hud.FusionWidgets
                                        .handleHeaderClick(event.x(), event.y())) return false;
                                // Checked ahead of everything else a row's own
                                // area might otherwise do (open the bazaar, open
                                // a route) - the delete button sits on top of it.
                                if (dev.squidutils.fusion.hud.FusionWidgets
                                        .handleActionClick(event.x(), event.y())) return false;
                                int route = dev.squidutils.fusion.hud.FusionWidgets
                                        .multiStepRowAt(event.x(), event.y());
                                if (route >= 0) {
                                    net.minecraft.client.Minecraft.getInstance().setScreen(
                                            new dev.squidutils.hud.MultiStepScreen(s, route));
                                    return false;
                                }
                                var hit = dev.squidutils.fusion.hud.FusionWidgets
                                        .hitAt(event.x(), event.y());
                                if (hit == null) return true;
                                if (event.button() == 1 && hit.shardIndex() >= 0) {
                                    dev.squidutils.hud.ShoppingList.remove(hit.shardIndex());
                                    return false;
                                }
                                if (hit.units() > 0) {
                                    dev.squidutils.hud.SignFill.remember(hit.shard(), hit.units());
                                }
                                openBazaar(hit.shard());
                                return false;
                            });
                });

        KeyMapping openKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.squidutils.open", InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_BACKSLASH, KeyMapping.Category.MISC));

        // Session totals are read from chat: bazaar fills and skill XP gains are
        // the only place the server states what actually happened.
        // Shard roster with rarities: the names filter out non-shard trades,
        // and the rarity turns a fusion message into an exact XP figure.
        java.util.Map<String, String> shardRarities = new java.util.HashMap<>();
        for (var s : data.shards()) shardRarities.put(s.name(), s.rarity());
        dev.squidutils.fusion.SessionTracker.setShards(shardRarities);

        net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents.GAME
                .register((message, overlay) -> {
                    TRACKER.onChat(message.getString(), overlay);
                    if (!overlay) {
                        dev.squidutils.bazaar.OrderTracker.onChat(message.getString());
                        dev.squidutils.fusion.AttributeDetector.onChat(message.getString());
                    }
                });
        net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents.CHAT
                .register((message, signed, sender, params, time) -> {
                    TRACKER.onChat(message.getString(), false);
                    dev.squidutils.bazaar.OrderTracker.onChat(message.getString());
                    dev.squidutils.fusion.AttributeDetector.onChat(message.getString());
                });

        // /squidutils and /squid both open the settings. Client-side commands,
        // so they never reach Hypixel.
        net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback.EVENT
                .register((dispatcher, ctx) -> {
                    for (String name : new String[]{"squidutils", "squid"}) {
                        dispatcher.register(
                                net.fabricmc.fabric.api.client.command.v2.ClientCommands
                                        .literal(name)
                                        .then(net.fabricmc.fabric.api.client.command.v2
                                                .ClientCommands.literal("reset")
                                                .executes(c -> {
                                                    TRACKER.reset();
                                                    say("session tracker reset");
                                                    return 1;
                                                }))
                                        .then(net.fabricmc.fabric.api.client.command.v2
                                                .ClientCommands.literal("debug")
                                                .then(net.fabricmc.fabric.api.client.command.v2
                                                        .ClientCommands.literal("all")
                                                        .executes(c -> {
                                                            TRACKER.toggleCaptureAll();
                                                            say("capture-all "
                                                                    + (TRACKER.captureAll()
                                                                    ? "on - every unmatched line is kept"
                                                                    : "off"));
                                                            return 1;
                                                        }))
                                                .then(net.fabricmc.fabric.api.client.command.v2
                                                        .ClientCommands.literal("clear")
                                                        .executes(c -> {
                                                            TRACKER.captured().clear();
                                                            say("captured lines cleared");
                                                            return 1;
                                                        }))
                                                .then(net.fabricmc.fabric.api.client.command.v2
                                                        .ClientCommands.literal("sound")
                                                        .executes(c -> {
                                                            logSounds = !logSounds;
                                                            say("sound logging "
                                                                    + (logSounds
                                                                    ? "on - every sound played is logged"
                                                                    : "off"));
                                                            return 1;
                                                        }))
                                                .then(net.fabricmc.fabric.api.client.command.v2
                                                        .ClientCommands.literal("tablist")
                                                        .executes(c -> {
                                                            dumpTabList();
                                                            return 1;
                                                        }))
                                                .then(net.fabricmc.fabric.api.client.command.v2
                                                        .ClientCommands.literal("exp")
                                                        .executes(c -> {
                                                            logExp = !logExp;
                                                            say("experience packet logging "
                                                                    + (logExp
                                                                    ? "on - every XP bar update is logged"
                                                                    : "off"));
                                                            return 1;
                                                        }))
                                                .executes(c -> {
                                                    TRACKER.dumpCaptured();
                                                    return 1;
                                                }))
                                        .then(net.fabricmc.fabric.api.client.command.v2
                                                .ClientCommands.literal("pause")
                                                .executes(c -> {
                                                    TRACKER.togglePause();
                                                    say("session tracker "
                                                            + (TRACKER.paused() ? "paused" : "resumed"));
                                                    return 1;
                                                }))
                                        .executes(c -> {
                                            // Deferred: opening a screen while the
                                            // chat screen is still closing leaves
                                            // the game with no screen at all.
                                            net.minecraft.client.Minecraft.getInstance()
                                                    .schedule(() -> config.openConfigGui());
                                            return 1;
                                        }));
                    }
                });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openKey.consumeClick()) {
                config.openConfigGui();
            }
            dev.squidutils.fusion.WisdomDetector.tick(client);
            dev.squidutils.fusion.AttributeDetector.tick(client);
            TRACKER.tick();
            dev.squidutils.fishing.FrozenBlazeOverlay.tick(client);
            dev.squidutils.bazaar.OrderTracker.tick(client);
        });

        // The configurable "open route hotkey": not a registered KeyMapping,
        // since MoulConfig's own keybind editor just stores a raw GLFW code
        // that can change at runtime, so it is polled directly instead. Only
        // acts on a fresh press (edge-detected against the previous tick) and
        // only while a shard's multi-step route was shown in a tooltip within
        // the last moment - see ShardTooltip.currentHoverRoute().
        boolean[] hotkeyWasDown = {false};
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            var cfg = config();
            if (cfg == null) return;
            int key = cfg.fusion.tooltips.openRouteKey;
            boolean down = key != GLFW.GLFW_KEY_UNKNOWN
                    && com.mojang.blaze3d.platform.InputConstants.isKeyDown(client.getWindow(), key);
            if (down && !hotkeyWasDown[0]) {
                int route = dev.squidutils.fusion.hud.ShardTooltip.currentHoverRoute();
                if (route >= 0) {
                    client.setScreen(new dev.squidutils.hud.MultiStepScreen(client.screen, route));
                }
            }
            hotkeyWasDown[0] = down;
        });

        // Same edge-detected polling as the route hotkey above - see
        // QuickFuse's own doc for why a single keypress clicking one known
        // prompt is not the same thing as a macro.
        boolean[] quickFuseWasDown = {false};
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            var cfg = config();
            if (cfg == null) return;
            int key = cfg.fusion.settings.quickFuse.key;
            boolean down = key != GLFW.GLFW_KEY_UNKNOWN
                    && com.mojang.blaze3d.platform.InputConstants.isKeyDown(client.getWindow(), key);
            if (down && !quickFuseWasDown[0]) {
                dev.squidutils.fusion.QuickFuse.press(client);
            }
            quickFuseWasDown[0] = down;
        });

        LOG.info("[squidutils] ready - press \\ for settings");
    }

    /**
     * One-time fixups for a {@code config.json} written by an older build,
     * run before {@link ManagedConfig} ever loads the file.
     *
     * <p>Renaming a config field is not free: Gson silently drops whatever
     * sat under the old name rather than carrying it over, which from the
     * player's side looks exactly like "my settings keep resetting after an
     * update" - a real report, not a hypothetical one, after Profit Shards
     * went from one table to four ({@code profitShards} -> {@code profit1..4}
     * in {@code FusionTablesCategory}). This patches the raw JSON to the
     * shape the current classes expect before Gson ever sees it, so a
     * genuine rename does not read as data loss.
     *
     * <p>Safe by construction: a missing file (fresh install) is a no-op, and
     * anything this does not specifically recognise is left completely
     * alone - it never invents a migration for shapes it was not written for.
     */
    private static void migrateConfig(File file) {
        if (!file.isFile()) return;
        try {
            com.google.gson.JsonObject root;
            try (var reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
                root = com.google.gson.JsonParser.parseReader(reader).getAsJsonObject();
            }
            boolean changed = false;

            com.google.gson.JsonObject fusion = childObject(root, "fusion");
            com.google.gson.JsonObject tables = childObject(fusion, "tables");
            // 0.1.x: one "Profit Shards" table -> four independently
            // configured variants (profit1-4). The old table's own show/
            // rows/multiStep become the first variant's - it played the same
            // "the one Profit Shards table" role the old field did, so its
            // settings are the closest thing to a correct carry-over.
            if (tables != null && tables.has("profitShards") && !tables.has("profit1")) {
                com.google.gson.JsonObject old = tables.getAsJsonObject("profitShards");
                com.google.gson.JsonObject migrated = new com.google.gson.JsonObject();
                for (String field : new String[]{"show", "rows", "multiStep"}) {
                    if (old.has(field)) migrated.add(field, old.get(field));
                }
                tables.add("profit1", migrated);
                tables.remove("profitShards");
                changed = true;
            }

            // 0.3.x: "Shard Fusion" and "Fishing" each dropped their nested
            // "General" sub-page - master switches and shared display now
            // render straight on the parent category's own page instead (see
            // FusionCategory's class doc for why MoulConfig allows that), so
            // e.g. fusion.general.huntingWisdom needs to land on
            // fusion.huntingWisdom, the shape FusionCategory now expects,
            // rather than Gson silently dropping a detected Wisdom value or a
            // player's shopping-list/legend toggles on the rename.
            if (fusion != null) changed |= hoistIntoParent(fusion, "general");
            com.google.gson.JsonObject fishing = childObject(root, "fishing");
            if (fishing != null) changed |= hoistIntoParent(fishing, "general");

            // 0.4.x: "Bazaar" held its order-tracker fields (enabled,
            // chatEnabled, splash, sound) directly on its own page, the same
            // shape Shard Fusion and Fishing had before their own 0.3.x
            // migration above - now "Bazaar" is just a master switch (default
            // on, matching Fishing's own enabled field) with order tracking
            // moved to its own "Order Tracker" sub-page, alongside Order
            // Overlay and the new Order Value panel. The old `enabled` meant
            // "track my orders" specifically, not "bazaar features on at
            // all" - sinkIntoChild carries that value over as
            // orderTracker.enabled and removes the old top-level key, so the
            // new master-switch meaning gets its own (true) default instead
            // of silently inheriting the old tracking toggle's (usually
            // false) value.
            com.google.gson.JsonObject bazaar = childObject(root, "bazaar");
            if (bazaar != null && !bazaar.has("orderTracker")) {
                changed |= sinkIntoChild(bazaar, "orderTracker", "enabled", "chatEnabled", "splash", "sound");
            }

            com.google.gson.JsonObject general = childObject(root, "general");
            // Table index space grew from 3 slots (Recommended, Profit
            // Shards, XP) to 6 (Recommended, Profit Shards 1-4, XP) in the
            // same update - old slot 1 becomes the new profit1 slot (same
            // table), and old slot 2 (XP) moves to new slot 5 rather than
            // being silently inherited by profit2, an unrelated new panel.
            // normalise() backfills the null gaps with fresh defaults itself.
            if (general != null) {
                changed |= migrateTableSlots(general, "tablePos");
                changed |= migrateGraphSlots(general, "graphPos");
            }

            if (changed) {
                try (var writer = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8)) {
                    new com.google.gson.Gson().toJson(root, writer);
                }
                LOG.info("[squidutils] migrated config.json to the current settings layout");
            }
        } catch (Exception e) {
            LOG.warn("[squidutils] config migration failed, leaving config.json as-is", e);
        }
    }

    private static com.google.gson.JsonObject childObject(com.google.gson.JsonObject parent, String key) {
        return parent != null && parent.has(key) && parent.get(key).isJsonObject()
                ? parent.getAsJsonObject(key) : null;
    }

    /**
     * Merges every field of {@code parent.<childKey>} directly into {@code
     * parent}, then removes the now-empty wrapper - for a nested sub-category
     * whose options moved onto its parent's own page. {@code parent} already
     * having a same-named field wins (should never happen from a real
     * config.json, since the two shapes are mutually exclusive, but it means
     * a second migration run on an already-migrated file cannot clobber
     * anything).
     */
    private static boolean hoistIntoParent(com.google.gson.JsonObject parent, String childKey) {
        com.google.gson.JsonObject child = childObject(parent, childKey);
        if (child == null) return false;
        for (var entry : new java.util.ArrayList<>(child.entrySet())) {
            if (!parent.has(entry.getKey())) parent.add(entry.getKey(), entry.getValue());
        }
        parent.remove(childKey);
        return true;
    }

    /**
     * Moves the named fields out of {@code parent} into a new {@code
     * parent.<childKey>} object, removing them from {@code parent} itself -
     * the reverse of {@link #hoistIntoParent}, for a set of options that
     * used to render as loose fields on a category's own page and now live
     * under one of its sub-pages instead. A no-op (returns false, adds
     * nothing) if none of the named fields are actually present, so it is
     * safe to call on a config that never had them to begin with.
     */
    private static boolean sinkIntoChild(com.google.gson.JsonObject parent, String childKey, String... fields) {
        com.google.gson.JsonObject child = new com.google.gson.JsonObject();
        boolean moved = false;
        for (String field : fields) {
            if (parent.has(field)) {
                child.add(field, parent.get(field));
                parent.remove(field);
                moved = true;
            }
        }
        if (moved) parent.add(childKey, child);
        return moved;
    }

    private static boolean migrateTableSlots(com.google.gson.JsonObject general, String key) {
        if (!general.has(key) || !general.get(key).isJsonArray()) return false;
        var old = general.getAsJsonArray(key);
        if (old.size() != 3) return false;

        var fresh = new com.google.gson.JsonArray();
        fresh.add(old.get(0));
        fresh.add(old.get(1));
        for (int i = 0; i < 3; i++) fresh.add(com.google.gson.JsonNull.INSTANCE);
        fresh.set(5, old.get(2));
        general.add(key, fresh);
        return true;
    }

    /** As {@link #migrateTableSlots}, but for {@code graphPos}'s per-table
     *  array of three graph positions each, rather than one position. */
    private static boolean migrateGraphSlots(com.google.gson.JsonObject general, String key) {
        if (!general.has(key) || !general.get(key).isJsonArray()) return false;
        var old = general.getAsJsonArray(key);
        if (old.size() != 3) return false;

        var fresh = new com.google.gson.JsonArray();
        fresh.add(old.get(0));
        for (int i = 0; i < 4; i++) fresh.add(com.google.gson.JsonNull.INSTANCE);
        fresh.set(5, old.get(2));
        general.add(key, fresh);
        return true;
    }

    /** Translate the GUI config into what the scorer actually consumes. */
    private static Scorer.Settings settings() {
        SquidUtilsConfig c = config.getInstance();

        double tax = 0.0125
                - 0.00125 * Math.max(0, Math.min(2, c.fusion.settings.trading.bazaarFlipperLevel))
                - (c.fusion.settings.trading.communityTaxUpgrade ? 0.00125 : 0.0);
        tax = Math.max(0.0, tax);

        Set<String> rarities = FusionCategory.parseList(c.fusion.settings.filters.rarityFilter);
        Set<String> inputs = FusionCategory.parseList(c.fusion.settings.filters.inputBlacklist);
        Set<String> outputs = FusionCategory.parseList(c.fusion.settings.filters.outputBlacklist);

        return new Scorer.Settings(
                tax,
                c.fusion.settings.trading.buyMode == 0 ? Scorer.BuyMode.INSTA_BUY : Scorer.BuyMode.BUY_ORDER,
                c.fusion.settings.trading.sellMode == 0 ? Scorer.SellMode.SELL_OFFER : Scorer.SellMode.INSTA_SELL,
                c.fusion.settings.trading.captureShare,
                c.fusion.settings.advanced.hourAlpha,
                FusionCategory.parseNumber(c.fusion.settings.filters.minProfitPerFuse, 1000),
                FusionCategory.parseNumber(c.fusion.settings.filters.maxCostPerFuse, 0),
                c.fusion.settings.filters.minMovingWeek,
                c.fusion.settings.advanced.maxBookImpact,
                c.fusion.settings.advanced.maxPremiumOverReference,
                c.fusion.settings.advanced.minBookOrders,
                c.fusion.settings.advanced.requireReference,
                c.fusion.settings.trading.maxFillMinutes,
                c.fusion.settings.advanced.queueEfficiency,
                lower(inputs), lower(outputs), lower(rarities),
                // Each table sorts itself now, so the scorer's own ranking mode
                // is no longer what decides order.
                false,
                c.fusion.huntingWisdom,
                c.fusion.settings.trading.depthLimitThreshold,
                dev.squidutils.fusion.AttributeDetector.pureReptileChance(),
                c.fusion.settings.trading.depthLimitFlatTolerance);
    }

    /**
     * One {@link Scorer.Settings} per Profit Shards table (index 0-3 for
     * config's variants 1-4), sharing every field from {@link #settings()}
     * except buy/sell mode - so each table prices the market its own way
     * without needing four full copies of tax, filters and every other
     * scoring input to stay in sync by hand.
     */
    private static Scorer.Settings[] profitVariantSettings() {
        Scorer.Settings base = settings();
        SquidUtilsConfig c = config.getInstance();
        Scorer.Settings[] out = new Scorer.Settings[4];
        for (int i = 0; i < out.length; i++) {
            int variant = i + 1;
            Scorer.BuyMode buy = c.fusion.profitVariantBuyMode(variant) == 0
                    ? Scorer.BuyMode.INSTA_BUY : Scorer.BuyMode.BUY_ORDER;
            Scorer.SellMode sell = c.fusion.profitVariantSellMode(variant) == 0
                    ? Scorer.SellMode.SELL_OFFER : Scorer.SellMode.INSTA_SELL;
            out[i] = base.withMode(buy, sell);
        }
        return out;
    }

    /**
     * Open a shard's bazaar page.
     *
     * <p>Hypixel's /bz takes the display name, and shards are listed as
     * "&lt;name&gt; Shard" - searching the bare name pulls up the mob's other
     * items instead.
     */
    public static void openBazaar(String shardName) {
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player == null || mc.player.connection == null) return;
        mc.player.connection.sendCommand("bz " + shardName + " Shard");
    }

    private static void say(String message) {
        var player = net.minecraft.client.Minecraft.getInstance().player;
        if (player != null) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "§d[Squid Utils] §7" + message));
        }
    }

    /**
     * One-shot diagnostic for /squid debug tablist: dumps every tab list
     * entry's display text to the log.
     *
     * <p>Unlike chat and the action bar, tab list content is not something
     * the game logs anywhere on its own - live client state read on demand
     * is the only way to see it, hence a direct dump instead of a passive
     * capture like {@code SessionTracker}'s. Prompted by Hunting XP not
     * showing up on the action bar at all in a live capture, despite that
     * being how another skill-tracking mod (SkyHanni) reads every other
     * SkyBlock skill - it also falls back to the tab list for exactly this
     * kind of case, which is the lead this follows.
     */
    private static void dumpTabList() {
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player == null || mc.player.connection == null) return;
        var entries = mc.player.connection.getListedOnlinePlayers();
        LOG.info("[squidutils] tab list: {} entries", entries.size());
        for (var info : entries) {
            var display = info.getTabListDisplayName();
            String text = display != null ? display.getString() : "(no tab name)";
            LOG.info("[squidutils] tab: {}", text);
        }
        say("dumped " + entries.size() + " tab list entries to the log");
    }

    private static Set<String> lower(Set<String> in) {
        return in.stream().map(s -> s.toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toSet());
    }
}
