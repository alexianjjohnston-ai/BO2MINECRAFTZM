package com.zombiecraft.client.render;

import com.zombiecraft.ZombiecraftMod;
import com.zombiecraft.audio.Bo2Locator;
import com.zombiecraft.bo2.Bo2Assets;
import com.zombiecraft.bo2.Bo2Config;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

/** Builds the local model/texture cache from the player's own Black Ops II install (first run only) and says when it can be used. */
public final class ModelCache {
	private ModelCache() {}

	private static volatile boolean ready, working;

	public static boolean ready() { return ready; }

	public static void ensureAsync() {
		if (ready || working) return;
		working = true;
		Thread t = new Thread(() -> {
			Path game = Minecraft.getInstance().gameDirectory.toPath();
			try {
				Optional<Path> bo2 = Bo2Locator.find(game);
				if (bo2.isEmpty()) return;
				var dumps = Bo2Config.dumps(game);
				if (!Bo2Assets.ready(game, bo2.get())) {
					int n = Bo2Assets.prepare(game, bo2.get(), dumps, m -> ZombiecraftMod.LOG.info("Block Ops 2 models: {}", m));
					ZombiecraftMod.LOG.info("Block Ops 2 models: {} ready", n);
				}
				ready = true;
				com.zombiecraft.game.LocalAssets.models = true;
			} catch (IOException | RuntimeException e) {
				ZombiecraftMod.LOG.warn("Block Ops 2 models unavailable: {}", e.toString());
			} finally { working = false; }
		}, "zombiecraft-models");
		t.setDaemon(true);
		t.start();
	}
}
