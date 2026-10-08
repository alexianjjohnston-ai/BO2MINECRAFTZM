package com.zombiecraft.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.zombiecraft.bo2.Pose;
import com.zombiecraft.bo2.XAnim;
import com.zombiecraft.client.ZcRoster;
import com.zombiecraft.client.ZombiecraftClient;
import com.zombiecraft.item.ModItems;
import com.zombiecraft.net.Payloads;
import com.zombiecraft.sheet.Rows.Bo2Model;
import com.zombiecraft.sheet.Sheets;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.state.PlayerRenderState;
import net.minecraft.world.entity.player.Player;

import java.util.HashMap;
import java.util.Map;

/**
 * Other players (and you, in third person) drawn as BO2's four survivors: the real full-body character model with the real third-person
 * clips (standing, crouching, prone, the downed crawl, rifle and pistol holds) and the gun's world model in the right hand.
 * Returns false (vanilla body) while the cache has no character, so the game still runs without BO2.
 */
public final class ZcPlayerBody {
	private ZcPlayerBody() {}

	private static final String[] CHARACTERS = {"oldman", "engineer", "farmgirl", "reporter"};
	private static final float SCALE = Bo2Mesh.INCH;
	/** Seconds to fade from the previous clip into a new one. */
	private static final float BLEND = 0.18f;

	private static final Map<Bo2Mesh.Loaded, Pose> POSES = new HashMap<>();
	private static final Map<Integer, Track> TRACKS = new HashMap<>();

	/** What one player is playing, so a change of clip fades instead of popping. */
	private static final class Track {
		String clip = "";
		XAnim prev;
		float prevFrame;
		long since = System.nanoTime();
		long lastSeen;
	}

	private static final Integer DEBUG_STANCE = switch (System.getProperty("zombiecraft.debugBody", "")) {
		case "prone" -> Payloads.RosterEntry.PRONE;
		case "down" -> Payloads.RosterEntry.DOWNED;
		case "stand", "crouch" -> Payloads.RosterEntry.STAND;
		default -> null;
	};

	private static final java.util.Set<Bo2Mesh.Loaded> PATCHED = new java.util.HashSet<>();
	private static final Map<Integer, net.minecraft.resources.ResourceLocation> SOLIDS = new HashMap<>();
	/** Clothing colour per character (the characters' own cloth images are not in the install's data). */
	private static final int[] CLOTH = {0x5A5040, 0x3C506E, 0x783C32, 0x5A554B};
	private static final int[] HAIR = {0x8C8C8C, 0x2A2018, 0x6A4A2A, 0x3A2A20};

	/** A small noisy single-colour texture, so a part without its BO2 image is still shaded like cloth or skin. */
	private static net.minecraft.resources.ResourceLocation solid(int rgb) {
		return SOLIDS.computeIfAbsent(rgb, c -> {
			var img = new com.mojang.blaze3d.platform.NativeImage(8, 8, false);
			java.util.Random r = new java.util.Random(c);
			for (int y = 0; y < 8; y++) for (int x = 0; x < 8; x++) {
				int n = r.nextInt(14) - 7;
				int red = Math.max(0, Math.min(255, (c >> 16 & 255) + n)), grn = Math.max(0, Math.min(255, (c >> 8 & 255) + n)), blu = Math.max(0, Math.min(255, (c & 255) + n));
				img.setPixel(x, y, 0xFF000000 | blu << 16 | grn << 8 | red);
			}
			var loc = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("zombiecraft", "bo2body/" + Integer.toHexString(c));
			Minecraft.getInstance().getTextureManager().register(loc, new net.minecraft.client.renderer.texture.DynamicTexture(img));
			return loc;
		});
	}

