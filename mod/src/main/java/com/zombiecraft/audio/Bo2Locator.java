package com.zombiecraft.audio;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Finds the player's own Call of Duty: Black Ops II install (read-only). Nothing here writes to or starts the game. */
public final class Bo2Locator {
	private Bo2Locator() {}

	public static final String FOLDER = "Call of Duty Black Ops II";
	private static final String PROOF = "sound/zmb_common.all.sabl";

	private static boolean valid(Path dir) { return dir != null && Files.isRegularFile(dir.resolve(PROOF)); }

	/** Order: environment variable config file, Steam's library list, then common library folders on every drive. */
	public static Optional<Path> find(Path gameDir) {
		List<Path> candidates = new ArrayList<>();
		for (String key : new String[]{"ZOMBIECRAFT_BO2_DIR", "BO2_DIR"}) {
			String v = System.getenv(key);
			if (v != null && !v.isBlank()) candidates.add(Path.of(v));
		}
		String prop = System.getProperty("zombiecraft.bo2");
		if (prop != null && !prop.isBlank()) candidates.add(Path.of(prop));
		try {
			Path cfg = gameDir.resolve("config").resolve("zombiecraft.properties");
			if (Files.isRegularFile(cfg)) {
				Properties p = new Properties();
				try (var in = Files.newInputStream(cfg)) { p.load(in); }
				String v = p.getProperty("bo2.dir");
				if (v != null && !v.isBlank()) candidates.add(Path.of(v.trim()));
			}
		} catch (IOException | RuntimeException ignored) {}

		for (Path lib : steamLibraries()) candidates.add(lib.resolve("steamapps").resolve("common").resolve(FOLDER));
		for (Path root : java.nio.file.FileSystems.getDefault().getRootDirectories()) {
			for (String rel : new String[]{"SteamLibrary", "Steam", "Games/Steam", "Program Files (x86)/Steam", "Program Files/Steam", "Games/SteamLibrary"})
				candidates.add(root.resolve(rel).resolve("steamapps").resolve("common").resolve(FOLDER));
		}
		for (Path c : candidates) {
			try { if (valid(c)) return Optional.of(c); } catch (RuntimeException ignored) {}
		}
		return Optional.empty();
	}

	private static List<Path> steamLibraries() {
		List<Path> libs = new ArrayList<>();
		try {
			if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) return libs;
			Process pr = new ProcessBuilder("reg", "query", "HKCU\\Software\\Valve\\Steam", "/v", "SteamPath").redirectErrorStream(true).start();
			String steam = null;
			try (BufferedReader r = new BufferedReader(new InputStreamReader(pr.getInputStream()))) {
				String line;
				while ((line = r.readLine()) != null) {
					int i = line.indexOf("REG_SZ");
					if (i >= 0) steam = line.substring(i + 6).trim();
				}
			}
			if (steam == null) return libs;
			Path root = Path.of(steam);
			libs.add(root);
			Path vdf = root.resolve("steamapps").resolve("libraryfolders.vdf");
			if (Files.isRegularFile(vdf)) {
				Matcher m = Pattern.compile("\"path\"\\s+\"([^\"]+)\"").matcher(Files.readString(vdf));
				while (m.find()) libs.add(Path.of(m.group(1).replace("\\\\", "\\")));
			}
		} catch (IOException | RuntimeException ignored) {}
		return libs;
	}
}
