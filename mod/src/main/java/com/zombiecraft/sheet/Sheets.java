package com.zombiecraft.sheet;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.zombiecraft.sheet.Rows.*;

import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Loads the design sheets (bundled in the jar under data/zombiecraft/sheets) and exposes them. The sheets are the source of truth. */
public final class Sheets {
	private Sheets() {}

	private static final Gson GSON = new Gson();

	private static <T> List<T> load(String file, Class<T> row) {
		String path = "/data/zombiecraft/sheets/" + file;
		try (var in = Sheets.class.getResourceAsStream(path)) {
			if (in == null) throw new IllegalStateException("missing sheet " + path);
			Reader r = new InputStreamReader(in, StandardCharsets.UTF_8);
			Type t = TypeToken.getParameterized(List.class, row).getType();
			List<T> rows = GSON.fromJson(r, t);
			return List.copyOf(rows);
		} catch (java.io.IOException e) {
			throw new IllegalStateException("cannot read sheet " + path, e);
		}
	}

	public static final List<SystemRow> SYSTEMS = load("systems.json", SystemRow.class);
	public static final List<RoundRow> ROUNDS = load("rounds.json", RoundRow.class);
	public static final List<ZombieTier> ZOMBIES = load("zombies.json", ZombieTier.class);
	public static final List<WeaponDef> WEAPONS = load("weapons.json", WeaponDef.class);
	public static final List<BoxPoolRow> BOX_POOL = load("box_pool.json", BoxPoolRow.class);
	public static final List<BoxRule> BOX_RULES = load("box_rules.json", BoxRule.class);
	public static final List<MapOp> MAP_OPS = load("map_ops.json", MapOp.class);
	public static final List<WindowDef> WINDOWS = load("map_windows.json", WindowDef.class);
	public static final List<SpawnDef> SPAWNS = load("map_spawns.json", SpawnDef.class);
	public static final List<WallBuyDef> WALLBUYS = load("map_wallbuys.json", WallBuyDef.class);
	public static final List<BoxDef> BOXES = load("map_boxes.json", BoxDef.class);
	public static final List<DoorDef> DOORS = load("map_doors.json", DoorDef.class);
	public static final List<MachineDef> MACHINES = load("map_machines.json", MachineDef.class);
	public static final List<PapDef> PAPS = load("map_pap.json", PapDef.class);
	public static final List<PlayerSpawn> PLAYER_SPAWNS = load("map_player.json", PlayerSpawn.class);
	public static final List<CueDef> CUES = load("audio.json", CueDef.class);
	public static final List<CueFile> CUE_FILES = load("audio_files.json", CueFile.class);
	public static final List<TextureDef> TEXTURES = load("textures.json", TextureDef.class);
	public static final List<AtmosphereDef> ATMOSPHERE = load("atmosphere.json", AtmosphereDef.class);
	public static final List<Bo2Model> BO2_MODELS = load("bo2_models.json", Bo2Model.class);

	private static final Map<String, SystemRow> SYS = new HashMap<>();
	private static final Map<String, WeaponDef> WEAPON_BY_ID = new LinkedHashMap<>();
	private static final Map<String, CueDef> CUE_BY_ID = new HashMap<>();
	private static final Map<String, List<CueFile>> FILES_BY_CUE = new HashMap<>();
	private static final Map<String, WindowDef> WINDOW_BY_ID = new HashMap<>();
	private static final Map<String, ZombieTier> TIER_BY_ID = new HashMap<>();

	static {
		for (SystemRow s : SYSTEMS) SYS.put(s.id(), s);
		for (WeaponDef w : WEAPONS) WEAPON_BY_ID.put(w.id(), w);
		for (CueDef c : CUES) CUE_BY_ID.put(c.cue(), c);
		for (CueFile f : CUE_FILES) FILES_BY_CUE.computeIfAbsent(f.cue(), k -> new ArrayList<>()).add(f);
		for (WindowDef w : WINDOWS) WINDOW_BY_ID.put(w.id(), w);
		for (ZombieTier z : ZOMBIES) TIER_BY_ID.put(z.id(), z);
	}

	/** A number from systems.json. Unknown keys fail loudly: the preflight also scans the code for these keys. */
	public static double sys(String key) {
		SystemRow s = SYS.get(key);
		if (s == null) throw new IllegalStateException("systems.json has no key " + key);
		return s.value();
	}

	public static int sysInt(String key) { return (int) Math.round(sys(key)); }

	public static WeaponDef weapon(String id) {
		WeaponDef w = WEAPON_BY_ID.get(id);
		if (w == null) throw new IllegalStateException("weapons.json has no weapon " + id);
		return w;
	}

	public static WeaponDef startWeapon() {
		return WEAPONS.stream().filter(WeaponDef::start).findFirst().orElseThrow();
	}

	public static CueDef cue(String id) { return CUE_BY_ID.get(id); }

	public static List<CueFile> cueFiles(String cue) { return FILES_BY_CUE.getOrDefault(cue, List.of()); }

	public static WindowDef window(String id) { return WINDOW_BY_ID.get(id); }

	public static ZombieTier tier(String id) { return TIER_BY_ID.get(id); }

	public static RoundRow round(int n) {
		int idx = Math.max(1, Math.min(n, ROUNDS.size())) - 1;
		RoundRow base = ROUNDS.get(idx);
		if (n <= ROUNDS.size()) return base;
		// beyond the table: keep BO2's growth (x1.1 health per round, +1 zombie per round roughly), spawn delay at its floor
		int extra = n - ROUNDS.size();
		int health = (int) Math.min(Integer.MAX_VALUE, Math.round(base.health() * Math.pow(sys("health_mult_from_r10"), extra)));
		return new RoundRow(n, base.zombies() + extra * 2, health, sys("spawn_delay_min_s"), 0, 0, 100, "extrapolated");
	}
}
