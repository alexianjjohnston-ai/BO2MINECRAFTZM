package com.zombiecraft.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.zombiecraft.client.audio.AudioCache;
import com.zombiecraft.client.audio.CuePlayer;
import com.zombiecraft.client.hud.ZcHud;
import com.zombiecraft.entity.ZcEntities;
import com.zombiecraft.game.FeedbackBench;
import com.zombiecraft.game.PapBench;
import com.zombiecraft.item.ModItems;
import com.zombiecraft.net.Payloads;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionResult;
import org.lwjgl.glfw.GLFW;

public class ZombiecraftClient implements ClientModInitializer {
	public static volatile Payloads.StateSync state = new Payloads.StateSync(0, 0, 0, -1, 0, "", "", "", false, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
	private static KeyMapping interactKey, reloadKey, proneKey;
	private static boolean lastFire, lastInteract, lastAttack;
	private static int lastWeaponSlot;

	@Override
	public void onInitializeClient() {
		EntityRendererRegistry.register(ZcEntities.BOX, com.zombiecraft.client.render.ZcBoxRenderer::new);
		EntityRendererRegistry.register(ZcEntities.PROP, com.zombiecraft.client.render.ZcPropRenderer::new);
		EntityRendererRegistry.register(ZcEntities.ZOMBIE, com.zombiecraft.client.render.ZcZombieRenderer::new);
		AutoWorld.register();
		com.zombiecraft.client.menu.Bo2Menus.register();

		interactKey = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.zombiecraft.interact", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F, "key.categories.zombiecraft"));
		proneKey = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.zombiecraft.prone", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_Z, "key.categories.zombiecraft"));
		reloadKey = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.zombiecraft.reload", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_R, "key.categories.zombiecraft"));

		// blocks and items are never "used" the vanilla way: right click is the trigger, F is use, left click is the knife
		UseBlockCallback.EVENT.register((player, level, hand, hit) -> InteractionResult.FAIL);
		UseItemCallback.EVENT.register((player, level, hand) -> InteractionResult.FAIL);
		AttackBlockCallback.EVENT.register((player, level, hand, pos, dir) -> InteractionResult.FAIL);

		ClientPlayNetworking.registerGlobalReceiver(Payloads.StateSync.TYPE, (payload, ctx) -> state = payload);
		ClientPlayNetworking.registerGlobalReceiver(Payloads.EndMatch.TYPE, (payload, ctx) -> com.zombiecraft.client.menu.Bo2Menus.endGame());
		ClientPlayNetworking.registerGlobalReceiver(Payloads.Roster.TYPE, (payload, ctx) -> ZcRoster.set(payload.entries()));
		ClientPlayNetworking.registerGlobalReceiver(Payloads.CombatFeedback.TYPE, (payload, ctx) -> {
			GunFeedback.accept(payload);
			FeedbackBench.received(payload);
		});
		ClientPlayNetworking.registerGlobalReceiver(Payloads.HitDirection.TYPE, (payload, ctx) -> com.zombiecraft.client.hud.ZcHud.hitFrom(payload.x(), payload.z()));
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> resetSession());
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> resetSession());
		ClientPlayNetworking.registerGlobalReceiver(Payloads.CuePlay.TYPE, (payload, ctx) -> CuePlayer.play(payload));
		ClientPlayNetworking.registerGlobalReceiver(Payloads.Shot.TYPE, (payload, ctx) -> {
			Minecraft mc = Minecraft.getInstance();
			net.minecraft.client.Screenshot.grab(mc.gameDirectory, "zc-" + payload.name() + ".png", mc.getMainRenderTarget(), c -> { });
		});
		ClientPlayNetworking.registerGlobalReceiver(Payloads.CueStop.TYPE, (payload, ctx) -> CuePlayer.stop(payload.cue()));

