package com.zombiecraft.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.zombiecraft.bo2.Pose;
import com.zombiecraft.bo2.XAnim;
import com.zombiecraft.client.GunFeedback;
import com.zombiecraft.client.ViewState;
import com.zombiecraft.sheet.Rows.Bo2Model;
import com.zombiecraft.sheet.Sheets;
import net.minecraft.client.renderer.MultiBufferSource;

import java.util.HashMap;
import java.util.Map;

/**
 * BO2's real first-person rig: the character's viewhands model and the gun's view model, both driven by the gun's own animation
 * clips (idle, fire, reload, pullout), the gun carried on the hands' tag_weapon bone. Falls back (returns false) when the cache has
 * no hands, gun or clips, so the older static view still works.
 */
public final class ViewModel {
	private ViewModel() {}

	/** Which character's arms: -Dzombiecraft.character=oldman|engineer|farmgirl|reporter. */
	private static final String HANDS = "c_zom_" + System.getProperty("zombiecraft.character", "oldman") + "_viewhands";
	/** Placement and size of the rig relative to the eye (blocks per BO2 inch is INCH; tuned by eye). */
	static float VM_X = 0f, VM_Y = Float.parseFloat(System.getProperty("zombiecraft.vmY", "0.06")), VM_Z = 0f, VM_SCALE = 1.0f;

	private static final boolean DEBUG = Boolean.getBoolean("zombiecraft.debugVm");
	private static final java.util.Set<String> logged = new java.util.HashSet<>();
	private static final Map<Bo2Mesh.Loaded, Pose> POSES = new HashMap<>();

	private static final java.util.Set<Bo2Mesh.Loaded> FIXED = new java.util.HashSet<>();
	private static net.minecraft.resources.ResourceLocation cloth;

	/** Plain sleeve cloth (our own noise texture): BO2's per-character sleeve images are not in the install's data. */
	private static net.minecraft.resources.ResourceLocation cloth() {
		if (cloth == null) {
			int[] c = switch (System.getProperty("zombiecraft.character", "oldman")) {
				case "engineer" -> new int[] {34, 38, 44};
				case "farmgirl" -> new int[] {54, 44, 38};
				case "reporter" -> new int[] {40, 36, 34};
				default -> new int[] {36, 36, 32};
			};
			var img = new com.mojang.blaze3d.platform.NativeImage(8, 8, false);
			java.util.Random r = new java.util.Random(11);
			for (int y = 0; y < 8; y++) for (int x = 0; x < 8; x++) {
				int n = r.nextInt(16) - 8;
				img.setPixel(x, y, 0xFF000000 | (Math.max(0, Math.min(255, c[2] + n)) << 16) | (Math.max(0, Math.min(255, c[1] + n)) << 8) | Math.max(0, Math.min(255, c[0] + n)));
			}
			cloth = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("zombiecraft", "vm_cloth");
			net.minecraft.client.Minecraft.getInstance().getTextureManager().register(cloth, new net.minecraft.client.renderer.texture.DynamicTexture(img));
		}
		return cloth;
	}

	/** The skin material points at a rim mask; use BO2's real arm skin, and plain cloth where the sleeve image is missing. */
	private static void fixHands(Bo2Mesh.Loaded h) {
		if (!FIXED.add(h)) return;
		// BO2 Zombies shows dark gloves for every character
		String skinFile = "~-gc_gen_arm_clean_black_c.dds";
		var skin = Bo2Mesh.texture(skinFile);
		for (int s = 0; s < h.surfaceTextures().length; s++) {
			String mat = h.model.materials.get(h.model.surfaces.get(s).material).name();
			if (mat == null) continue;
			if (mat.endsWith("_skin")) h.surfaceTextures()[s] = skin;
			else if (h.surfaceTextures()[s] == null && !mat.contains("thread")) h.surfaceTextures()[s] = cloth();
		}
	}

	private static Pose pose(Bo2Mesh.Loaded l) { return POSES.computeIfAbsent(l, k -> new Pose(k.model)); }

