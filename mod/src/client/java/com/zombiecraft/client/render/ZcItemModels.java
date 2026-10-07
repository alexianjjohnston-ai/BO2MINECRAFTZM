package com.zombiecraft.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.zombiecraft.item.ModItems;
import com.zombiecraft.sheet.Rows.Bo2Model;
import com.zombiecraft.sheet.Sheets;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/** Which cached model an item is drawn with, and how it sits in the hand, in frames and in displays. */
public final class ZcItemModels {
	private ZcItemModels() {}

	/** First-person placement, tuned by eye. */
	static float VIEW_X = 0.30f, VIEW_Y = -0.30f, VIEW_Z = -0.5f, VIEW_SCALE = 1.3f;
	private static long tuneRead;

	/** Dev: run/zc-view.txt ("x y z scale", re-read every second) overrides the first-person placement while tuning. */
	private static void tune() {
		long now = System.currentTimeMillis();
		if (now - tuneRead < 1000) return;
		tuneRead = now;
		try {
			java.nio.file.Path f = net.minecraft.client.Minecraft.getInstance().gameDirectory.toPath().resolve("zc-view.txt");
			if (!java.nio.file.Files.isRegularFile(f)) return;
			String[] t = java.nio.file.Files.readString(f).trim().split("\s+");
			VIEW_X = Float.parseFloat(t[0]); VIEW_Y = Float.parseFloat(t[1]); VIEW_Z = Float.parseFloat(t[2]); VIEW_SCALE = Float.parseFloat(t[3]);
		} catch (Exception ignored) {}
	}

	/** Walking bob and a slow breathing sway, so the gun is never frozen on screen. */
	private static void sway(PoseStack ps) {
		var player = net.minecraft.client.Minecraft.getInstance().player;
		if (player == null) return;
		float t = (System.nanoTime() % 1_000_000_000_000L) / 1e9f;
		float speed = (float) Math.min(1.0, player.getDeltaMovement().horizontalDistance() / 0.12) * (player.onGround() ? 1f : 0.3f);
		float ph = player.walkDist * 2.3f;
		ps.translate(net.minecraft.util.Mth.cos(ph) * 0.014f * speed + net.minecraft.util.Mth.sin(t * 0.9f) * 0.003f,
				Math.abs(net.minecraft.util.Mth.sin(ph)) * 0.02f * speed + net.minecraft.util.Mth.sin(t * 1.3f) * 0.004f, 0f);
		ps.mulPose(com.mojang.math.Axis.ZP.rotationDegrees(net.minecraft.util.Mth.cos(ph) * 1.2f * speed));
	}

	/** Marker for items drawn from the cache, or null for everything else (and in inventories, where the icon is used). */
	public static String markFor(ItemStack stack, ItemDisplayContext ctx) {
		if (ctx == ItemDisplayContext.GUI) return null;
		return ModItems.modelKeyOf(stack);
	}

	/** Model name for the key: guns use the view model in the first-person hand and the world model elsewhere. */
	static String modelName(String key, boolean view) {
		for (Bo2Model m : Sheets.BO2_MODELS) {
			if (!m.id().equals(key)) continue;
			return view || m.world() == null || m.world().isEmpty() ? m.xmodel() : m.world();
		}
		return null;
	}

	/** Draws the item's mesh and returns true when it did (the vanilla flat icon is then skipped). */
	public static boolean render(String key, ItemDisplayContext ctx, PoseStack ps, MultiBufferSource buf, int light) {
		boolean hand = ctx == ItemDisplayContext.FIRST_PERSON_RIGHT_HAND || ctx == ItemDisplayContext.FIRST_PERSON_LEFT_HAND;
		String name = modelName(key, hand);
		Bo2Mesh.Loaded l = name == null ? null : Bo2Mesh.get(name);
		if (l == null) return false;
		ps.pushPose();
		if (hand) {
			tune();
			// vanilla puts the hand at (+-0.56, -0.52, -0.72) from the camera; view models are authored around the eye
			float side = ctx == ItemDisplayContext.FIRST_PERSON_RIGHT_HAND ? 1f : -1f;
			ps.translate(-side * 0.56f, 0.52f, 0.72f);
			float pt = net.minecraft.client.Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
			if (ViewModel.draw(key, pt, ps, buf, light)) { ps.popPose(); return true; }
			ps.translate(side * VIEW_X, VIEW_Y, VIEW_Z);
			sway(ps);
			Bo2Mesh.draw(l, null, ps, buf, light, Bo2Mesh.INCH * VIEW_SCALE, 2);
			var def = Sheets.WEAPONS.stream().filter(w -> w.id().equals(key)).findFirst().orElse(null);
			if (def != null) Hands.draw(ps, buf, light, l, def.kind(), Bo2Mesh.INCH * VIEW_SCALE);
		} else if (key.equals("mystery_box")) {
			// the BO2 box is 2.4 blocks wide with its front on local +y (the scripts put its trigger there): scaled down to fit
			// beside a wall, standing on the floor of its block, front toward the entity's facing
			float sc = 1.6f / Math.max(1f, l.maxX - l.minX);
			ps.translate(l.cx * sc, -l.minZ * sc - 0.5f, -l.cy * sc);
			Bo2Mesh.draw(l, null, ps, buf, light, sc, 3);
		} else if (key.equals("teddy")) {
			float sc = 0.9f / Math.max(1f, l.maxZ - l.minZ);
			ps.translate(-l.cy * sc, -(l.minZ + l.maxZ) / 2 * sc, -l.cx * sc);
			Bo2Mesh.draw(l, null, ps, buf, light, sc, 0);
		} else {
			// guns: centred, longest side normalised so it fits a frame or display; side view
			float len = Math.max(l.maxX - l.minX, Math.max(l.maxY - l.minY, l.maxZ - l.minZ));
			float target = ctx == ItemDisplayContext.FIXED ? 1.5f : ctx == ItemDisplayContext.NONE ? 0.9f : 0.7f;
			float sc = target / Math.max(1f, len);
			ps.translate(-l.cx * sc, -l.cz * sc, l.cy * sc);
			Bo2Mesh.draw(l, null, ps, buf, light, sc, 1);
		}
		ps.popPose();
		return true;
	}
}
