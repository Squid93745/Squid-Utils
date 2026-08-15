package dev.squidutils.mixin;

import dev.squidutils.SquidUtils;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundSetExperiencePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Diagnostic only, gated behind {@code /squid debug exp}: logs every vanilla
 * "set experience" packet - progress, total XP and level - the moment it
 * arrives.
 *
 * <p>Every hook this project has for Hunting XP so far (chat events, {@link
 * GuiMixin}, {@link ActionBarPacketMixin}) is text-based. If a mod is
 * instead repurposing the actual vanilla XP bar for skill display - a real,
 * documented technique (SkyHanni ships an option to show SkyBlock progress
 * on it) - the data would arrive as this packet's raw numbers, with no
 * Component text anywhere, invisible to every one of those hooks no matter
 * how early they run. This checks that possibility directly rather than
 * ruling it out by assumption.
 */
@Mixin(ClientPacketListener.class)
public class ExperiencePacketMixin {

    @Inject(method = "handleSetExperience", at = @At("HEAD"))
    private void squidutils$onSetExperience(ClientboundSetExperiencePacket packet, CallbackInfo ci) {
        if (!SquidUtils.logExp()) return;
        SquidUtils.LOG.info("[squidutils] experience: progress={} total={} level={}",
                packet.getExperienceProgress(), packet.getTotalExperience(), packet.getExperienceLevel());
    }
}
