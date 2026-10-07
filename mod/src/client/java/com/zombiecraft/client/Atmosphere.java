package com.zombiecraft.client;

import com.zombiecraft.client.hud.ZcHud;
import com.zombiecraft.sheet.Rows.AtmosphereDef;
import com.zombiecraft.sheet.Sheets;
import net.minecraft.client.Minecraft;

/** Fog, sky and cloud colours for the running map (sheets/atmosphere.json), applied only while a match is on. */
public final class Atmosphere {
	private Atmosphere() {}

	/** Id of the map being played; one map for now (the depot). Map select sets it when more maps arrive. */
	public static String map = "bus_depot";

	private static AtmosphereDef def;
	private static String defMap;

	/** The active atmosphere, or null outside a match (menus and vanilla worlds keep vanilla looks). */
	public static AtmosphereDef active() {
		if (!ZcHud.usesWeaponHud(Minecraft.getInstance())) return null;
		if (def == null || !map.equals(defMap)) {
			AtmosphereDef found = null, star = null;
			for (AtmosphereDef d : Sheets.ATMOSPHERE) {
				if (d.id().equals(map)) found = d;
				if (d.id().equals("*")) star = d;
			}
			def = found != null ? found : star;
			defMap = map;
		}
		return def;
	}

	public static int rgb(String hex) { return Integer.parseInt(hex.replace("#", ""), 16) & 0xFFFFFF; }
}