	private static Bo2Model row(String weaponId) {
		for (Bo2Model m : Sheets.BO2_MODELS) if (m.id().equals(weaponId) && m.anim() != null && !m.anim().isEmpty()) return m;
		return null;
	}

	private static XAnim clip(Bo2Model m, String name) { return Bo2Mesh.anim("viewmodel_" + m.anim() + "_" + name); }

	/** True when this gun can be drawn with the real rig. */
	public static boolean has(String weaponId) {
		Bo2Model m = row(weaponId);
		return m != null && Bo2Mesh.get(HANDS) != null && Bo2Mesh.get(m.xmodel()) != null && (clip(m, "idle") != null || clip(m, "first_raise") != null);
	}

	/** A short muzzle flash at the gun's tag_flash bone: two crossed, full-bright quads pointing down the barrel. */
	private static void flash(Bo2Mesh.Loaded gun, Pose gp, PoseStack ps, MultiBufferSource buf, float scale, float age) {
		int tf = gun.model.boneIndex("tag_flash");
		if (tf < 0) return;
		float bx = gp.world[tf * 12 + 3], by = gp.world[tf * 12 + 7], bz = gp.world[tf * 12 + 11];
		// BO2 (x forward, y left, z up) to the view space used by draw(..., axes 2): right = -y, up = z, forward = -z_mc = x
		float px = -by * scale, py = bz * scale, pz = -bx * scale;
		float fade = 1f - age, len = 0.22f * (0.6f + fade * 0.6f), w = 0.09f * (0.5f + fade * 0.7f);
		var vc = buf.getBuffer(net.minecraft.client.renderer.RenderType.entityTranslucentEmissive(Bo2Mesh.whiteTexture()));
		var p = ps.last();
		int a = (int) (230 * fade);
		float roll = (System.identityHashCode(gp) + (int) (System.nanoTime() / 40_000_000L)) % 3 * 0.6f;
		for (int q = 0; q < 2; q++) {
			float ca = (float) Math.cos(roll + q * Math.PI / 2), sa = (float) Math.sin(roll + q * Math.PI / 2);
			float ox = ca * w, oy = sa * w;
			quad(vc, p, px - ox, py - oy, pz, px + ox, py + oy, pz, px + ox * 0.2f, py + oy * 0.2f, pz - len, px - ox * 0.2f, py - oy * 0.2f, pz - len, a);
		}
	}

	private static void quad(com.mojang.blaze3d.vertex.VertexConsumer vc, com.mojang.blaze3d.vertex.PoseStack.Pose p,
			float ax, float ay, float az, float bx, float by, float bz, float cx, float cy, float cz, float dx, float dy, float dz, int alpha) {
		float[][] v = {{ax, ay, az, 255, 235, 150}, {bx, by, bz, 255, 235, 150}, {cx, cy, cz, 255, 160, 60}, {dx, dy, dz, 255, 160, 60}};
		for (float[] k : v)
			vc.addVertex(p, k[0], k[1], k[2]).setColor((int) k[3], (int) k[4], (int) k[5], alpha).setUv(0.5f, 0.5f)
					.setOverlay(net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY).setLight(0xF000F0).setNormal(p, 0f, 1f, 0f);
	}

