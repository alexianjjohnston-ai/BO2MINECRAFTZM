package com.zombiecraft.game;

import com.zombiecraft.entity.ZcEntities;
import com.zombiecraft.entity.ZcProp;
import com.zombiecraft.sheet.Rows.MachineDef;
import com.zombiecraft.sheet.Sheets;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The power switch and the perk machines. Power starts off; until it is on the perk machines and Pack-a-Punch refuse to work.
 * Costs and effects follow the BO2 scripts (Juggernog 2500, Speed Cola 3000, Double Tap 2000, Quick Revive 500 solo).
 */
public final class Machines {
	public enum Perk {
		JUG("jug", "Juggernog", 2500, 0, Blocks.RED_CONCRETE, "mus_perks_jugganog_jingle", "mus_perks_jugganog_sting"),
		SPEED("speed", "Speed Cola", 3000, 1, Blocks.LIME_CONCRETE, "mus_perks_speed_jingle", "mus_perks_speed_sting"),
		DOUBLETAP("doubletap", "Double Tap", 2000, 2, Blocks.ORANGE_CONCRETE, "mus_perks_doubletap_jingle", "mus_perks_doubletap_sting"),
		REVIVE("revive", "Quick Revive", 500, 3, Blocks.LIGHT_BLUE_CONCRETE, "mus_perks_revive_jingle", "mus_perks_revive_sting");

		public final String id, title, jingle, sting;
		public final int cost, bit;
		final Block lit;

		Perk(String id, String title, int cost, int bit, Block lit, String jingle, String sting) {
			this.id = id; this.title = title; this.cost = cost; this.bit = bit; this.lit = lit; this.jingle = jingle; this.sting = sting;
		}

		static Perk of(String id) { for (Perk p : values()) if (p.id.equals(id)) return p; return null; }
	}

	/** Solo Quick Revive can be bought this many times per game (BO2). */
	public static final int REVIVES_PER_GAME = 3;
	/** Drinking takes this long; guns cannot fire meanwhile. */
	private static final int DRINK_TICKS = 50;

	private final Game game;
	private final ServerLevel level;
	public boolean power;
	private int jingleTimer = 600;

	public Machines(Game game) {
		this.game = game; this.level = game.level;
		build();
	}

	private BlockPos pos(MachineDef d) { return game.origin.offset(d.x(), d.y(), d.z()); }

	public MachineDef at(BlockPos p) {
		for (MachineDef d : Sheets.MACHINES) { BlockPos b = pos(d); if (p.getX() == b.getX() && p.getZ() == b.getZ() && (p.getY() == b.getY() || p.getY() == b.getY() + 1)) return d; }
		return null;
	}

	private Vec3 center(MachineDef d) { BlockPos b = pos(d); return new Vec3(b.getX() + 0.5, b.getY() + 1.0, b.getZ() + 0.5); }

	private final Map<String, ZcProp> props = new HashMap<>();

	/** Yaw that makes a prop's front face the given direction (0 is south). */
	static float yawOf(String facing) {
		return switch (facing) { case "west" -> 90f; case "north" -> 180f; case "east" -> -90f; default -> 0f; };
	}

	/** Places every machine (unpowered) and its sign. */
	private void build() {
		game.cmd("kill @e[tag=zc_machine]");
		for (MachineDef d : Sheets.MACHINES) {
			if (LocalAssets.models) spawnProp(d);
			paint(d, false);
			Vec3 c = center(d);
			String name = d.kind().equals("power") ? "Power Switch" : Perk.of(d.perk()).title + "  [" + Perk.of(d.perk()).cost + "]";
			game.cmd(String.format(Locale.ROOT, "summon text_display %.2f %.2f %.2f {text:'{\"text\":\"%s\",\"color\":\"%s\"}',billboard:\"center\",alignment:\"center\",Tags:[\"zc\",\"zc_machine\"]}",
					c.x, c.y + (LocalAssets.models ? 1.9 : 1.4), c.z, name, d.kind().equals("power") ? "yellow" : "aqua"));
		}
	}

	/** The BO2 model of the machine, standing on the back face of its block and facing out. */
	private void spawnProp(MachineDef d) {
		BlockPos b = pos(d);
		net.minecraft.core.Direction f = net.minecraft.core.Direction.valueOf(d.facing().toUpperCase());
		ZcProp e = new ZcProp(ZcEntities.PROP, level);
		e.moveTo(b.getX() + 0.5 - f.getStepX() * 0.5, b.getY(), b.getZ() + 0.5 - f.getStepZ() * 0.5, yawOf(d.facing()), 0f);
		e.setYRot(yawOf(d.facing()));
		e.addTag("zc"); e.addTag("zc_machine");
		int kind = d.kind().equals("power") ? ZcProp.SWITCH : switch (d.perk()) { case "speed" -> ZcProp.SPEED; case "doubletap" -> ZcProp.DOUBLETAP; case "revive" -> ZcProp.REVIVE; default -> ZcProp.JUG; };
		e.getEntityData().set(ZcProp.KIND, kind);
		level.addFreshEntity(e);
		props.put(d.id(), e);
	}

