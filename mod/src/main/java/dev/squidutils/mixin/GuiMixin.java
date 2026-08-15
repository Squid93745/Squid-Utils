package dev.squidutils.mixin;

import dev.squidutils.SquidUtils;
import net.minecraft.client.gui.Gui;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Catches action-bar text sent via a dedicated {@code
 * ClientboundSetActionBarTextPacket}, which calls {@code
 * Gui.setOverlayMessage} directly and never fires Fabric's own chat-message
 * events - confirmed by disassembling {@code
 * ClientPacketListener.setActionBarText} in this exact game version.
 *
 * <p>{@code ClientReceiveMessageEvents.GAME}, already registered in {@code
 * SquidUtils}, only covers the <em>other</em> way text ends up in the same
 * on-screen spot: a regular system chat packet with its overlay flag set.
 * Both are real, independent paths Hypixel can use for the exact same
 * visual location, and only one of them was ever actually being listened
 * to - which is why "+2 SkyBlock XP" showed up fine while Hunting's own
 * action-bar text never did, even though both are equally real text on
 * screen. Hooking this method directly, rather than either packet
 * separately, covers whichever one is actually used without needing to
 * know that in advance.
 *
 * <p>Feeds straight into {@link dev.squidutils.fusion.SessionTracker#onChat}
 * as an overlay line, same as the chat-event path - its existing
 * repeat-suppression and the {@code Set}-based capture list both already
 * protect against processing the same text twice if a future case somehow
 * arrives via both paths for the same message.
 */
@Mixin(Gui.class)
public class GuiMixin {

    @Inject(method = "setOverlayMessage", at = @At("HEAD"))
    private void squidutils$onSetOverlayMessage(Component component, boolean animateColor, CallbackInfo ci) {
        if (component == null) return;
        SquidUtils.tracker().onChat(component.getString(), true);
    }
}
