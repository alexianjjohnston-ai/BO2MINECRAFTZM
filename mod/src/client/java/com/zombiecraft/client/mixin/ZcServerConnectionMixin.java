package com.zombiecraft.client.mixin;

import com.zombiecraft.client.net.ProxyProtocolDecoder;
import io.netty.channel.Channel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Online play: a world opened to LAN accepts the PROXY protocol header that tunnels (playit.gg) put in front of each player's connection. */
@Mixin(targets = "net.minecraft.server.network.ServerConnectionListener$1")
public abstract class ZcServerConnectionMixin {
	@Inject(method = "initChannel", at = @At("HEAD"))
	private void zombiecraft$proxyHeader(Channel channel, CallbackInfo ci) {
		channel.pipeline().addFirst("zc_proxy_protocol", new ProxyProtocolDecoder());
	}
}
