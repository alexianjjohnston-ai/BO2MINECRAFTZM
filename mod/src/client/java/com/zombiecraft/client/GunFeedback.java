package com.zombiecraft.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.zombiecraft.item.ModItems;
import com.zombiecraft.net.Payloads;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;

import java.util.Objects;

/** Client-only presentation of confirmed combat events. Never changes aim, damage, or ammunition. */
public final class GunFeedback {
	private static final double NO_EVENT = -1_000;
	private static final float HIT_TICKS = 7f;
	private static LocalPlayer trackedPlayer;
	private static int slot = -1;
	private static String weapon;
	private static long ticks;
	private static double shotAt = NO_EVENT, hitAt = NO_EVENT, reloadAt = NO_EVENT;
	private static float shotStrength;
	private static int reloadTicks, shotSequence;
	private static boolean headshot, killed;

	private GunFeedback() {}

	/** Call on the client thread; effects start only after the server accepts the action. */
	public static void accept(Payloads.CombatFeedback event) {
		Minecraft mc = Minecraft.getInstance();
		if (!refreshContext(mc)) return;
		double now = time(mc.getDeltaTracker().getGameTimeDeltaPartialTick(false));
		if (event.kind() == Payloads.FEEDBACK_HIT) {
			// A projectile may connect after switching guns. Preserve the strongest confirmation
			// when multiple pellets or splash victims report in the same tick.
			boolean sameImpact = now - hitAt < 1d;
			headshot = event.headshot() || (sameImpact && headshot);
			killed = event.killed() || (sameImpact && killed);
			hitAt = now;
			return;
		}
		if (weapon == null || event.slot() != slot || !weapon.equals(event.weapon())) return;
		switch (event.kind()) {
			case Payloads.FEEDBACK_SHOT -> {
				float residual = recoil(now);
				shotStrength = Math.min(1.45f, kickFor(weapon) + residual * 0.35f);
				shotAt = now;
				shotSequence++;
				reloadTicks = 0;
			}
			case Payloads.FEEDBACK_RELOAD_START -> {
				reloadAt = now;
				reloadTicks = Math.max(1, event.durationTicks());
				shotAt = NO_EVENT;
			}
			case Payloads.FEEDBACK_RELOAD_STOP -> reloadTicks = 0;
			default -> { }
		}
	}

	/** Tick-based timing keeps every effect frozen when the integrated game is paused. */
	public static void tick(Minecraft mc) {
		if (refreshContext(mc) && !mc.isPaused()) ticks++;
	}

	private static boolean refreshContext(Minecraft mc) {
		int phase = ZombiecraftClient.state.phase();
		if (mc.player == null || mc.level == null || !mc.player.isAlive() || mc.player.isSpectator()
				|| phase == Payloads.PHASE_IDLE || phase == Payloads.PHASE_GAMEOVER) {
			reset();
			return false;
		}
		String heldWeapon = ModItems.weaponOf(mc.player.getMainHandItem());
		int heldSlot = mc.player.getInventory().selected;
		if (trackedPlayer != mc.player || slot != heldSlot || !Objects.equals(weapon, heldWeapon)) {
			reset();
			trackedPlayer = mc.player;
			slot = heldSlot;
			weapon = heldWeapon;
		}
		return true;
	}

	public static void reset() {
		trackedPlayer = null;
		slot = -1;
		weapon = null;
		ticks = 0;
		shotAt = hitAt = reloadAt = NO_EVENT;
		shotStrength = 0;
		reloadTicks = shotSequence = 0;
		headshot = killed = false;
	}

	private static double time(float partialTick) {
		return ticks + Mth.clamp(partialTick, 0f, 1f);
	}

	public static float hitMarkerAlpha(float partialTick) {
		float age = (float) Math.max(0d, time(partialTick) - hitAt);
		return 1f - smooth(2f, HIT_TICKS, age);
	}

	public static boolean hitMarkerHeadshot() { return headshot; }
	public static boolean hitMarkerKilled() { return killed; }