	/** Gives every surface whose image is missing a plain stand-in chosen by its material name. */
	private static void patchTextures(Bo2Mesh.Loaded l, int character) {
		if (!PATCHED.add(l)) return;
		var tex = l.surfaceTextures();
		for (int i = 0; i < tex.length; i++) {
			if (tex[i] != null) continue;
			String m = l.model.materials.get(l.model.surfaces.get(i).material).name();
			if (m == null || m.contains("glass")) continue;
			int rgb;
			if (m.contains("cornea")) rgb = 0xE8E8E8;
			else if (m.contains("iris")) rgb = 0x3A2A1C;
			else if (m.contains("insidemouth")) rgb = 0x4A1818;
			else if (m.contains("hair")) rgb = HAIR[character];
			else if (m.contains("glove") || m.contains("clean_black")) rgb = 0x1C1C1C;
			else if (m.contains("head") || m.contains("skin") || m.contains("arm")) rgb = 0xBE8C6E;
			else rgb = CLOTH[character];
			tex[i] = solid(rgb);
		}
	}

	private static Pose pose(Bo2Mesh.Loaded l) { return POSES.computeIfAbsent(l, k -> new Pose(k.model)); }

	/** The gun's third-person model row: upgraded guns share the base gun's model. */
	private static Bo2Model gunRow(String weaponId) {
		for (String id : new String[] {weaponId, weaponId.replace("_pap", "")})
			for (Bo2Model m : Sheets.BO2_MODELS) if (m.id().equals(id) && m.world() != null && !m.world().isEmpty()) return m;
		return null;
	}

	private static boolean pistolHold(String weaponId) {
		var def = Sheets.weapon(weaponId.replace("_pap", ""));
		return def != null && ("pistol".equals(def.kind()) || "revolver".equals(def.kind()));
	}

	/** 0 forward, 1 back, 2 left, 3 right, or -1 when standing still, from the way the player moved last tick against where they face. */
	private static int direction(Player p, float facingDegrees) {
		double vx = p.getX() - p.xo, vz = p.getZ() - p.zo;
		if (vx * vx + vz * vz < 0.0001) return -1;
		double rad = Math.toRadians(facingDegrees), fwd = vx * -Math.sin(rad) + vz * Math.cos(rad), left = vx * Math.cos(rad) + vz * Math.sin(rad);
		if (Math.abs(fwd) >= Math.abs(left)) return fwd >= 0 ? 0 : 1;
		return left > 0 ? 2 : 3;
	}

	private static final String[] DIR = {"forward", "back", "left", "right"};

	/** The clip for this player's stance, hold and movement. */
	private static String clip(int stance, boolean crouching, boolean sprinting, boolean pistol, int dir) {
		if (stance == Payloads.RosterEntry.DOWNED) return dir < 0 ? "pb_laststand_idle" : "pb_laststand_crawl_" + DIR[dir];
		String hold = pistol ? "_pistol" : "";
		if (stance == Payloads.RosterEntry.PRONE) {
			if (dir < 0) return "pb_prone_aim" + hold;
			if (!pistol) return dir == 0 ? "pb_prone_crawl" : "pb_prone_crawl_" + DIR[dir];
			return switch (dir) { case 0 -> "pb_prone_crawl_pistol"; case 1 -> "pb_prone_crawl_pistol_back"; default -> "pb_prone_pistol_crawl_" + DIR[dir]; };
		}
		if (crouching) return dir < 0 ? "pb_crouch_alert" + hold : "pb_crouch_run_" + DIR[dir] + hold;
		if (dir < 0) return "pb_stand_alert" + hold;
		if (sprinting && dir == 0) return pistol ? "pb_sprint_pistol" : "pb_sprint";
		if (!pistol) return "pb_combatrun_" + DIR[dir] + "_loop";
		return dir == 0 ? "pb_combatwalk_forward_loop_pistol" : "pb_combatrun_" + DIR[dir] + "_loop_pistol";
	}

