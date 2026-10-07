package com.zombiecraft.client;

import com.zombiecraft.ZombiecraftMod;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.world.Difficulty;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

/**
 * With -Dzombiecraft.autoplay=true the title screen is skipped: the flat "Zombiecraft" world is created (first launch) or
 * opened, and the game starts by itself. This is what makes the packaged mashup start with one press of Play.
 */
public final class AutoWorld {
	private AutoWorld() {}

	public static final String WORLD = "Block Ops 2";
	private static boolean done;

	public static void register() {
		if (!Boolean.getBoolean("zombiecraft.autoplay") || System.getProperty("zombiecraft.debugJoin") != null) return;
		ScreenEvents.AFTER_INIT.register((mc, screen, w, h) -> {
			if (done || !(screen instanceof TitleScreen)) return;
			done = true;
			mc.execute(() -> start(mc, screen));
		});
	}

	public static void start(Minecraft mc, net.minecraft.client.gui.screens.Screen title) {
		com.zombiecraft.client.audio.MenuAudio.stopMusic();
		try {
			if (mc.getLevelSource().levelExists(WORLD)) {
				ZombiecraftMod.LOG.info("Block Ops 2: opening the existing world");
				mc.createWorldOpenFlows().openWorld(WORLD, () -> {});
			} else {
				ZombiecraftMod.LOG.info("Block Ops 2: creating the flat world");
				LevelSettings settings = new LevelSettings(WORLD, GameType.ADVENTURE, false, Difficulty.NORMAL, true,
						new GameRules(FeatureFlags.DEFAULT_FLAGS), WorldDataConfiguration.DEFAULT);
				mc.createWorldOpenFlows().createFreshLevel(WORLD, settings, new WorldOptions(20261006L, false, false),
						WorldPresets::createFlatWorldDimensions, title);
			}
		} catch (RuntimeException e) {
			ZombiecraftMod.LOG.error("Block Ops 2: could not open the world automatically", e);
			done = false;
		}
	}
}
