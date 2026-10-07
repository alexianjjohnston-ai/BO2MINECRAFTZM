package com.zombiecraft.bo2;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

/** Dev settings for the BO2 asset cache. */
public final class Bo2Config {
	private Bo2Config() {}

	/**
	 * Folders that already hold unlinked zones (skips the Unlinker run): {@code -Dzombiecraft.bo2dump=a;b} or {@code bo2.dump=a;b} in
	 * config/zombiecraft.properties. Empty for normal players.
	 */
	public static List<Path> dumps(Path gameDir) {
		String v = System.getProperty("zombiecraft.bo2dump", "");
		if (v.isBlank()) {
			try {
				Path cfg = gameDir.resolve("config").resolve("zombiecraft.properties");
				if (Files.isRegularFile(cfg)) {
					Properties p = new Properties();
					try (var in = Files.newInputStream(cfg)) { p.load(in); }
					v = p.getProperty("bo2.dump", "");
				}
			} catch (IOException | RuntimeException ignored) {}
		}
		return v.isBlank() ? List.of() : Arrays.stream(v.split(";")).map(String::trim).filter(s -> !s.isEmpty()).map(Path::of).toList();
	}
}
