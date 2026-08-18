package dev.squidutils.mixin;

import dev.squidutils.SquidUtils;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.BundlePacket;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Reads the action-bar packet at the earliest point that exists: the raw
 * Netty callback every inbound packet passes through before Minecraft does
 * any type-specific dispatch at all.
 *
 * <p>{@link ActionBarPacketMixin} targets {@code ClientPacketListener}'s own
 * handler method - which turned out to be no earlier in practice than the
 * packet's own {@code handle()}, since one calls the other in a single line
 * (confirmed by disassembling both). Neither one is actually "first": both
 * sit downstream of whatever type-specific dispatch happens to get there,
 * which is exactly where another mod's own handling could plausibly get in
 * ahead of a normal-priority mixin. {@code channelRead0} is upstream of all
 * of that - the packet has only just been decoded off the wire and handed
 * to Minecraft, before it has been routed to any handler whatsoever.
 *
 * <p>Bundled packets are the gap that mattered: {@code channelRead0} hands
 * off to {@code Connection.genericsFtw}, which just calls {@code
 * packet.handle(listener)} - for a {@code BundlePacket} that resolves to
 * {@code ClientGamePacketListener.handleBundlePacket}, which is what
 * actually unwraps the bundle and dispatches each sub-packet's own {@code
 * handle()} in turn (confirmed by disassembling {@code Connection} and
 * {@code ClientboundBundlePacket} - neither {@code channelRead0} nor {@code
 * genericsFtw} ever sees the individual sub-packets). Bundling is a normal
 * vanilla technique for applying several updates atomically, and Hypixel
 * plausibly reaches for it around the action bar along with whatever else
 * it is updating that tick. A bundled action-bar packet was invisible to
 * the {@code instanceof} check below - the top-level packet here is the
 * bundle, not what is inside it - which left this mixin's whole reason to
 * exist (being upstream of every other mod's own action-bar handling)
 * defeated on exactly the ticks it mattered most. SkyHanni's own equivalent
 * hook ({@code MixinConnection}, on {@code genericsFtw}) unwraps bundles
 * for the same reason; recursing here does the same one level up.
 *
 * <p>Every packet the client receives passes through here, not just this
 * one type, so the check has to be cheap - a single {@code instanceof} and
 * an early return covers the non-bundle case, which is the overwhelming
 * majority of traffic.
 */
@Mixin(value = Connection.class, priority = 100)
public class ConnectionMixin {

    @Inject(method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;)V",
            at = @At("HEAD"))
    private void squidutils$onChannelRead0(ChannelHandlerContext ctx, Packet<?> packet, CallbackInfo ci) {
        squidutils$handle(packet);
    }

    private static void squidutils$handle(Packet<?> packet) {
        if (packet instanceof BundlePacket<?> bundle) {
            // Bundles are not documented to nest, but recursing rather than
            // assuming one level costs nothing and stays correct either way.
            for (Packet<?> sub : bundle.subPackets()) {
                squidutils$handle(sub);
            }
            return;
        }
        if (!(packet instanceof ClientboundSetActionBarTextPacket actionBar)) return;
        var text = actionBar.text();
        if (text == null) return;
        SquidUtils.tracker().onChat(text.getString(), true);
    }
}
