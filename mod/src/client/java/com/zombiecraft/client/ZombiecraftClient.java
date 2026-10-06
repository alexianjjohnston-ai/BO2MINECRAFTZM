package com.zombiecraft.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.zombiecraft.client.audio.AudioCache;
import com.zombiecraft.client.audio.CuePlayer;
import com.zombiecraft.client.hud.ZcHud;
import com.zombiecraft.entity.ZcEntities;
import com.zombiecraft.net.Payloads;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.ZombieRenderer;
import net.minecraft.world.InteractionResult;
import org.lwjgl.glfw.GLFW;

public class ZombiecraftClient implements ClientModInitializer {
	public static volatile Payloads.StateSync state = new Payloads.StateSync(0, 0, 0, -1, 0, "", "", "", false, 0, 0, 0);
	private static KeyMapping interactKey, reloadKey;
	private static boolean lastFire, lastInteract, lastAttack;

	@Override
	public void onInitializeClient() {
		EntityRendererRegistry.register(ZcEntities.ZOMBIE, ZombieRenderer::new);
		AutoWorld.register();

		interactKey = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.zombiecraft.interact", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F, "key.categories.zombiecraft"));
		reloadKey = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.zombiecraft.reload", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_R, "key.categories.zombiecraft"));

		// blocks and items are never "used" the vanilla way: right click is the trigger, F is use, left click is the knife
		UseBlockCallback.EVENT.register((player, level, hand, hit) -> InteractionResult.FAIL);
		UseItemCallback.EVENT.register((player, level, hand) -> InteractionResult.FAIL);
		AttackBlockCallback.EVENT.register((player, level, hand, pos, dir) -> InteractionResult.FAIL);

		ClientPlayNetworking.registerGlobalReceiver(Payloads.StateSync.TYPE, (payload, ctx) -> state = payload);
		ClientPlayNetworking.registerGlobalReceiver(Payloads.CuePlay.TYPE, (payload, ctx) -> CuePlayer.play(payload));
		ClientPlayNetworking.registerGlobalReceiver(Payloads.Shot.TYPE, (payload, ctx) -> {
			Minecraft mc = Minecraft.getInstance();
			net.minecraft.client.Screenshot.grab(mc.gameDirectory, "zc-" + payload.name() + ".png", mc.getMainRenderTarget(), c -> { });
		});
		ClientPlayNetworking.registerGlobalReceiver(Payloads.CueStop.TYPE, (payload, ctx) -> CuePlayer.stop(payload.cue()));

		ClientTickEvents.START_CLIENT_TICK.register(ZombiecraftClient::pollInput);
		HudRenderCallback.EVENT.register(ZcHud::render);
		ClientLifecycleEvents.CLIENT_STARTED.register(c -> AudioCache.prepareAsync());
	}

	private static void pollInput(Minecraft mc) {
		if (mc.player == null || mc.level == null) { lastFire = lastInteract = lastAttack = false; return; }
		// F used to swap hands: swallow it so the gun never ends up in the off hand
		while (mc.options.keySwapOffhand.consumeClick()) { }
		boolean playing = mc.screen == null && mc.mouseHandler.isMouseGrabbed();
		boolean fire = playing && mc.options.keyUse.isDown();
		boolean interact = playing && interactKey.isDown();
		boolean attack = playing && mc.options.keyAttack.isDown();
		boolean fireClick = false;
		while (mc.options.keyUse.consumeClick()) fireClick = playing;
		boolean reload = false;
		while (reloadKey.consumeClick()) reload = playing;
		boolean melee = attack && !lastAttack;
		if (fire != lastFire || interact != lastInteract || reload || melee || fire || fireClick) {
			net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(new Payloads.Input(fire, fireClick, interact, reload, melee));
		}
		lastFire = fire; lastInteract = interact; lastAttack = attack;
	}
}
