package com.zombiecraft.audio;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Reads a Black Ops II sound bank (.sabs / .sabl), strictly read-only. Layout from https://codresearch.dev/index.php/SABS_%26_SABL_Files:
 * a 0x800 byte header ("2UX#", entry count at 0x14, entry size at 0x08, entry table offset at 0x28) and 0x14 byte entries.
 */
public final class BankReader implements AutoCloseable {
	public record Entry(long id, long size, long offset, long frames, int rateIndex, int channels, boolean loop, int format) {}

	public static final int FORMAT_PCM16 = 0, FORMAT_FLAC = 8;
	public static final int[] RATES = {8000, 12000, 16000, 24000, 32000, 44100, 48000, 96000, 192000};

	private final RandomAccessFile file;
	public final Map<Long, Entry> entries = new HashMap<>();

	public BankReader(Path path) throws IOException {
		file = new RandomAccessFile(path.toFile(), "r");
		byte[] head = new byte[0x800];
		file.readFully(head);
		ByteBuffer h = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
		if (head[0] != '2' || head[1] != 'U' || head[2] != 'X' || head[3] != '#') throw new IOException("not a sound bank: " + path);
		long count = h.getInt(0x14) & 0xffffffffL;
		int entrySize = h.getInt(0x08);
		long tableOffset = h.getLong(0x28);
		if (entrySize != 0x14 || count > 1_000_000) throw new IOException("unsupported sound bank layout: " + path);
		byte[] raw = new byte[(int) (count * entrySize)];
		file.seek(tableOffset);
		file.readFully(raw);
		ByteBuffer t = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
		for (int i = 0; i < count; i++) {
			int o = i * entrySize;
			long id = t.getInt(o) & 0xffffffffL;
			entries.put(id, new Entry(id, t.getInt(o + 4) & 0xffffffffL, t.getInt(o + 8) & 0xffffffffL, t.getInt(o + 12) & 0xffffffffL,
					raw[o + 16] & 0xff, raw[o + 17] & 0xff, raw[o + 18] != 0, raw[o + 19] & 0xff));
		}
	}

	public byte[] read(Entry e) throws IOException {
		byte[] d = new byte[(int) e.size()];
		file.seek(e.offset());
		file.readFully(d);
		return d;
	}

	@Override public void close() throws IOException { file.close(); }
}
