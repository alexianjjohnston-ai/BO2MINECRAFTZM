package com.zombiecraft.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;

/** A map prop drawn from a local BO2 model: the perk machines, Pack-a-Punch and the power switch. Only the server's Machines/PapSystem create it. */
public class ZcProp extends Entity {
	/** What it is; the client picks the model from this. */
	public static final int JUG = 0, SPEED = 1, DOUBLETAP = 2, REVIVE = 3, PAP = 4, SWITCH = 5,
			/** Power-up pickups: they spin and bob; BUSY hides one (the blink before it expires). */
			AMMO = 6, INSTA = 7, X2 = 8, NUKE = 9, CARPENTER = 10,
			/** A gun model with BO2's blue glow: on a wall (WALLGUN) or rising out of the Mystery Box (BOXGUN). The weapon id (or "teddy") is in PAP_WEAPON. */
			WALLGUN = 11, BOXGUN = 12,
			/** Scenery: any BO2 model (name in PAP_WEAPON, scale in PAP_DEPTH). Visual only; solid cells under it are decor blocks or barriers. */
			SCENERY = 13;
	public static final EntityDataAccessor<Integer> KIND = SynchedEntityData.defineId(ZcProp.class, EntityDataSerializers.INT);
	/** Lit and humming (power is on). */
	public static final EntityDataAccessor<Boolean> POWERED = SynchedEntityData.defineId(ZcProp.class, EntityDataSerializers.BOOLEAN);
	/** Busy: Pack-a-Punch is upgrading a gun. */
	public static final EntityDataAccessor<Boolean> BUSY = SynchedEntityData.defineId(ZcProp.class, EntityDataSerializers.BOOLEAN);
	/** Game time at which POWERED last changed (for the switch's lever swing). */
	public static final EntityDataAccessor<Integer> SINCE = SynchedEntityData.defineId(ZcProp.class, EntityDataSerializers.INT);
	/** Pack-a-Punch's server-owned phase, weapon and phase start keep its effects in sync for every viewer. */
	public static final EntityDataAccessor<Integer> PAP_STATE = SynchedEntityData.defineId(ZcProp.class, EntityDataSerializers.INT);
	public static final EntityDataAccessor<Long> PAP_SINCE = SynchedEntityData.defineId(ZcProp.class, EntityDataSerializers.LONG);
	public static final EntityDataAccessor<String> PAP_WEAPON = SynchedEntityData.defineId(ZcProp.class, EntityDataSerializers.STRING);
	public static final EntityDataAccessor<Integer> PAP_DURATION = SynchedEntityData.defineId(ZcProp.class, EntityDataSerializers.INT);
	public static final EntityDataAccessor<Boolean> PAP_MODELED = SynchedEntityData.defineId(ZcProp.class, EntityDataSerializers.BOOLEAN);
	public static final EntityDataAccessor<Float> PAP_DEPTH = SynchedEntityData.defineId(ZcProp.class, EntityDataSerializers.FLOAT);

	public ZcProp(EntityType<? extends ZcProp> type, Level level) {
		super(type, level);
		noPhysics = true;
		setNoGravity(true);
	}

	public void setPowered(boolean on) {
		if (entityData.get(POWERED) == on) return;
		entityData.set(POWERED, on);
		entityData.set(SINCE, (int) level().getGameTime());
	}

	public void setPapState(int state, String weapon, int duration) {
		if (entityData.get(PAP_STATE) == state && entityData.get(PAP_WEAPON).equals(weapon)) return;
		entityData.set(PAP_STATE, state);
		entityData.set(PAP_WEAPON, weapon);
		entityData.set(PAP_DURATION, duration);
		entityData.set(PAP_SINCE, level().getGameTime());
		entityData.set(BUSY, state == PapVisual.UPGRADING);
	}

	@Override protected void defineSynchedData(SynchedEntityData.Builder b) {
		b.define(KIND, JUG).define(POWERED, false).define(BUSY, false).define(SINCE, 0);
		b.define(PAP_STATE, PapVisual.IDLE).define(PAP_SINCE, 0L).define(PAP_WEAPON, "")
				.define(PAP_DURATION, 0).define(PAP_MODELED, false).define(PAP_DEPTH, 1f);
	}

	@Override public boolean hurtServer(ServerLevel level, DamageSource source, float amount) { return false; }
	@Override public boolean isPickable() { return false; }
	@Override public boolean shouldBeSaved() { return false; }
	@Override protected void readAdditionalSaveData(CompoundTag tag) {}
	@Override protected void addAdditionalSaveData(CompoundTag tag) {}
}
