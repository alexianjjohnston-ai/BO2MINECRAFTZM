package com.zombiecraft.client;

import com.zombiecraft.sheet.Sheets;
import net.minecraft.client.Minecraft;

/**
 * What the player's hands are doing besides shooting: aiming down the sight, sprinting and the frag grenade throw. Times are real
 * seconds (like the rest of the first-person rig); the server decides what really happens, this only drives the look.
 */
public final class ViewState {
	private ViewState() {}

	/** Seconds the throw animation takes after the grenade leaves the hand before the gun comes back (weapon file fireTime 0.365). */
	private static final float THROW_SECONDS = 0.45f;

	private static boolean ads, sprint, gActive, gHeld;
	private static long adsAt, adsOffAt, sprintAt, sprintOffAt, gPress, gRelease;
	private static float adsHeld;
	private static float zoom;
	private static long zoomAt;

	private static float since(long t) { return t == 0 ? 1e6f : (System.nanoTime() - t) / 1e9f; }

	public static void reset() {
		ads = sprint = gActive = gHeld = false;
		adsAt = adsOffAt = sprintAt = sprintOffAt = gPress = gRelease = 0;
		adsHeld = zoom = 0;
	}

	/** Once per client tick from the input poll. */
	public static void update(Minecraft mc, boolean playing, boolean adsKey, boolean grenadeKey, int grenades) {
		long now = System.nanoTime();
		var p = mc.player;
		boolean reloading = GunFeedback.isReloading();

		boolean sprinting = playing && p != null && p.isSprinting() && !gActive;
		if (sprinting != sprint) { if (sprinting) sprintAt = now; else sprintOffAt = now; sprint = sprinting; }

		// grenade: pin out on press, thrown on release once the pin animation is done, the gun returns after the throw
		if (!playing || p == null) { gActive = gHeld = false; }
		else {
			if (grenadeKey && !gHeld && !gActive && grenades > 0 && !reloading && (ZombiecraftClient.state.perks() & 512) == 0) {
				gActive = true; gPress = now; gRelease = 0;
			}
			gHeld = grenadeKey;
			if (gActive && gRelease == 0) {
				float pin = (float) Sheets.sys("grenade_pin_sec"), fuse = (float) Sheets.sys("grenade_fuse_sec");
				float held = (now - gPress) / 1e9f;
				if (!gHeld && held >= pin) gRelease = now;
				else if (!gHeld) gRelease = gPress + (long) (pin * 1e9f);
				else if (held >= fuse) gRelease = now;
			}
			if (gActive && gRelease != 0 && (now - gRelease) / 1e9f >= THROW_SECONDS) {
				gActive = false;
				GunFeedback.requestEquip();
			}
		}

		boolean wantAds = playing && p != null && adsKey && !reloading && !sprinting && !gActive;
		if (wantAds != ads) {
			if (wantAds) adsAt = now;
			else { adsOffAt = now; adsHeld = since(adsAt); }
			ads = wantAds;
		}
	}

	public static boolean ads() { return ads; }
	/** Seconds since the sight was raised / since it was lowered, and how long it had been up when lowered. */
	public static float adsSeconds() { return since(adsAt); }
	public static float adsOffSeconds() { return since(adsOffAt); }
	public static float adsHeldAtRelease() { return adsHeld; }

	public static boolean sprinting() { return sprint; }
	public static float sprintSeconds() { return since(sprintAt); }
	public static float sprintOffSeconds() { return since(sprintOffAt); }

	public static boolean grenadeActive() { return gActive; }
	/** Seconds since the pin was pulled. */
	public static float grenadeSeconds() { return since(gPress); }
	/** Seconds since the grenade left the hand, or -1 while it is still in the hand. */
	public static float grenadeThrownSeconds() { return gRelease == 0 || System.nanoTime() < gRelease ? -1f : since(gRelease); }

	/** 0 (hip) to 1 (fully aimed): eased towards the target at the speed of the sight raise. */
	public static float zoomLevel() {
		long now = System.nanoTime();
		float dt = zoomAt == 0 ? 0f : Math.min(0.1f, (now - zoomAt) / 1e9f);
		zoomAt = now;
		float step = dt / 0.25f;
		zoom = ads ? Math.min(1f, zoom + step) : Math.max(0f, zoom - step);
		return zoom;
	}

	/** Field of view multiplier for the world (the hands keep their own). */
	public static float fovScale() {
		float z = zoomLevel();
		z = z * z * (3f - 2f * z);
		return 1f - z * (1f - (float) Sheets.sys("ads_zoom_fov_mult"));
	}

	/** True while the crosshair should be hidden (aimed through the sights). */
	public static boolean sighted() { return zoom > 0.4f; }
}
