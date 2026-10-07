package com.zombiecraft.game;

import com.zombiecraft.sheet.Rows.WeaponDef;
import com.zombiecraft.sheet.Sheets;

import java.util.UUID;

/** Per-player game state (points, the two gun slots, firing and reload timers). */
public final class PlayerGame {
	public final UUID id;
	public int points;
	/** Scoreboard stats (BO2 tab screen). */
	public int kills, headshots, downs, revives;
	public final Gun[] guns = new Gun[2];

	/** Server tick at which the next shot is allowed. */
	public double fireCooldown;
	public long nextMelee;
	public int burstLeft;
	public boolean prevFire, prevInteract;
	public boolean fireHeld, interactHeld, fireClick;
	public long lastHurtTick;
	public int boardPointsThisRound;
	public long lastBoardRepair;

	public int reloadSlot = -1;
	public long reloadStart, reloadEnd;
	public int reloadStage;

	/** Perks and power-ups that change how guns behave. */
	public double perkReloadFactor = 1.0, perkFireFactor = 1.0;
	public boolean instaKill;
	/** Bit per perk (see Machines.Perk), Quick Revive purchases so far, drinking in progress, and a safety window after a revive. */
	public int perks, revivesBought;
	public boolean drinking;
	/** Which perk bottle is in hand while drinking (Machines.Perk bit). */
	public int drinkPerk;
	public long shieldUntil;

	/** Co-op: downed (bleeding out, crawling, waiting for a teammate), dead (spectating until the next round), prone (crawling by choice). */
	public boolean downed, dead, prone;
	public long bleedEnd;
	/** Ticks of revive progress a teammate has built on this downed player, and what the HUD shows (0-100) for the downed player and the reviver. */
	public int reviveTicks, reviveShow;

	public String message = "";
	public int messageTicks;
	public String prompt = "";
	public boolean interactable;
	public long lastDry;

	public PlayerGame(UUID id) { this.id = id; }

	/** A gun in a slot. 'weapon' is the base weapon id; the upgraded stats come from the base row's papId row. */
	public static final class Gun {
		public String weapon;
		public boolean pap;
		public int mag, reserve;

		public Gun(String weapon, boolean pap) {
			this.weapon = weapon; this.pap = pap;
			refill();
			if (!pap) reserve = base().startReserve(); // BO2 givestartammo: a freshly given gun starts below max
		}

		public WeaponDef base() { return Sheets.weapon(weapon); }
		public WeaponDef def() { return pap ? Sheets.weapon(base().papId()) : base(); }
		public int magSize() { return def().mag(); }
		public int reserveMax() { return def().reserve(); }
		public String displayName() { return def().name(); }
		public void refill() { mag = magSize(); reserve = reserveMax(); }
	}

	/** Points earned from kills and repairs: doubled during Double Points and counted toward power-up drops. */
	public void earn(int n) {
		Game g = Game.INSTANCE;
		int m = g != null && g.powerups != null && g.powerups.doubleTicks > 0 ? 2 : 1;
		points += n * m;
		if (g != null) g.teamEarned += n * m;
	}

	public void say(String text, int ticks) { message = text; messageTicks = ticks; }
}
