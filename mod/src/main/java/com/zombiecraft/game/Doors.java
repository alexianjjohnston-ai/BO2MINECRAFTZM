package com.zombiecraft.game;

import com.zombiecraft.sheet.Rows.DoorDef;
import com.zombiecraft.sheet.Rows.WindowDef;
import com.zombiecraft.sheet.Sheets;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/**
 * The buyable doors. Every door row seals a doorway with blocks; paying for it opens the doorway and unlocks the rooms it leads to.
 * Zombies only spawn at windows of unlocked rooms (BO2's zone activation): a room is open from the start unless some door opens it.
 */
public final class Doors {
	private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;

	private final Game game;
	private final ServerLevel level;
	private final Set<String> opened = new HashSet<>();
	private final Set<String> openRooms = new HashSet<>();

	public Doors(Game game) {
		this.game = game; this.level = game.level;
		Set<String> locked = new HashSet<>();
		for (DoorDef d : Sheets.DOORS) locked.addAll(rooms(d));
		for (WindowDef w : Sheets.WINDOWS) if (!locked.contains(w.room())) openRooms.add(w.room());
		game.cmd("kill @e[tag=zc_door]");
		BlockState clip = com.zombiecraft.game.Barrier.parse(level, "zombiecraft:door_clip");
		for (DoorDef d : Sheets.DOORS) {
			forEach(d, p -> level.setBlock(p, clip, FLAGS));
			leaves(d, false);
		}
	}

	private static List<String> rooms(DoorDef d) { return Arrays.asList(d.opens().split(",")); }

	private void forEach(DoorDef d, java.util.function.Consumer<BlockPos> f) {
		BlockPos o = game.origin;
		for (int x = Math.min(d.x1(), d.x2()); x <= Math.max(d.x1(), d.x2()); x++)
			for (int y = Math.min(d.y1(), d.y2()); y <= Math.max(d.y1(), d.y2()); y++)
				for (int z = Math.min(d.z1(), d.z2()); z <= Math.max(d.z1(), d.z2()); z++) f.accept(o.offset(x, y, z));
	}

	private Vec3 center(DoorDef d) {
		BlockPos o = game.origin;
		return new Vec3(o.getX() + (d.x1() + d.x2()) / 2.0 + 0.5, o.getY() + Math.min(d.y1(), d.y2()), o.getZ() + (d.z1() + d.z2()) / 2.0 + 0.5);
	}

	/** The still-closed door that owns this block, or null. */
	public DoorDef at(BlockPos p) {
		BlockPos o = game.origin;
		for (DoorDef d : Sheets.DOORS) {
			if (opened.contains(d.id())) continue;
			if (p.getX() >= o.getX() + Math.min(d.x1(), d.x2()) && p.getX() <= o.getX() + Math.max(d.x1(), d.x2())
					&& p.getY() >= o.getY() + Math.min(d.y1(), d.y2()) && p.getY() <= o.getY() + Math.max(d.y1(), d.y2())
					&& p.getZ() >= o.getZ() + Math.min(d.z1(), d.z2()) && p.getZ() <= o.getZ() + Math.max(d.z1(), d.z2())) return d;
		}
		return null;
	}

	public boolean windowOpen(String windowId) {
		WindowDef w = Sheets.window(windowId);
		return w == null || openRooms.contains(w.room());
	}

	public String promptFor(DoorDef d, PlayerGame pg) {
		return "Hold F to open Door [Cost: " + d.cost() + "]";
	}

	public void use(DoorDef d, ServerPlayer p, PlayerGame pg) {
		if (pg.points < d.cost()) { Cue.ui("zmb_no_cha_ching", p); return; }
		pg.points -= d.cost();
		Cue.ui("zmb_cha_ching", p);
		open(d);
	}

	/** dev (debugTour): swing every door open */
	void openAll() { for (DoorDef d : Sheets.DOORS) open(d, true); }