	public static boolean isReloading() {
		return reloadTicks > 0 && time(Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false)) - reloadAt < reloadTicks;
	}

	public static float reloadProgress(float partialTick) {
		return reloadTicks <= 0 ? 0f : Mth.clamp((float) ((time(partialTick) - reloadAt) / reloadTicks), 0f, 1f);
	}

	/** Called inside vanilla's per-hand pose, immediately before drawing its item. */
	public static void renderHeldGun(InteractionHand hand, ItemStack stack, float partialTick,
			PoseStack pose, MultiBufferSource buffers) {
		Minecraft mc = Minecraft.getInstance();
		if (hand != InteractionHand.MAIN_HAND || !refreshContext(mc) || weapon == null
				|| !weapon.equals(ModItems.weaponOf(stack)) || !mc.options.getCameraType().isFirstPerson()) return;
		int side = mc.player.getMainArm() == HumanoidArm.RIGHT ? 1 : -1;
		double now = time(partialTick);
		float kick = recoil(now);
		pose.translate(0f, kick * 0.025f, kick * 0.09f);
		pose.mulPose(Axis.XP.rotationDegrees(kick * 7.5f));
		pose.mulPose(Axis.ZP.rotationDegrees(-side * kick * 1.4f));

		float progress = reloadProgress(partialTick);
		if (reloadTicks > 0 && progress < 1f) {
			float lowered = smooth(0f, 0.18f, progress) * (1f - smooth(0.78f, 1f, progress));
			float seat = (float) Math.sin(smooth(0.64f, 0.77f, progress) * Math.PI);
			pose.translate(-side * lowered * 0.10f, -lowered * 0.18f + seat * 0.035f, lowered * 0.08f);
			pose.mulPose(Axis.XP.rotationDegrees(-lowered * 12f + seat * 3f));
			pose.mulPose(Axis.YP.rotationDegrees(-side * lowered * 18f));
			pose.mulPose(Axis.ZP.rotationDegrees(side * lowered * 28f));
			renderReloadInsert(pose, buffers, side, progress);
		}

		float flash = Mth.clamp(1f - (float) (now - shotAt) / 1.5f, 0f, 1f);
		if (flash > 0f) renderMuzzleFlash(pose, buffers, side, flash);
	}

	private static float recoil(double now) {
		double age = Math.max(0d, now - shotAt);
		return age >= 10d ? 0f : shotStrength * (float) Math.exp(-age / 2d);
	}

	private static float kickFor(String id) {
		return switch (id) {
			case "rottweil72" -> 1.3f;
			case "python", "m14" -> 1.1f;
			case "mp5k", "ak74u" -> 0.65f;
			case "galil", "m16" -> 0.8f;
			default -> 0.9f;
		};
	}

	private static float smooth(float start, float end, float value) {
		float p = Mth.clamp((value - start) / (end - start), 0f, 1f);
		return p * p * (3f - 2f * p);
	}

	/** Same display transform as the supplied generated gun models, including left-hand mirroring. */
	private static void attachmentPose(PoseStack pose, int side) {
		pose.translate(side / 16f, 3.2f / 16f, -2.5f / 16f);
		pose.mulPose(Axis.YP.rotationDegrees(-90f * side));
		pose.scale(0.9f, 0.9f, 0.9f);
	}

	private static void renderMuzzleFlash(PoseStack pose, MultiBufferSource buffers, int side, float alpha) {
		pose.pushPose();
		attachmentPose(pose, side);
		// Sprite barrels end at the right edge; pistols have a one-pixel margin.
		float muzzleX = weapon.equals("m1911") ? 7f / 16f : 0.5f;
		float muzzleY = weapon.equals("python") || weapon.equals("m1911") ? 2.5f / 16f : 1.5f / 16f;
		pose.translate(muzzleX + 0.025f, muzzleY, 0f);
		float radius = (0.055f + 0.04f * alpha) * (shotSequence % 2 == 0 ? 1f : 1.12f);
		boolean ray = weapon.equals("ray_gun");
		int outer = ((int) (220 * alpha) << 24) | (ray ? 0x44EE88 : 0xFFAD35);
		int inner = ((int) (255 * alpha) << 24) | (ray ? 0xDAFFE3 : 0xFFF6CC);
		VertexConsumer vertices = buffers.getBuffer(RenderType.debugQuads());
		// Two crossed, unlit diamonds stay attached to the muzzle through recoil and view bob.
		diamond(vertices, pose, radius, radius * 2.5f, outer, false);
		diamond(vertices, pose, radius, radius * 2.5f, outer, true);
		pose.translate(0.002f, 0f, 0f);
		diamond(vertices, pose, radius * 0.5f, radius * 1.5f, inner, false);
		diamond(vertices, pose, radius * 0.5f, radius * 1.5f, inner, true);
		pose.popPose();
	}

	private static void diamond(VertexConsumer v, PoseStack pose, float radius, float length, int color, boolean cross) {
		vertex(v, pose, -radius * 0.25f, 0f, 0f, color);
		vertex(v, pose, length * 0.35f, cross ? 0f : radius, cross ? radius : 0f, color);
		vertex(v, pose, length, 0f, 0f, color);
		vertex(v, pose, length * 0.35f, cross ? 0f : -radius, cross ? -radius : 0f, color);
	}

	private static void renderReloadInsert(PoseStack pose, MultiBufferSource buffers, int side, float progress) {
		if (progress < 0.18f || progress > 0.76f) return;
		pose.pushPose();
		attachmentPose(pose, side);
		float travel = smooth(0.18f, 0.37f, progress) * (1f - smooth(0.49f, 0.76f, progress));
		float opacity = smooth(0.18f, 0.24f, progress) * (1f - smooth(0.70f, 0.76f, progress));
		boolean shells = weapon.equals("rottweil72"), cylinder = weapon.equals("python"), ray = weapon.equals("ray_gun");
		float insertX = weapon.equals("m1911") || ray ? -0.16f : cylinder ? -0.08f : 0f;
		pose.translate(insertX, -0.12f - travel * 0.26f, side * 0.06f);
		pose.mulPose(Axis.ZP.rotationDegrees(-travel * 15f));
		int color = ((int) (255 * opacity) << 24) | (shells || cylinder ? 0xB49A48 : ray ? 0x68B391 : 0x59616B);
		VertexConsumer vertices = buffers.getBuffer(RenderType.debugQuads());
		if (shells) {
			box(vertices, pose, -0.035f, 0f, 0f, 0.025f, 0.10f, 0.025f, color);
			box(vertices, pose, 0.035f, 0f, 0f, 0.025f, 0.10f, 0.025f, color);
		} else box(vertices, pose, 0f, 0f, 0f, cylinder ? 0.065f : 0.04f, cylinder ? 0.06f : 0.12f, 0.035f, color);
		pose.popPose();
	}

	private static void box(VertexConsumer v, PoseStack p, float x, float y, float z, float w, float h, float d, int color) {
		float x0 = x - w, x1 = x + w, y0 = y - h, y1 = y + h, z0 = z - d, z1 = z + d;
		quad(v, p, x0,y0,z0, x1,y0,z0, x1,y1,z0, x0,y1,z0, color);
		quad(v, p, x1,y0,z1, x0,y0,z1, x0,y1,z1, x1,y1,z1, color);
		quad(v, p, x0,y0,z1, x0,y0,z0, x0,y1,z0, x0,y1,z1, color);
		quad(v, p, x1,y0,z0, x1,y0,z1, x1,y1,z1, x1,y1,z0, color);
		quad(v, p, x0,y1,z0, x1,y1,z0, x1,y1,z1, x0,y1,z1, color);
		quad(v, p, x0,y0,z1, x1,y0,z1, x1,y0,z0, x0,y0,z0, color);
	}

	private static void quad(VertexConsumer v, PoseStack p, float x0, float y0, float z0, float x1, float y1, float z1,
			float x2, float y2, float z2, float x3, float y3, float z3, int color) {
		vertex(v, p, x0, y0, z0, color); vertex(v, p, x1, y1, z1, color);
		vertex(v, p, x2, y2, z2, color); vertex(v, p, x3, y3, z3, color);
	}

	private static void vertex(VertexConsumer v, PoseStack pose, float x, float y, float z, int color) {
		v.addVertex(pose.last(), x, y, z).setColor(color);
	}
}
