package dev.squidutils.hud;

import dev.squidutils.SquidUtils;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;

/**
 * Plays a notification sound by its resource id, and dumps every valid id to
 * a text file - shared by every tracker's own "Test Sound"/"List of Sounds"
 * buttons, the same two operations {@code OrderTracker} and {@code
 * FrozenBlazeOverlay} each already have their own copy of. Those two are left
 * alone rather than migrated here - not broken, not worth the risk of
 * touching already-shipped code for a pure de-duplication - but every new
 * tracker from here on shares this one instead of growing a fourth copy.
 */
public final class Sounds {

    private Sounds() {}

    public static void play(String soundId, float pitch) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        Identifier id = Identifier.tryParse(soundId);
        SoundEvent event = id == null ? null : BuiltInRegistries.SOUND_EVENT.getValue(id);
        if (event == null) {
            if (mc.player != null) {
                mc.player.sendSystemMessage(Component.literal(
                        "§d[Squid Utils] §7Unknown sound id: §f" + soundId));
            }
            return;
        }
        mc.getSoundManager().play(SimpleSoundInstance.forUI(event, pitch));
    }

    /** Wired to a config screen's "List of Sounds" button. */
    public static void list() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null) return;
        try {
            Path dir = FabricLoader.getInstance().getConfigDir().resolve(SquidUtils.MOD_ID);
            Files.createDirectories(dir);
            Path file = dir.resolve("sounds.txt");

            var ids = new ArrayList<String>();
            for (Identifier id : BuiltInRegistries.SOUND_EVENT.keySet()) ids.add(id.toString());
            Collections.sort(ids);
            Files.write(file, ids, StandardCharsets.UTF_8);

            mc.player.sendSystemMessage(Component.literal(
                    "§d[Squid Utils] §7" + ids.size() + " sound ids written to §f" + file));
        } catch (Exception e) {
            SquidUtils.LOG.warn("[squidutils] could not write sounds.txt", e);
        }
    }
}