	/** Dark when off; lit in the perk's colour (or a lamp for the switch) when on. */
	private void paint(MachineDef d, boolean on) {
		BlockPos b = pos(d);
		ZcProp prop = props.get(d.id());
		if (prop != null) {
			// the model is the visible part; invisible barriers keep it solid and targetable, and a light block shows it is on
			level.setBlock(b, Blocks.BARRIER.defaultBlockState(), 3);
			level.setBlock(b.above(), Blocks.BARRIER.defaultBlockState(), 3);
			if (level.getBlockState(b.above(2)).isAir() || level.getBlockState(b.above(2)).is(Blocks.LIGHT))
				level.setBlock(b.above(2), on ? Blocks.LIGHT.defaultBlockState().setValue(net.minecraft.world.level.block.LightBlock.LEVEL, 11) : Blocks.AIR.defaultBlockState(), 3);
			prop.setPowered(on);
			return;
		}
		if (d.kind().equals("power")) {
			level.setBlock(b, Blocks.IRON_BLOCK.defaultBlockState(), 3);
			level.setBlock(b.above(), (on ? Blocks.REDSTONE_BLOCK : Blocks.BLACK_CONCRETE).defaultBlockState(), 3);
		} else {
			level.setBlock(b, (on ? Perk.of(d.perk()).lit : Blocks.GRAY_CONCRETE).defaultBlockState(), 3);
			level.setBlock(b.above(), (on ? Blocks.SEA_LANTERN : Blocks.BLACK_CONCRETE).defaultBlockState(), 3);
		}
	}

	public String promptFor(MachineDef d, PlayerGame pg) {
		if (d.kind().equals("power")) return power ? "The power is on" : "Press F to turn on the power";
		Perk perk = Perk.of(d.perk());
		if (!power) return "The power must be turned on first";
		if (has(pg, perk)) return "You already have " + perk.title;
		if (perk == Perk.REVIVE && pg.revivesBought >= REVIVES_PER_GAME) return "Quick Revive is out of order";
		return "Press F to buy " + perk.title + " [" + perk.cost + "]";
	}

	public void use(MachineDef d, ServerPlayer p, PlayerGame pg) {
		if (d.kind().equals("power")) { if (!power) switchOn(p); return; }
		Perk perk = Perk.of(d.perk());
		if (!power || has(pg, perk) || pg.drinking || (perk == Perk.REVIVE && pg.revivesBought >= REVIVES_PER_GAME) || pg.points < perk.cost) {
			Cue.ui("evt_perk_deny", p);
			return;
		}
		pg.points -= perk.cost;
		pg.drinking = true;
		pg.drinkPerk = perk.bit;
		Cue.ui("zmb_cha_ching", p);
		Cue.ui("evt_perk_bottle_open", p);
		Cue.ui(perk.sting, p);
		WeaponSystem.cancelReload(p, pg);
		pg.fireCooldown = game.tick + DRINK_TICKS;
		game.later(DRINK_TICKS - 12, () -> Cue.ui("evt_perk_swallow", p));
		game.later(DRINK_TICKS, () -> grant(p, pg, perk));
	}

	private void switchOn(ServerPlayer p) {
		power = true;
		for (MachineDef d : Sheets.MACHINES) paint(d, true);
		Cue.all("zmb_power_on_quad", level);
		Cue.all("zmb_perks_power_on", level);
		for (MachineDef d : Sheets.MACHINES) {
			if (d.kind().equals("perk")) {
				Cue.at("zmb_perks_machine_loop", level, center(d));
				game.later(40, () -> Cue.at(Perk.of(d.perk()).jingle, level, center(d)));
			}
		}
	}

	private boolean has(PlayerGame pg, Perk perk) { return (pg.perks & (1 << perk.bit)) != 0; }

	private void grant(ServerPlayer p, PlayerGame pg, Perk perk) {
		pg.drinking = false;
		pg.perks |= 1 << perk.bit;
		switch (perk) {
			case JUG -> {
				double base = Sheets.sys("player_max_health");
				p.getAttribute(Attributes.MAX_HEALTH).setBaseValue(base * 2.5);
				p.setHealth(p.getMaxHealth());
			}
			case SPEED -> pg.perkReloadFactor = 0.5;
			case DOUBLETAP -> pg.perkFireFactor = 0.75;
			case REVIVE -> pg.revivesBought++;
		}
	}

	/** Solo Quick Revive: spends the perk, restores health, and gives a moment of safety. Returns true when it saved the player. */
	public boolean revive(ServerPlayer p, PlayerGame pg) {
		if ((pg.perks & (1 << Perk.REVIVE.bit)) == 0) return false;
		pg.perks &= ~(1 << Perk.REVIVE.bit);
		pg.downs++;
		pg.revives++;
		p.setHealth(p.getMaxHealth());
		pg.shieldUntil = game.tick + 80;
		Cue.ui("mus_perks_revive_sting", p);
		// push the crowd back
		for (var z : game.alive) {
			double dx = z.getX() - p.getX(), dz = z.getZ() - p.getZ(), d = Math.sqrt(dx * dx + dz * dz);
			if (d < 6 && d > 0.01) z.knockback(1.6, -dx / d, -dz / d);
		}
		return true;
	}

	public void tick() {
		if (!power) return;
		if (--jingleTimer <= 0) {
			jingleTimer = 900 + level.random.nextInt(900);
			var perks = Sheets.MACHINES.stream().filter(m -> m.kind().equals("perk")).toList();
			if (!perks.isEmpty()) {
				MachineDef d = perks.get(level.random.nextInt(perks.size()));
				Cue.at(Perk.of(d.perk()).jingle, level, center(d));
			}
		}
	}

	/** Called when a game ends: the machine hum must not carry over. */
	public void shutdown() { Cue.stopAll("zmb_perks_machine_loop", level); }
}
