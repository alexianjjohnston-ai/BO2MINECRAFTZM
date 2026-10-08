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

	/** Which map's sheets to use: {@code -Dzombiecraft.map=tranzit_depot} or {@code map=tranzit_depot} in config/zombiecraft.properties. Empty = the built-in Bus Depot. */
	public static String MAP = mapId();

	private static String mapId() {
		String v = System.getProperty("zombiecraft.map", "");
		if (v.isBlank()) {
			try {
				java.nio.file.Path cfg = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve("config").resolve("zombiecraft.properties");
				if (java.nio.file.Files.isRegularFile(cfg)) {
					Properties p = new Properties();
					try (var in = java.nio.file.Files.newInputStream(cfg)) { p.load(in); }
					v = p.getProperty("map", "");
				}
			} catch (java.io.IOException | RuntimeException ignored) {}
		}
		return v.trim();
	}

	private static <T> List<T> load(String file, Class<T> row) {
		String path = "/data/zombiecraft/sheets/" + file;
		// map_*.json can be overridden per map: sheets/maps/<id>/map_*.json (anything missing there falls back to the built-in sheet)
		if (!MAP.isEmpty() && file.startsWith("map_") && Sheets.class.getResource("/data/zombiecraft/sheets/maps/" + MAP + "/" + file) != null)
			path = "/data/zombiecraft/sheets/maps/" + MAP + "/" + file;
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
	public static List<MapOp> MAP_OPS = load("map_ops.json", MapOp.class);
	public static List<WindowDef> WINDOWS = load("map_windows.json", WindowDef.class);
	public static List<SpawnDef> SPAWNS = load("map_spawns.json", SpawnDef.class);
	public static List<WallBuyDef> WALLBUYS = load("map_wallbuys.json", WallBuyDef.class);
	public static List<BoxDef> BOXES = load("map_boxes.json", BoxDef.class);
	public static List<DoorDef> DOORS = load("map_doors.json", DoorDef.class);
	public static List<MachineDef> MACHINES = load("map_machines.json", MachineDef.class);
	public static List<PapDef> PAPS = load("map_pap.json", PapDef.class);
	public static List<MapProp> PROPS = load("map_props.json", MapProp.class);
	public static List<PlayerSpawn> PLAYER_SPAWNS = load("map_player.json", PlayerSpawn.class);
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

	/** Switches to another map's sheets ("" = the built-in Bus Depot). Call before a game starts (menu pick or dev switch); the loaded game reads the lists afresh. */
	public static void useMap(String id) {
		MAP = id == null ? "" : id.trim();
		MAP_OPS = load("map_ops.json", MapOp.class);
		WINDOWS = load("map_windows.json", WindowDef.class);
		SPAWNS = load("map_spawns.json", SpawnDef.class);
		WALLBUYS = load("map_wallbuys.json", WallBuyDef.class);
		BOXES = load("map_boxes.json", BoxDef.class);
		DOORS = load("map_doors.json", DoorDef.class);
		MACHINES = load("map_machines.json", MachineDef.class);
		PAPS = load("map_pap.json", PapDef.class);
		PROPS = load("map_props.json", MapProp.class);
		PLAYER_SPAWNS = load("map_player.json", PlayerSpawn.class);
		WINDOW_BY_ID.clear();
		for (WindowDef w : WINDOWS) WINDOW_BY_ID.put(w.id(), w);
	}

	/** The Tranzit place the loaded map is set at: depot, town, power, diner or farm (map ids are bo2_depot, tranzit_town ...; the built-in map is the depot). */
	public static String place() {
		String m = MAP.startsWith("tranzit_") ? MAP.substring(8) : MAP.startsWith("bo2_") ? MAP.substring(4) : "";
		return m.isEmpty() ? "depot" : m;
	}

	/** Fog, sky and clock of a place: its own row, else the shared Tranzit look (bus_depot), else the default row. */
	public static AtmosphereDef atmosphere(String place) {
		AtmosphereDef tranzit = null, star = null;
		for (AtmosphereDef d : ATMOSPHERE) {
			if (d.id().equals(place)) return d;
			if (d.id().equals("bus_depot")) tranzit = d;
			else if (d.id().equals("*")) star = d;
		}
		return tranzit != null ? tranzit : star;
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