		ClientTickEvents.START_CLIENT_TICK.register(ZombiecraftClient::pollInput);
		// the narrator never turns on: no option, no Ctrl+B hotkey, and a saved "on" is switched back off
		ClientTickEvents.START_CLIENT_TICK.register(mc -> {
			var o = mc.options;
			if (o.narrator().get() != net.minecraft.client.NarratorStatus.OFF) o.narrator().set(net.minecraft.client.NarratorStatus.OFF);
			if (o.narratorHotkey().get()) o.narratorHotkey().set(false);
		});
		// dev: -Dzombiecraft.debugEndGame=true leaves the match from inside after a while, to check the screen that follows
		if (Boolean.getBoolean("zombiecraft.debugEndGame")) {
			int[] ticks = {0};
			ClientTickEvents.END_CLIENT_TICK.register(mc -> {
				ticks[0]++;
				if (mc.level != null && ticks[0] == 400) com.zombiecraft.client.menu.Bo2Menus.endGame();
				if (ticks[0] == 560) net.minecraft.client.Screenshot.grab(mc.gameDirectory, mc.getMainRenderTarget(), c -> {});
			});
		}
		// dev: -Dzombiecraft.debugBody=stand|prone|down looks at the own character from the front and saves screenshots (see ZcPlayerBody)
		if (!System.getProperty("zombiecraft.debugBody", "").isEmpty()) {
			int[] t = {0};
			ClientTickEvents.END_CLIENT_TICK.register(mc -> {
				if (mc.player == null || ++t[0] < 200) return;
				mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_FRONT);
				mc.player.setXRot(12f);
				if (t[0] % 40 == 0 && t[0] <= 400) net.minecraft.client.Screenshot.grab(mc.gameDirectory, "zc-body" + t[0] + ".png", mc.getMainRenderTarget(), c -> { });
			});
		}
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			keepWeaponSelected(mc);
			GunFeedback.tick(mc);
			ZcHud.tick(mc);
			com.zombiecraft.client.audio.Footsteps.tick(mc);
			if (FeedbackBench.finished() || PapBench.finished()) mc.stop();
		});
		HudRenderCallback.EVENT.register(ZcHud::render);
		ClientLifecycleEvents.CLIENT_STARTED.register(c -> { AudioCache.prepareAsync(); com.zombiecraft.client.render.ModelCache.ensureAsync(); });
	}

	private static void resetSession() {
		state = new Payloads.StateSync(Payloads.PHASE_IDLE, 0, 0, -1, 0, "", "", "", false, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
		ZcRoster.clear();
		lastFire = lastInteract = lastAttack = false;
		lastWeaponSlot = 0;
		GunFeedback.reset();
		ZcHud.reset();
	}

	/** Number keys aimed at hidden, empty slots keep the last usable weapon selected. */
	private static void keepWeaponSelected(Minecraft mc) {
		if (!ZcHud.usesWeaponHud(mc)) return;
		var inventory = mc.player.getInventory();
		if (ModItems.weaponOf(inventory.getItem(inventory.selected)) != null) {
			lastWeaponSlot = inventory.selected;
			return;
		}
		if (ModItems.weaponOf(inventory.getItem(lastWeaponSlot)) != null) {
			inventory.setSelectedHotbarSlot(lastWeaponSlot);
			return;
		}
		for (int slot = 0; slot < 9; slot++) {
			if (ModItems.weaponOf(inventory.getItem(slot)) == null) continue;
			inventory.setSelectedHotbarSlot(slot);
			lastWeaponSlot = slot;
			return;
		}
	}

	private static final java.util.Set<String> NEEDED_KEYS = new java.util.HashSet<>(java.util.List.of(
		"key.forward", "key.left", "key.back", "key.right", "key.jump", "key.sneak", "key.sprint",
		"key.attack", "key.use", "key.playerlist", "key.chat", "key.screenshot", "key.fullscreen",
		"key.hotbar.1", "key.hotbar.2", "key.hotbar.3", "key.hotbar.4", "key.hotbar.5",
		"key.hotbar.6", "key.hotbar.7", "key.hotbar.8", "key.hotbar.9"));

	/** Only these bindings exist in the game; every other Minecraft binding is hidden and kept unbound. */
	public static boolean keyNeeded(KeyMapping km) {
		return NEEDED_KEYS.contains(km.getName()) || km.getName().startsWith("key.zombiecraft");
	}

	/** Unbinds the Minecraft keys the game does not use (a saved F for swap-hands would swallow the interact key). */
	private static void stripUnusedKeys(Minecraft mc) {
		boolean changed = false;
		for (KeyMapping km : mc.options.keyMappings) {
			if (keyNeeded(km) || km.isUnbound()) continue;
			km.setKey(InputConstants.UNKNOWN);
			changed = true;
		}
		if (changed) { KeyMapping.resetMapping(); mc.options.save(); }
	}

	private static void pollInput(Minecraft mc) {
		stripUnusedKeys(mc);
		if (mc.player == null || mc.level == null) { lastFire = lastInteract = lastAttack = false; return; }
		if (PapBench.controlsInput()) { lastFire = lastInteract = lastAttack = false; return; }
		keepWeaponSelected(mc);
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
		boolean prone = false;
		while (proneKey.consumeClick()) prone = playing;
		if (fire != lastFire || interact != lastInteract || reload || melee || fire || fireClick || prone) {
			net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(new Payloads.Input(fire, fireClick, interact, reload, melee, prone));
		}
		lastFire = fire; lastInteract = interact; lastAttack = attack;
	}
}
