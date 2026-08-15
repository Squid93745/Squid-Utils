package dev.squidutils.mixin;

import dev.squidutils.SquidUtils;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;
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
 * <p>Every packet the client receives passes through here, not just this
 * one type, so the check has to be cheap - a single {@code instanceof} and
 * an early return covers that.
 */
@Mixin(value = Connection.class, priority = 100)
public class ConnectionMixin {

    @Inject(method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;)V",
            at = @At("HEAD"))
    private void squidutils$onChannelRead0(ChannelHandlerContext ctx, Packet<?> packet, CallbackInfo ci) {
        if (!(packet instanceof ClientboundSetActionBarTextPacket actionBar)) return;
        var text = actionBar.text();
        if (text == null) return;
        SquidUtils.tracker().onChat(text.getString(), true);
    }
}
