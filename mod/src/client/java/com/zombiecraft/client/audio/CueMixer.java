package com.zombiecraft.client.audio;

import com.zombiecraft.ZombiecraftMod;
import com.zombiecraft.sheet.Rows.CueDef;
import com.zombiecraft.sheet.Rows.CueFile;
import com.zombiecraft.sheet.Sheets;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A small software mixer that plays the extracted BO2 cues on its own audio line: 48 kHz stereo, positional pan and distance
 * for mono world sounds, plain stereo for UI/music/guns, looping, pitch, and Minecraft's own volume sliders.
 */
public final class CueMixer {
	public static volatile CueMixer INSTANCE;

	private static final int RATE = 48000, FRAMES = 480, MAX_VOICES = 40;
	private static final double MAX_DIST = 48.0, REF_DIST = 3.0;

	private record Sample(short[] pcm, int channels) {}

	private static final class Voice {
		String cue; Sample s; double pos; double step; float gain; boolean loop, positional, menu; double x, y, z;
	}

	private final Map<String, List<Sample>> bank = new HashMap<>();
	private final List<Voice> voices = new ArrayList<>();
	private final SourceDataLine line;
	private final Map<String, Integer> lastVariant = new HashMap<>();
	private volatile boolean running = true;

	private CueMixer(SourceDataLine line) { this.line = line; }

	public static CueMixer create(Path dir) {
		try {
			AudioFormat fmt = new AudioFormat(RATE, 16, 2, true, false);
			SourceDataLine line = AudioSystem.getSourceDataLine(fmt);
			line.open(fmt, RATE * 4 / 20);
			CueMixer m = new CueMixer(line);
			int loaded = 0;
			for (CueFile f : Sheets.CUE_FILES) {
				Path p = dir.resolve(AudioCache.fileName(f));
				if (!Files.isRegularFile(p)) continue;
				Sample s = readWav(p);
				if (s == null) continue;
				m.bank.computeIfAbsent(f.cue(), k -> new ArrayList<>()).add(s);
				loaded++;
			}
			line.start();
			Thread t = new Thread(m::mixLoop, "zombiecraft-mixer");
			t.setDaemon(true);
			t.start();
			ZombiecraftMod.LOG.info("Block Ops 2 mixer: {} sounds loaded", loaded);
			return loaded > 0 ? m : null;
		} catch (LineUnavailableException | IOException | RuntimeException e) {
			ZombiecraftMod.LOG.warn("Block Ops 2 mixer: no audio line, using Minecraft sounds ({})", e.toString());
			return null;
		}
	}

