package com.zombiecraft.client.audio;

import com.zombiecraft.ZombiecraftMod;
import com.zombiecraft.audio.Bo2Locator;
import com.zombiecraft.audio.CueExtractor;
import com.zombiecraft.sheet.Rows.CueFile;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Builds the local cue cache from the player's own Black Ops II sound banks, once, in the background.
 * Banks are only read. The result (short WAV files) lives in the game folder and is never shipped or shared.
 */
public final class AudioCache {
	private AudioCache() {}

	public enum Status { WORKING, READY, NO_BO2, ERROR }

	public static volatile Status status = Status.WORKING;
	public static volatile String detail = "";
	public static volatile Path dir;

	public static String fileName(CueFile f) { return CueExtractor.fileName(f); }

	public static void prepareAsync() {
		Thread t = new Thread(AudioCache::run, "zombiecraft-audio");
		t.setDaemon(true);
		t.start();
	}

	private static void run() {
		try {
			Path gameDir = FabricLoader.getInstance().getGameDir();
			dir = gameDir.resolve("zombiecraft").resolve("cues");
			Optional<Path> bo2 = Bo2Locator.find(gameDir);
			if (bo2.isEmpty()) {
				status = Status.NO_BO2;
				detail = "Black Ops II not found";
				ZombiecraftMod.LOG.warn("Block Ops 2 audio: Black Ops II not found, using Minecraft sounds. Set bo2.dir in config/zombiecraft.properties or ZOMBIECRAFT_BO2_DIR.");
				return;
			}
			var r = CueExtractor.extract(bo2.get(), dir, msg -> ZombiecraftMod.LOG.warn("Block Ops 2 audio: {}", msg));
			ZombiecraftMod.LOG.info("Block Ops 2 audio: {} sounds from {} ({}), {} skipped", r.files(), bo2.get(), r.fromCache() ? "cache up to date" : (r.bytes() / 1_000_000) + " MB extracted", r.skipped());
			CueMixer m = CueMixer.create(dir);
			if (m == null) { status = Status.ERROR; detail = "no audio output"; return; }
			CueMixer.INSTANCE = m;
			status = Status.READY;
			detail = r.files() + " Black Ops II sounds ready";
		} catch (IOException | RuntimeException ex) {
			status = Status.ERROR;
			detail = String.valueOf(ex);
			ZombiecraftMod.LOG.error("Block Ops 2 audio: could not prepare the Black Ops II sounds, using Minecraft sounds", ex);
		}
	}
}
