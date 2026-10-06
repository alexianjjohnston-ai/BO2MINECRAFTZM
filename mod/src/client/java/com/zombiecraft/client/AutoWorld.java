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

	public static final String WORLD = "Zombiecraft";
	private static boolean done;

	public static void register() {
		if (!Boolean.getBoolean("zombiecraft.autoplay")) return;
		ScreenEvents.AFTER_INIT.register((mc, screen, w, h) -> {
			if (done || !(screen instanceof TitleScreen)) return;
			done = true;
			mc.execute(() -> open(mc, screen));
		});
	}

	private static void open(Minecraft mc, net.minecraft.client.gui.screens.Screen title) {
		try {
			if (mc.getLevelSource().levelExists(WORLD)) {
				ZombiecraftMod.LOG.info("Zombiecraft: opening the existing world");
				mc.createWorldOpenFlows().openWorld(WORLD, () -> {});
			} else {
				ZombiecraftMod.LOG.info("Zombiecraft: creating the flat world");
				LevelSettings settings = new LevelSettings(WORLD, GameType.ADVENTURE, false, Difficulty.NORMAL, true,
						new GameRules(FeatureFlags.DEFAULT_FLAGS), WorldDataConfiguration.DEFAULT);
				mc.createWorldOpenFlows().createFreshLevel(WORLD, settings, new WorldOptions(20261006L, false, false),
						WorldPresets::createFlatWorldDimensions, title);
			}
		} catch (RuntimeException e) {
			ZombiecraftMod.LOG.error("Zombiecraft: could not open the world automatically", e);
			done = false;
		}
	}
}
