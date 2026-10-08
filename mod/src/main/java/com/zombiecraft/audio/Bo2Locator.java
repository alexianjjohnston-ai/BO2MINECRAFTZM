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
		candidates.addAll(unixCandidates());
		// several installs may exist (a partial copy next to the full one): take the one that has the most of the sound banks the cues need
		Path best = null; int bestScore = -1;
		java.util.Set<String> banks = new java.util.HashSet<>();
		for (var f : com.zombiecraft.sheet.Sheets.CUE_FILES) banks.add(f.bank());
		for (Path c : candidates) {
			try {
				if (!valid(c)) continue;
				int score = 0;
				for (String b : banks) if (Files.isRegularFile(c.resolve("sound").resolve(b))) score++;
				if (score > bestScore) { best = c; bestScore = score; }
			} catch (RuntimeException ignored) {}
		}
		return Optional.ofNullable(best);
	}

	private static boolean windows() { return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"); }

	/**
	 * macOS and Linux: Black Ops II has no native version there, so the files come from a Steam library copied from a PC, a Windows Steam
	 * inside a Wine bottle (CrossOver, Whisky, Heroic, Bottles), or a copy on an external drive.
	 */
	private static List<Path> unixCandidates() {
		List<Path> out = new ArrayList<>();
		if (windows()) return out;
		Path home = Path.of(System.getProperty("user.home", "."));
		List<Path> libs = new ArrayList<>();
		for (String rel : new String[]{"Library/Application Support/Steam", ".steam/steam", ".local/share/Steam", ".var/app/com.valvesoftware.Steam/.local/share/Steam", "Games", "SteamLibrary", "Steam"})
			libs.add(home.resolve(rel));
		// every Wine prefix we know of: a bottle's drive_c holds a normal Windows layout
		List<Path> prefixes = new ArrayList<>();
		for (String rel : new String[]{"Library/Application Support/CrossOver/Bottles", "Library/Containers/com.isaacmarovitz.Whisky/Bottles", ".wine", "Games/Heroic/Prefixes", ".local/share/bottles/bottles"}) {
			Path b = home.resolve(rel);
			prefixes.add(b);
			try (var kids = Files.isDirectory(b) ? Files.list(b) : java.util.stream.Stream.<Path>empty()) { kids.forEach(prefixes::add); } catch (IOException | RuntimeException ignored) {}
		}
		for (Path pre : prefixes)
			for (String rel : new String[]{"drive_c/Program Files (x86)/Steam", "drive_c/Program Files/Steam", "drive_c/GOG Games", "drive_c/Program Files (x86)/GOG Galaxy/Games"}) libs.add(pre.resolve(rel));
		// external drives (macOS /Volumes, Linux /media, /mnt)
		for (String mount : new String[]{"/Volumes", "/media", "/mnt", "/run/media/" + System.getProperty("user.name", "")}) {
			try (var kids = Files.isDirectory(Path.of(mount)) ? Files.list(Path.of(mount)) : java.util.stream.Stream.<Path>empty()) {
				kids.forEach(k -> { libs.add(k); libs.add(k.resolve("SteamLibrary")); libs.add(k.resolve("Steam")); libs.add(k.resolve("Games")); });
			} catch (IOException | RuntimeException ignored) {}
		}
		for (Path lib : libs) {
			out.add(lib.resolve(FOLDER));
			out.add(lib.resolve("steamapps").resolve("common").resolve(FOLDER));
		}
		// the library list inside a Unix Steam install names further library folders
		for (Path lib : new ArrayList<>(libs)) out.addAll(vdfLibraries(lib));
		return out;
	}

	private static List<Path> vdfLibraries(Path steamRoot) {
		List<Path> out = new ArrayList<>();
		try {
			Path vdf = steamRoot.resolve("steamapps").resolve("libraryfolders.vdf");
			if (!Files.isRegularFile(vdf)) return out;
			Matcher m = Pattern.compile("\"path\"\\s+\"([^\"]+)\"").matcher(Files.readString(vdf));
			while (m.find()) out.add(Path.of(m.group(1)).resolve("steamapps").resolve("common").resolve(FOLDER));
		} catch (IOException | RuntimeException ignored) {}
		return out;
	}

	private static List<Path> steamLibraries() {
		List<Path> libs = new ArrayList<>();
		try {
			if (!windows()) return libs;
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
