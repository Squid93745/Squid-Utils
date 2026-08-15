package dev.squidutils.mixin;

import dev.squidutils.SquidUtils;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Reads the action-bar packet at the earliest possible point - before any
 * other installed mod's own action-bar handling gets a chance to touch it.
 *
 * <p>Confirmed by a live A/B test: Hunting XP tracked correctly with only
 * this mod installed, but not in the full pack - so {@link GuiMixin}'s hook
 * on {@code Gui.setOverlayMessage} was the right idea, it is just arriving
 * too late. Something else in the pack intercepts the packet first and
 * never lets the original text reach that method - several installed mods
 * (SkyHanni and Skyblocker both, at least) have their own action-bar
 * processing features that plausibly do exactly that. Reading the packet
 * directly here, at a higher Mixin priority than the 1000 default almost
 * everything else uses, runs before whatever that is gets the chance to
 * cancel or rewrite it.
 *
 * <p>Feeds into {@link dev.squidutils.fusion.SessionTracker#onChat} the
 * same way {@link GuiMixin} does - kept alongside it rather than replacing
 * it, as a fallback for however {@code Gui.setOverlayMessage} might still
 * end up called from somewhere else. The existing repeat-suppression and
 * the capture list's own {@code Set}-based dedup already guard against
 * processing the same text twice if both fire for one message.
 */
@Mixin(value = ClientPacketListener.class, priority = 100)
public class ActionBarPacketMixin {

    @Inject(method = "setActionBarText", at = @At("HEAD"))
    private void squidutils$onActionBarPacket(ClientboundSetActionBarTextPacket packet, CallbackInfo ci) {
        var text = packet.text();
        if (text == null) return;
        SquidUtils.tracker().onChat(text.getString(), true);
    }
}
