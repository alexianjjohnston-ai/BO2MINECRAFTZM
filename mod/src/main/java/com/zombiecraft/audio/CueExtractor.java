package com.zombiecraft.audio;

import com.google.gson.Gson;
import com.zombiecraft.sheet.Rows.CueFile;
import com.zombiecraft.sheet.Sheets;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;

/**
 * Reads the cues listed in audio_files.json out of the player's Black Ops II sound banks (read-only) and writes them as
 * small WAV files into a cache folder. Skips the work when a manifest shows the cache already matches the installed banks.
 */
public final class CueExtractor {
	private CueExtractor() {}

	public record Result(int files, int skipped, long bytes, boolean fromCache) {}

	private record Manifest(String bo2, Map<String, Long> bankSizes, int files) {}

	public static String fileName(CueFile f) { return f.id().replace('#', '_') + ".wav"; }

	public static Result extract(Path bo2, Path cacheDir, Consumer<String> warn) throws IOException {
		Files.createDirectories(cacheDir);
		Path sound = bo2.resolve("sound");
		Map<String, List<CueFile>> byBank = new TreeMap<>();
		for (CueFile f : Sheets.CUE_FILES) byBank.computeIfAbsent(f.bank(), k -> new ArrayList<>()).add(f);
		Map<String, Long> sizes = new TreeMap<>();
		for (String b : byBank.keySet()) sizes.put(b, Files.size(sound.resolve(b)));

		Path manifestPath = cacheDir.resolve("manifest.json");
		Gson gson = new Gson();
		if (Files.isRegularFile(manifestPath)) {
			try {
				Manifest m = gson.fromJson(Files.readString(manifestPath), Manifest.class);
				if (m != null && sizes.equals(m.bankSizes()) && m.files() > 0
						&& Sheets.CUE_FILES.stream().allMatch(f -> Files.isRegularFile(cacheDir.resolve(fileName(f)))))
					return new Result(m.files(), 0, 0, true);
			} catch (RuntimeException ignored) { /* rebuild below */ }
		}

		int done = 0, skipped = 0;
		long bytes = 0;
		for (var e : byBank.entrySet()) {
			try (BankReader bank = new BankReader(sound.resolve(e.getKey()))) {
				for (CueFile f : e.getValue()) {
					BankReader.Entry en = bank.entries.get(f.entryId());
					if (en == null || en.size() != f.size()) { skipped++; warn.accept(f.id() + " not found in " + e.getKey() + " (a different Black Ops II build?)"); continue; }
					byte[] data = bank.read(en);
					short[] pcm; int channels = en.channels();
					if (en.format() == BankReader.FORMAT_FLAC) {
						FlacDecoder.Result r = FlacDecoder.decode(data);
						if (r.crcBad > 0) { skipped++; warn.accept(f.id() + " failed its checksum"); continue; }
						pcm = r.pcm; channels = r.channels;
					} else if (en.format() == BankReader.FORMAT_PCM16) {
						pcm = new short[data.length / 2];
						ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm);
					} else { skipped++; warn.accept(f.id() + " has an unknown format"); continue; }
					writeWav(cacheDir.resolve(fileName(f)), pcm, channels, BankReader.RATES[en.rateIndex()]);
					done++; bytes += pcm.length * 2L;
				}
			}
		}
		Files.writeString(manifestPath, gson.toJson(new Manifest(bo2.toString(), sizes, done)));
		return new Result(done, skipped, bytes, false);
	}

	private static void writeWav(Path path, short[] pcm, int channels, int rate) throws IOException {
		int dataLen = pcm.length * 2;
		ByteBuffer b = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN);
		b.put(new byte[]{'R', 'I', 'F', 'F'}).putInt(36 + dataLen).put(new byte[]{'W', 'A', 'V', 'E', 'f', 'm', 't', ' '})
				.putInt(16).putShort((short) 1).putShort((short) channels).putInt(rate).putInt(rate * channels * 2).putShort((short) (channels * 2)).putShort((short) 16)
				.put(new byte[]{'d', 'a', 't', 'a'}).putInt(dataLen);
		for (short s : pcm) b.putShort(s);
		try (OutputStream o = Files.newOutputStream(path)) { o.write(b.array()); }
	}
}