	/** Draws the player; false means "not here, use the vanilla body". */
	public static boolean render(PlayerRenderState s, PoseStack ps, MultiBufferSource buf, int light) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || ZombiecraftClient.state.phase() == Payloads.PHASE_IDLE) return false;
		if (!(mc.level.getEntity(s.id) instanceof Player p)) return false;
		Payloads.RosterEntry entry = ZcRoster.get(p.getUUID());
		int stance = entry == null ? Payloads.RosterEntry.STAND : entry.stance();
		if (DEBUG_STANCE != null) stance = DEBUG_STANCE; // dev: -Dzombiecraft.debugBody=stand|prone|down (third-person camera, see ZombiecraftClient)
		if (p.isSpectator() || stance == Payloads.RosterEntry.DEAD) return true; // out until the next round: nothing to draw
		int character = ZcRoster.index(p.getUUID()) & 3;
		Bo2Mesh.Loaded body = Bo2Mesh.get("c_zom_player_" + CHARACTERS[character] + "_fb");
		if (body == null) return false;
		patchTextures(body, character);

		String weapon = ModItems.weaponOf(p.getMainHandItem());
		boolean pistol = weapon != null && pistolHold(weapon);
		float facing = s.bodyRot + s.yRot;
		String name = clip(stance, p.isCrouching(), p.isSprinting(), pistol, direction(p, facing));
		XAnim a = Bo2Mesh.anim(name);

		long now = System.nanoTime();
		Track tr = TRACKS.computeIfAbsent(s.id, k -> new Track());
		tr.lastSeen = now;
		if (a != null && !name.equals(tr.clip)) {
			XAnim old = tr.clip.isEmpty() ? null : Bo2Mesh.anim(tr.clip);
			tr.prev = old;
			tr.prevFrame = old == null ? 0f : frame(old, tr.since, now);
			tr.clip = name;
			tr.since = now;
		}
		if (TRACKS.size() > 32) TRACKS.values().removeIf(t -> now - t.lastSeen > 5_000_000_000L);

		Pose bp = pose(body);
		float lift = a == null ? 0f : lift(body, bp, a, name); // before the pose for drawing: it scratches the pose
		bp.reset();
		if (a != null) {
			float fade = Math.min(1f, (now - tr.since) / 1e9f / BLEND);
			if (tr.prev != null && fade < 1f) {
				bp.apply(tr.prev, tr.prevFrame, 1f);
				bp.apply(a, frame(a, tr.since, now), fade);
			} else bp.apply(a, frame(a, tr.since, now), 1f);
		}
		bp.build();

		ps.pushPose();
		ps.mulPose(Axis.YP.rotationDegrees(-facing));
		ps.translate(0f, lift * SCALE, 0f);
		Bo2Mesh.draw(body, bp, ps, buf, light, SCALE, 0);
		Bo2Model row = weapon == null ? null : gunRow(weapon);
		Bo2Mesh.Loaded gun = row == null ? null : Bo2Mesh.get(row.world());
		if (gun != null) {
			Pose gp = pose(gun);
			gp.reset();
			gp.buildOn(bp, "tag_weapon_right");
			Bo2Mesh.draw(gun, gp, ps, buf, light, SCALE, 0);
		}
		ps.popPose();
		return true;
	}

	private static final Map<String, Float> LIFT = new HashMap<>();

	/** The clips are authored about the model's origin, not the floor: find each clip's lowest point once (over a few frames) and lift by it. */
	private static float lift(Bo2Mesh.Loaded body, Pose bp, XAnim a, String name) {
		Float known = LIFT.get(name);
		if (known != null) return known;
		float lo = 1e9f;
		float[] t3 = new float[3];
		for (int k = 0; k < 4; k++) {
			bp.reset();
			bp.apply(a, a.numFrames * k / 4f, 1f);
			bp.build();
			for (int v = 0; v < body.model.vertCount; v += 5) { bp.skinPos(v, t3); lo = Math.min(lo, t3[2]); }
		}
		LIFT.put(name, -lo);
		return -lo;
	}

	private static float frame(XAnim a, long since, long now) {
		return a.numFrames <= 0 ? 0f : ((now - since) / 1e9f * a.frameRate) % a.numFrames;
	}
}
