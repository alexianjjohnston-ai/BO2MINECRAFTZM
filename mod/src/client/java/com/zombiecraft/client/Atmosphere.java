package com.zombiecraft.client;

import com.zombiecraft.client.hud.ZcHud;
import com.zombiecraft.sheet.Rows.AtmosphereDef;
import com.zombiecraft.sheet.Sheets;
import net.minecraft.client.Minecraft;

/** Fog, sky and cloud colours for the running map (sheets/atmosphere.json), applied only while a match is on. */
public final class Atmosphere {
	private Atmosphere() {}

	/** The place being played (depot, town, power, diner, farm), "" until known. Set when the player picks a place and by the server when joining. */
	public static String map = "";

	private static AtmosphereDef def;
	private static String defMap;

	/** The active atmosphere, or null outside a match (menus and vanilla worlds keep vanilla looks). */
	public static AtmosphereDef active() {
		if (!ZcHud.usesWeaponHud(Minecraft.getInstance())) return null;
		if (def == null || !map.equals(defMap)) {
			def = Sheets.atmosphere(map);
			defMap = map;
		}
		return def;
	}

	public static int rgb(String hex) { return Integer.parseInt(hex.replace("#", ""), 16) & 0xFFFFFF; }
}
