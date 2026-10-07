package com.zombiecraft.sheet;

/** One record per sheet row. Field names are exactly the sheet columns (sheets/schema.json). */
public final class Rows {
	private Rows() {}

	public record SystemRow(String id, double value, String unit, String source, String status, String note) {}

	public record RoundRow(int round, int zombies, int health, double spawnDelay, int walkPct, int runPct, int sprintPct, String source) {}

	public record ZombieTier(String id, double speedRatio, String hitDamageKey, int attackIntervalTicks, double reachBlocks,
			String cueAmbient, String cueAttack, String cueDeath, String cueSpawn, String cueTear, String cueRun, String status) {}

	public record WeaponDef(String id, String name, String bo2Id, String kind, String fireMode, double damage, double damageMin, double rangeFull,
			double rangeMin, double headMult, int pellets, double spreadDeg, double fireTime, int burstCount, double burstGap, int mag, int reserve,
			double reloadTime, double reloadEmptyTime, boolean projectile, double projSpeed, double explRadius, double range, Integer wallCost,
			double boxWeight, boolean start, boolean upgrade, String papId, String papName, String cueFire, String cueDry, String cueReloadOut,
			String cueReloadIn, String cueReloadEnd, String cueProjectile, String cueExplosion, String iconShape, String iconColor, String status) {}

	public record BoxPoolRow(String id, String weaponId, double weight, String source) {}

	public record BoxRule(String id, int usesMin, int usesMax, String moves, int teddyPct, String source, String note) {}

	public record MapOp(String id, int order, String op, String block, String block2, int x1, int y1, int z1, int x2, int y2, int z2,
			int stepX, int stepZ, String group, String note) {}

	public record WindowDef(String id, String wall, int fixed, int a, int width, int y0, int height, int boards, String room,
			String boardBlock, String sillBlock) {}

	public record SpawnDef(String id, String window, int x, int y, int z) {}

	public record WallBuyDef(String id, String weaponId, int x, int y, int z, String facing, String room) {}

	public record DoorDef(String id, int cost, int x1, int y1, int z1, int x2, int y2, int z2, String block, String opens, String cue, String label, String room) {}

	public record MachineDef(String id, String kind, String perk, int x, int y, int z, String facing, String room) {}
	public record BoxDef(String id, int x, int y, int z, String facing, boolean initial, String room) {}

	public record PapDef(String id, int x1, int y1, int z1, int x2, int y2, int z2, String facing, String room) {}

	public record PlayerSpawn(String id, int x, int y, int z, double yaw, String room) {}

	public record CueDef(String cue, String category, String soundSource, double volume, boolean positional, boolean loop, int variants,
			String fallback) {}

	public record CueFile(String id, String cue, String bank, long entryId, long size, String format, int channels, int rateHz, long frames) {}

	/** A Black Ops II model the game converts from the player's own install (see tools/gen_bo2_models.py). */
	public record Bo2Model(String id, String group, String xmodel, String world, String note) {}

	public record HookRow(String id, String system, String kind, String target, String handler, String status, String note) {}
}
