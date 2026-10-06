package com.zombiecraft.client.audio;

import com.zombiecraft.net.Payloads;
import com.zombiecraft.sheet.Rows.CueDef;
import com.zombiecraft.sheet.Sheets;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;

/** Plays a cue: the real BO2 sound when the local cache has it, otherwise the vanilla fallback from audio.json. */
public final class CuePlayer {
	private CuePlayer() {}

	public static void play(Payloads.CuePlay p) {
		CueDef def = Sheets.cue(p.cue());
		if (def == null) return;
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) return;
		if (CueMixer.INSTANCE != null && CueMixer.INSTANCE.play(def, p.at(), p.x(), p.y(), p.z(), p.volume(), p.pitch())) return;
		SoundEvent ev = BuiltInRegistries.SOUND_EVENT.getValue(ResourceLocation.parse(def.fallback()));
		if (ev == null) return;
		SoundSource src = SoundSource.valueOf(def.soundSource());
		float vol = (float) def.volume() * p.volume();
		if (p.at()) mc.level.playLocalSound(p.x(), p.y(), p.z(), ev, src, vol, p.pitch(), false);
		else mc.getSoundManager().play(SimpleSoundInstance.forUI(ev, p.pitch(), vol));
	}

	public static void stop(String cue) {
		if (CueMixer.INSTANCE != null) CueMixer.INSTANCE.stop(cue);
	}
}