	private static Sample readWav(Path p) throws IOException {
		byte[] b = Files.readAllBytes(p);
		if (b.length < 44) return null;
		ByteBuffer h = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
		int channels = h.getShort(22), rate = h.getInt(24);
		if (rate != RATE || (channels != 1 && channels != 2)) return null;
		short[] pcm = new short[(b.length - 44) / 2];
		ByteBuffer.wrap(b, 44, b.length - 44).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm);
		return new Sample(pcm, channels);
	}

	public boolean play(CueDef def, boolean at, double x, double y, double z, float volume, float pitch) {
		List<Sample> variants = bank.get(def.cue());
		if (variants == null || variants.isEmpty()) return false;
		int idx = ThreadLocalRandom.current().nextInt(variants.size());
		if (variants.size() > 1 && lastVariant.getOrDefault(def.cue(), -1) == idx) idx = (idx + 1) % variants.size();
		lastVariant.put(def.cue(), idx);
		Minecraft mc = Minecraft.getInstance();
		float cat = mc.options.getSoundSourceVolume(SoundSource.valueOf(def.soundSource()));
		float master = mc.options.getSoundSourceVolume(SoundSource.MASTER);
		Voice v = new Voice();
		v.cue = def.cue(); v.s = variants.get(idx); v.step = Math.max(0.25, pitch);
		v.gain = (float) def.volume() * volume * cat * master;
		v.loop = def.loop();
		v.positional = at && def.positional() && v.s.channels() == 1;
		v.x = x; v.y = y; v.z = z;
		synchronized (voices) {
			if (voices.size() >= MAX_VOICES) voices.remove(0);
			voices.add(v);
		}
		return true;
	}

	/** A menu sound or music: plays on any screen, even paused or with no world. */
	public boolean playMenu(CueDef def, float volume) {
		if (!play(def, false, 0, 0, 0, volume, 1f)) return false;
		synchronized (voices) { voices.get(voices.size() - 1).menu = true; }
		return true;
	}

	public boolean playing(String cue) {
		synchronized (voices) { return voices.stream().anyMatch(v -> v.cue.equals(cue)); }
	}

	public void stop(String cue) {
		synchronized (voices) { voices.removeIf(v -> v.cue.equals(cue)); }
	}

	private void mixLoop() {
		float[] mix = new float[FRAMES * 2];
		byte[] out = new byte[FRAMES * 4];
		while (running) {
			Arrays.fill(mix, 0f);
			Minecraft mc = Minecraft.getInstance();
			// in a world, game sounds freeze while paused or loading; menu sounds and music always play
			boolean frozen = mc.level != null && (mc.player == null || mc.isPaused());
			{
				boolean inWorld = mc.level != null && mc.player != null;
				double lx = inWorld ? mc.player.getX() : 0, ly = inWorld ? mc.player.getEyeY() : 0, lz = inWorld ? mc.player.getZ() : 0;
				double yaw = inWorld ? Math.toRadians(mc.player.getYRot()) : 0;
				double rx = -Math.cos(yaw), rz = -Math.sin(yaw);
				synchronized (voices) {
					Iterator<Voice> it = voices.iterator();
					while (it.hasNext()) {
						Voice v = it.next();
						if (frozen && !v.menu) continue;
						float gl = v.gain, gr = v.gain;
						if (v.positional) {
							double dx = v.x - lx, dy = v.y - ly, dz = v.z - lz;
							double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
							double att = d <= REF_DIST ? 1.0 : Math.max(0.0, 1.0 - (d - REF_DIST) / (MAX_DIST - REF_DIST));
							att *= att;
							double pan = d < 0.01 ? 0 : (dx * rx + dz * rz) / Math.max(1e-6, Math.sqrt(dx * dx + dz * dz)) * Math.min(1.0, Math.sqrt(dx * dx + dz * dz) / 2.0);
							gl = (float) (v.gain * att * Math.sqrt((1 - pan) / 2) * 1.2);
							gr = (float) (v.gain * att * Math.sqrt((1 + pan) / 2) * 1.2);
						}
						short[] pcm = v.s.pcm();
						int ch = v.s.channels();
						int n = pcm.length / ch;
						boolean finished = false;
						for (int i = 0; i < FRAMES; i++) {
							int i0 = (int) v.pos;
							if (i0 >= n) { if (v.loop) { v.pos -= n; i0 = (int) v.pos; } else { finished = true; break; } }
							double frac = v.pos - i0;
							int i1 = Math.min(i0 + 1, n - 1);
							float l, r;
							if (ch == 1) { float a = pcm[i0], b = pcm[i1]; l = r = (float) (a + (b - a) * frac); }
							else { float a0 = pcm[i0 * 2], b0 = pcm[i1 * 2], a1 = pcm[i0 * 2 + 1], b1 = pcm[i1 * 2 + 1]; l = (float) (a0 + (b0 - a0) * frac); r = (float) (a1 + (b1 - a1) * frac); }
							mix[i * 2] += l * gl / 32768f;
							mix[i * 2 + 1] += r * gr / 32768f;
							v.pos += v.step;
						}
						if (finished) it.remove();
					}
				}
			}
			for (int i = 0; i < FRAMES * 2; i++) {
				int s = Math.round(Math.max(-1f, Math.min(1f, mix[i])) * 32767f);
				out[i * 2] = (byte) s; out[i * 2 + 1] = (byte) (s >> 8);
			}
			line.write(out, 0, out.length);
		}
	}
}
