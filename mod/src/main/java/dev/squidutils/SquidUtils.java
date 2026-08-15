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

import java.io.InputStream;
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

        config = ManagedConfig.create(dir.resolve("config.json").toFile(), SquidUtilsConfig.class);

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
                () -> config.getInstance().fusion.maxRows());
        // Backfill runs on the engine's own worker after a refresh, so it never
        // touches the render thread and always has a populated table to work
        // out which shards matter.
        var backfill = new dev.squidutils.fusion.engine.HistoryBackfill(engine, data);
        engine.setBackfill(() -> {
            var cfg = config.getInstance();
            if (backfill.needsRun(cfg)) backfill.run(cfg, settings());
        });

        engine.start(config.getInstance().fusion.settings.advanced.refreshSeconds);

        FusionHud hud = new FusionHud(engine, SquidUtils::config);
        HudElementRegistry.addLast(
                Identifier.fromNamespaceAndPath(MOD_ID, "panel"), hud);

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
                            .register((s, gfx, mx, my, tick) -> hud.drawUnderScreen(gfx, mx, my));

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
                .register((message, overlay) -> TRACKER.onChat(message.getString(), overlay));
        net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents.CHAT
                .register((message, signed, sender, params, time) ->
                        TRACKER.onChat(message.getString(), false));

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

        LOG.info("[squidutils] ready - press \\ for settings");
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
                c.fusion.general.huntingWisdom);
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

    private static Set<String> lower(Set<String> in) {
        return in.stream().map(s -> s.toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toSet());
    }
}
