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

/** The Mystery Box as an entity, so its mesh can play the lid animations. Only the server's BoxSystem creates and drives it. */
public class ZcBox extends Entity {
	/** 0 closed and idle, 1 lid opens, 2 lid closes, 3 flies away, 4 arrives. */
	public static final int IDLE = 0, OPEN = 1, CLOSE = 2, LEAVE = 3, ARRIVE = 4;
	public static final EntityDataAccessor<Integer> ANIM = SynchedEntityData.defineId(ZcBox.class, EntityDataSerializers.INT);
	/** Game time at which the current animation started. */
	public static final EntityDataAccessor<Integer> START = SynchedEntityData.defineId(ZcBox.class, EntityDataSerializers.INT);
	/** Metres per BO2 inch for this box (smaller when the spot is narrow). */
	public static final EntityDataAccessor<Float> SCALE = SynchedEntityData.defineId(ZcBox.class, EntityDataSerializers.FLOAT);

	public ZcBox(EntityType<? extends ZcBox> type, Level level) {
		super(type, level);
		noPhysics = true;
		setNoGravity(true);
	}

	public void play(int anim) {
		entityData.set(ANIM, anim);
		entityData.set(START, (int) level().getGameTime());
	}

	@Override protected void defineSynchedData(SynchedEntityData.Builder b) {
		b.define(ANIM, IDLE).define(START, 0).define(SCALE, 0.024f);
	}

	@Override public boolean hurtServer(ServerLevel level, DamageSource source, float amount) { return false; }
	@Override public boolean isPickable() { return false; }
	@Override public boolean shouldBeSaved() { return false; }
	@Override protected void readAdditionalSaveData(CompoundTag tag) {}
	@Override protected void addAdditionalSaveData(CompoundTag tag) {}
}
