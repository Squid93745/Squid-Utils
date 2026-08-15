package dev.squidutils.mixin;

import dev.squidutils.SquidUtils;
import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Diagnostic only, for now: logs every sound the client is about to play,
 * gated behind {@code /squid debug sound} so it stays silent by default -
 * sound events fire constantly (footsteps, ambience, UI clicks), so this
 * would flood the log left on.
 *
 * <p>The idea is borrowed from Feesh, a fishing-focused SkyBlock mod: its
 * {@code EfficiencyTracker} does not read fishing skill XP from any chat
 * line at all - it listens for the vanilla XP-orb-pickup sound Hypixel
 * plays alongside a skill XP grant, via the exact same kind of mixin as
 * this one. That is a genuinely different signal from the chat-message
 * approach {@link dev.squidutils.fusion.SessionTracker} already uses for
 * fuses - this logs every sound around an actual fusion first, the same
 * "capture real data before writing a pattern" approach that fixed the
 * FUSION chat regex, so whatever Hypixel actually plays for a Hunting XP
 * grant can be confirmed before anything is built to react to it.
 */
@Mixin(SoundEngine.class)
public class SoundEngineMixin {

    @Inject(method = "play", at = @At("HEAD"))
    private void squidutils$onPlay(SoundInstance sound, CallbackInfoReturnable<SoundEngine.PlayResult> cir) {
        if (!SquidUtils.logSounds()) return;

        float volume = -1;
        float pitch = -1;
        if (sound instanceof AbstractSoundInstance abstractSound) {
            var accessor = (AbstractSoundInstanceAccessor) abstractSound;
            volume = accessor.squidutils$rawVolume();
            pitch = accessor.squidutils$rawPitch();
        }

        SquidUtils.LOG.info("[squidutils] sound: {} source={} volume={} pitch={}",
                sound.getIdentifier(), sound.getSource(), volume, pitch);
    }
}