	private void open(DoorDef d) { open(d, true); }

	private void open(DoorDef d, boolean paid) {
		if (!opened.add(d.id())) return;
		BlockState air = Blocks.AIR.defaultBlockState();
		forEach(d, pos -> level.setBlock(pos, air, FLAGS));
		leaves(d, true);
		if (paid) Cue.at(d.cue(), level, center(d));
		openRooms.addAll(rooms(d));
		// a door whose rooms are all open already guards nothing any more: let it swing open for free
		for (DoorDef o : Sheets.DOORS) if (!opened.contains(o.id()) && openRooms.containsAll(rooms(o))) open(o, false);
	}

	/**
	 * The visible door: two leaves (block displays) hinged at the two sides of the doorway. Closed they fill it; opened they swing 90 degrees
	 * into the room the door leads to. Spawned closed, then animated with display interpolation.
	 */
	private void leaves(DoorDef d, boolean swing) {
		BlockPos o = game.origin;
		boolean alongX = d.x1() != d.x2();
		int lo = alongX ? Math.min(d.x1(), d.x2()) : Math.min(d.z1(), d.z2()), hi = alongX ? Math.max(d.x1(), d.x2()) : Math.max(d.z1(), d.z2());
		int fixed = alongX ? d.z1() : d.x1();
		double h = Math.abs(d.y2() - d.y1()) + 1, th = 0.25, len = (hi - lo + 1) / 2.0;
		// which side of the doorway the opened rooms lie on: the leaves swing that way
		double sum = 0; int n = 0;
		for (WindowDef w : Sheets.WINDOWS) {
			if (!rooms(d).contains(w.room())) continue;
			boolean horiz = w.wall().equals("N") || w.wall().equals("S");
			sum += (alongX == horiz) ? w.fixed() : w.a() + w.width() / 2.0;
			n++;
		}
		double side = n == 0 ? 1 : Math.signum(sum / n - (fixed + 0.5));
		if (side == 0) side = 1;
		double sx = alongX ? 0 : side, sz = alongX ? side : 0;
		for (int i = 0; i < 2; i++) {
			double u = i == 0 ? 1 : -1, hinge = i == 0 ? lo : hi + 1;
			double dx = alongX ? u : 0, dz = alongX ? 0 : u;
			double th0 = Math.atan2(-dz, dx);
			double phi = (Math.abs(dz - sx) < 1e-6 && Math.abs(-dx - sz) < 1e-6) ? Math.PI / 2 : -Math.PI / 2;
			double hx = alongX ? hinge : fixed + 0.5, hz = alongX ? fixed + 0.5 : hinge;
			String tag = "zcl_" + d.id() + "_" + i;
			if (!swing) {
				game.cmd(String.format(Locale.ROOT, "summon block_display %.4f %.4f %.4f {block_state:{Name:\"%s\"},Tags:[\"zc\",\"zc_door\",\"zcd_%s\",\"%s\"],transformation:%s}",
						o.getX() + hx - 0.5 * th * Math.sin(th0) + (alongX ? 0 : 0), (double) (o.getY() + Math.min(d.y1(), d.y2())), o.getZ() + hz - 0.5 * th * Math.cos(th0),
						d.block(), d.id(), tag, transform(th0, len, h, th)));
			} else {
				game.cmd(String.format(Locale.ROOT, "data merge entity @e[tag=%s,limit=1] {start_interpolation:0,interpolation_duration:30,transformation:%s}", tag, transform(th0 + phi, len, h, th)));
			}
		}
	}

	private static String transform(double yaw, double len, double h, double th) {
		return String.format(Locale.ROOT, "{left_rotation:[0f,%.5ff,0f,%.5ff],scale:[%.3ff,%.3ff,%.3ff],translation:[0f,0f,0f],right_rotation:[0f,0f,0f,1f]}",
				Math.sin(yaw / 2), Math.cos(yaw / 2), len, h, th);
	}
}
