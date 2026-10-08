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
	public static volatile Payloads.StateSync state = new Payloads.StateSync(0, 0, 0, -1, 0, "", "", "", false, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
	private static KeyMapping interactKey, reloadKey, proneKey, meleeKey, grenadeKey;
	private static boolean lastFire, lastInteract, lastAds, lastGrenade;
	private static int lastWeaponSlot;

	@Override
	public void onInitializeClient() {
		EntityRendererRegistry.register(ZcEntities.BOX, com.zombiecraft.client.render.ZcBoxRenderer::new);
		EntityRendererRegistry.register(ZcEntities.PROP, com.zombiecraft.client.render.ZcPropRenderer::new);
		EntityRendererRegistry.register(ZcEntities.GRENADE, com.zombiecraft.client.render.ZcPropRenderer::new);
		EntityRendererRegistry.register(ZcEntities.ZOMBIE, com.zombiecraft.client.render.ZcZombieRenderer::new);
		AutoWorld.register();
		com.zombiecraft.client.menu.Bo2Menus.register();

		interactKey = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.zombiecraft.interact", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F, "key.categories.zombiecraft"));
		proneKey = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.zombiecraft.prone", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_Z, "key.categories.zombiecraft"));
		reloadKey = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.zombiecraft.reload", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_R, "key.categories.zombiecraft"));
		meleeKey = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.zombiecraft.melee", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_V, "key.categories.zombiecraft"));
		grenadeKey = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.zombiecraft.grenade", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_G, "key.categories.zombiecraft"));

		// blocks and items are never "used" the vanilla way: left click is the trigger, right click aims down the sight, F is use, V is the knife
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
		state = new Payloads.StateSync(Payloads.PHASE_IDLE, 0, 0, -1, 0, "", "", "", false, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
		ZcRoster.clear();
		lastFire = lastInteract = lastAds = lastGrenade = false;
		ViewState.reset();
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

	/** BO2 PC defaults where they differ from Minecraft's: crouch is C and sprint is the left Shift key. Everything else (WASD, Space, Tab, T, 1-9, both mouse buttons) already matches. */
	private static final java.util.Map<String, Integer> BO2_KEYS = java.util.Map.of("key.sneak", GLFW.GLFW_KEY_C, "key.sprint", GLFW.GLFW_KEY_LEFT_SHIFT);

	/** The key a binding has by default in this game. */
	public static InputConstants.Key defaultKey(KeyMapping km) {
		Integer k = BO2_KEYS.get(km.getName());
		return k == null ? km.getDefaultKey() : InputConstants.Type.KEYSYM.getOrCreate(k);
	}

	/** What BO2's controls screen calls each action. */
	public static String label(KeyMapping km) {
		return switch (km.getName()) {
			case "key.forward" -> "MOVE FORWARD";
			case "key.back" -> "MOVE BACKWARD";
			case "key.left" -> "MOVE LEFT";
			case "key.right" -> "MOVE RIGHT";
			case "key.jump" -> "JUMP";
			case "key.sneak" -> "CROUCH";
			case "key.sprint" -> "SPRINT";
			case "key.attack" -> "FIRE WEAPON";
			case "key.use" -> "AIM DOWN THE SIGHT";
			case "key.playerlist" -> "SCOREBOARD";
			case "key.chat" -> "CHAT";
			case "key.screenshot" -> "SCREENSHOT";
			case "key.fullscreen" -> "TOGGLE FULLSCREEN";
			case "key.zombiecraft.reload" -> "RELOAD WEAPON";
			case "key.zombiecraft.melee" -> "MELEE ATTACK";
			case "key.zombiecraft.grenade" -> "FRAG GRENADE";
			case "key.zombiecraft.interact" -> "USE / BUY / REPAIR";
			case "key.zombiecraft.prone" -> "PRONE";
			default -> km.getName().startsWith("key.hotbar.")
					? "WEAPON SLOT " + km.getName().substring("key.hotbar.".length())
					: net.minecraft.client.resources.language.I18n.get(km.getName()).toUpperCase();
		};
	}

	/** Only these bindings exist in the game; every other Minecraft binding is hidden and kept unbound. */
	public static boolean keyNeeded(KeyMapping km) {
		return NEEDED_KEYS.contains(km.getName()) || km.getName().startsWith("key.zombiecraft");
	}

	/** Unbinds the Minecraft keys the game does not use (a saved F for swap-hands would swallow the interact key), and moves crouch and sprint to BO2's keys once. */
	private static void stripUnusedKeys(Minecraft mc) {
		boolean changed = false;
		for (KeyMapping km : mc.options.keyMappings) {
			if (keyNeeded(km) || km.isUnbound()) continue;
			km.setKey(InputConstants.UNKNOWN);
			changed = true;
		}
		java.nio.file.Path marker = mc.gameDirectory.toPath().resolve("zombiecraft_controls_v1");
		if (!java.nio.file.Files.exists(marker)) {
			for (KeyMapping km : mc.options.keyMappings) if (BO2_KEYS.containsKey(km.getName())) km.setKey(defaultKey(km));
			try { java.nio.file.Files.writeString(marker, "BO2 crouch (C) and sprint (left Shift) applied once\n"); } catch (java.io.IOException ignored) { }
			changed = true;
		}
		if (changed) { KeyMapping.resetMapping(); mc.options.save(); }
	}

	private static void pollInput(Minecraft mc) {
		stripUnusedKeys(mc);
		if (mc.player == null || mc.level == null) { lastFire = lastInteract = lastAds = lastGrenade = false; ViewState.reset(); return; }
		if (PapBench.controlsInput()) { lastFire = lastInteract = lastAds = lastGrenade = false; return; }
		keepWeaponSelected(mc);
		// F used to swap hands: swallow it so the gun never ends up in the off hand
		while (mc.options.keySwapOffhand.consumeClick()) { }
		boolean playing = mc.screen == null && mc.mouseHandler.isMouseGrabbed();
		// BO2 PC: left mouse fires, right mouse aims down the sight, V is the knife, G throws the frag grenade, F uses
		boolean fire = playing && mc.options.keyAttack.isDown();
		boolean ads = playing && mc.options.keyUse.isDown();
		boolean interact = playing && interactKey.isDown();
		boolean grenade = playing && grenadeKey.isDown();
		boolean fireClick = false;
		while (mc.options.keyAttack.consumeClick()) fireClick = playing;
		while (mc.options.keyUse.consumeClick()) { }
		boolean reload = false;
		while (reloadKey.consumeClick()) reload = playing;
		boolean melee = false;
		while (meleeKey.consumeClick()) melee = playing;
		boolean prone = false;
		while (proneKey.consumeClick()) prone = playing;
		while (grenadeKey.consumeClick()) { }
		ViewState.update(mc, playing, ads, grenade, state.grenades());
		boolean aiming = ViewState.ads();
		if (fire != lastFire || interact != lastInteract || aiming != lastAds || grenade != lastGrenade || reload || melee || fire || fireClick || prone) {
			net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(new Payloads.Input(fire, fireClick, interact, reload, melee, prone, aiming, grenade));
		}
		lastFire = fire; lastInteract = interact; lastAds = aiming; lastGrenade = grenade;
	}
}