	/** Draws hands and gun at the eye origin (the caller has already undone vanilla's hand offset). Returns false if not drawable. */
	public static boolean draw(String weaponId, float partialTick, PoseStack ps, MultiBufferSource buf, int light) {
		Bo2Model m = row(weaponId);
		if (m == null) return false;
		Bo2Mesh.Loaded hands = Bo2Mesh.get(HANDS), gun = Bo2Mesh.get(m.xmodel());
		if (hands == null || gun == null) return false;
		fixHands(hands);

		XAnim a;
		float frame;
		float reload = GunFeedback.isReloading() ? GunFeedback.reloadProgress(partialTick) : -1f;
		float shotAge = GunFeedback.shotAgeTicks(partialTick) / 20f, equipAge = GunFeedback.equipAgeTicks(partialTick) / 20f;
		XAnim fire = clip(m, "fire"), pull = clip(m, "pullout"), drink = weaponId.startsWith("bottle_") ? clip(m, "drink") : null;
		boolean grenade = weaponId.equals("frag_grenade");
		XAnim adsUp = grenade ? null : clip(m, "ads_up"), adsDown = grenade ? null : clip(m, "ads_down"), adsFire = grenade ? null : clip(m, "ads_fire");
		XAnim sprintIn = grenade ? null : clip(m, "sprint_in"), sprintLoop = grenade ? null : clip(m, "sprint_loop"), sprintOut = grenade ? null : clip(m, "sprint_out");
		float adsAge = ViewState.adsSeconds(), adsOff = ViewState.adsOffSeconds(), sprintAge = ViewState.sprintSeconds(), sprintOffAge = ViewState.sprintOffSeconds();
		boolean aimed = ViewState.ads() && adsUp != null && adsAge >= adsUp.lengthSeconds();
		XAnim reloadClip = reload < 0f ? null : clip(m, GunFeedback.reloadWasEmpty() ? "reload_empty" : "reload");
		if (reload >= 0f && reloadClip == null) reloadClip = clip(m, GunFeedback.reloadWasEmpty() ? "reload" : "reload_empty"); // some guns have only one reload clip
		XAnim segIn = reload >= 0f && reloadClip == null ? clip(m, "reload_in") : null; // shell-by-shell guns (Remington 870 MCS): in, one loop per shell, out
		if (grenade) {
			// the pin comes out, is held while the key is down, and the throw follows (BO2's M67 clips)
			float thrown = ViewState.grenadeThrownSeconds();
			XAnim throwClip = clip(m, "throw"), pin = clip(m, "pullpin");
			if (thrown >= 0f && throwClip != null) { a = throwClip; frame = Math.min(a.numFrames, thrown * a.frameRate); }
			else if (pin != null) { a = pin; frame = Math.min(a.numFrames, ViewState.grenadeSeconds() * a.frameRate); }
			else { a = clip(m, "idle"); frame = 0; if (a == null) return false; }
		} else if (drink != null) {
			a = drink;
			frame = Math.min(a.numFrames, GunFeedback.drinkSeconds() * a.frameRate);
		} else if (reloadClip != null) {
			a = reloadClip;
			frame = reload * a.numFrames;
		} else if (segIn != null) {
			XAnim lp = clip(m, "reload_loop"), out = clip(m, "reload_out");
			float tIn = segIn.lengthSeconds(), tLp = lp == null ? 0f : lp.lengthSeconds(), tOut = out == null ? 0f : out.lengthSeconds();
			int shells = Math.max(1, Sheets.weapon(weaponId.replace("_pap", "")).mag());
			float pos = reload * (tIn + shells * tLp + tOut);
			if (pos < tIn || lp == null) { a = segIn; frame = Math.min(a.numFrames, pos * a.frameRate); }
			else if (pos < tIn + shells * tLp) { a = lp; frame = ((pos - tIn) % tLp) * lp.frameRate; }
			else if (out != null) { a = out; frame = Math.min(out.numFrames, (pos - tIn - shells * tLp) * out.frameRate); }
			else { a = clip(m, "idle"); frame = 0; if (a == null) return false; }
		} else if (aimed && adsFire != null && shotAge >= 0f && shotAge < adsFire.lengthSeconds()) {
			a = adsFire; frame = shotAge * adsFire.frameRate;
		} else if (fire != null && !aimed && shotAge >= 0f && shotAge < fire.lengthSeconds()) {
			a = fire; frame = shotAge * fire.frameRate;
		} else if (pull != null && equipAge < pull.lengthSeconds()) {
			a = pull; frame = equipAge * pull.frameRate;
		} else if (ViewState.ads() && adsUp != null) {
			// raising the sight, then holding its last frame
			a = adsUp; frame = Math.min(a.numFrames, adsAge * a.frameRate);
		} else if (adsDown != null && adsUp != null && adsOff < adsDown.lengthSeconds() && ViewState.adsHeldAtRelease() > 0f) {
			// lowering it, starting from wherever the raise had got to
			float raised = Math.min(1f, ViewState.adsHeldAtRelease() / adsUp.lengthSeconds());
			a = adsDown; frame = Math.min(a.numFrames, (adsOff + (1f - raised) * adsDown.lengthSeconds()) * a.frameRate);
		} else if (ViewState.sprinting() && sprintIn != null) {
			if (sprintAge < sprintIn.lengthSeconds() || sprintLoop == null) { a = sprintIn; frame = Math.min(a.numFrames, sprintAge * a.frameRate); }
			else { a = sprintLoop; frame = a.numFrames <= 0 ? 0 : ((sprintAge - sprintIn.lengthSeconds()) * a.frameRate) % a.numFrames; }
		} else if (sprintOut != null && sprintOffAge < sprintOut.lengthSeconds()) {
			a = sprintOut; frame = Math.min(a.numFrames, sprintOffAge * a.frameRate);
		} else {
			a = clip(m, "idle");
			if (a != null) frame = a.numFrames <= 0 ? 0 : ((System.nanoTime() / 1e9f) * a.frameRate) % a.numFrames;
			else {
				// some guns (the M14) have no idle clip: hold the last frame of the raise
				a = clip(m, "first_raise");
				if (a == null) return false;
				frame = a.numFrames;
			}
		}

		Pose hp = pose(hands), gp = pose(gun);
		hp.reset(); hp.apply(a, frame, 1f); hp.build();
		gp.reset(); gp.apply(a, frame, 1f); gp.buildOn(hp, "tag_weapon");

		if (DEBUG && logged.add(weaponId)) {
			int tw = hands.model.boneIndex("tag_weapon"), tv = hands.model.boneIndex("tag_view"), jg = gun.model.boneIndex("j_gun");
			System.out.println("[vm] " + weaponId + " clip=" + a.name + " frames=" + a.numFrames + " handsBones=" + hands.model.boneCount() + " tag_weapon=" + tw + " tag_view=" + tv + " j_gun=" + jg);
			float[] w = hp.world;
			if (tw >= 0) System.out.println("[vm] hands tag_weapon world " + java.util.Arrays.toString(java.util.Arrays.copyOfRange(w, tw * 12, tw * 12 + 12)));
			if (tv >= 0) System.out.println("[vm] hands tag_view world " + java.util.Arrays.toString(java.util.Arrays.copyOfRange(w, tv * 12, tv * 12 + 12)));
			if (jg >= 0) System.out.println("[vm] gun j_gun world " + java.util.Arrays.toString(java.util.Arrays.copyOfRange(gp.world, jg * 12, jg * 12 + 12)));
			System.out.println("[vm] hands bbox x " + hands.minX + ".." + hands.maxX + " y " + hands.minY + ".." + hands.maxY + " z " + hands.minZ + ".." + hands.maxZ);
			System.out.println("[vm] gun bbox x " + gun.minX + ".." + gun.maxX + " y " + gun.minY + ".." + gun.maxY + " z " + gun.minZ + ".." + gun.maxZ);
			System.out.println("[vm] hands tex0=" + (hands.model.materials.size() > 0 ? hands.model.materials.get(0).texture() : "-") + " surfaces=" + hands.model.surfaces.size());
		}
		ps.pushPose();
		ps.translate(VM_X, VM_Y, VM_Z);
		float s = Bo2Mesh.INCH * VM_SCALE;
		Bo2Mesh.draw(hands, hp, ps, buf, light, s, 2);
		Bo2Mesh.draw(gun, gp, ps, buf, light, s, 2);
		if (shotAge >= 0f && shotAge < 0.07f && a == fire) flash(gun, gp, ps, buf, s, shotAge / 0.07f);
		ps.popPose();
		return true;
	}
}
