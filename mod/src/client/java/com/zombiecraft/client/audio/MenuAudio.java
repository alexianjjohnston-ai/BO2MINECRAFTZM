package com.zombiecraft.client.audio;

import com.zombiecraft.sheet.Rows.CueDef;
import com.zombiecraft.sheet.Sheets;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;

/** BO2 menu music and interface sounds (Minecraft's own click when the BO2 sounds are not available). */
public final class MenuAudio {
	private MenuAudio() {}

	/** BO2's front-end theme (ambient mix); it loops, and is restarted if it ever stops while a menu is showing. */
	private static final String MUSIC = "mus_fe_main";

	public static void play(String cue) {
		CueDef def = Sheets.cue(cue);
		if (def == null) return;
		CueMixer m = CueMixer.INSTANCE;
		if (m != null && m.playMenu(def, 1f)) return;
		var ev = BuiltInRegistries.SOUND_EVENT.getValue(ResourceLocation.parse(def.fallback()));
		if (ev != null && !def.category().equals("music")) Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(ev, 1f, (float) def.volume()));
	}

	/** Call every frame a menu is drawn. */
	public static void music() {
		CueMixer m = CueMixer.INSTANCE;
		if (m != null && !m.playing(MUSIC)) play(MUSIC);
	}

	public static void stopMusic() {
		if (CueMixer.INSTANCE != null) CueMixer.INSTANCE.stop(MUSIC);
	}
}
