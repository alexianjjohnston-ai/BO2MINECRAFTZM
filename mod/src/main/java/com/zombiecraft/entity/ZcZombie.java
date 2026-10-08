package com.zombiecraft.entity;

import com.zombiecraft.game.Barrier;
import com.zombiecraft.game.Cue;
import com.zombiecraft.sheet.Rows.ZombieTier;
import com.zombiecraft.sheet.Sheets;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.ZombieAttackGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/** A Zombies-mode zombie: vanilla zombie body, our own health (hp), goals and sounds. */
public class ZcZombie extends Zombie {
	/** 0 walk to the window, 1 tear boards, 2 climb in, 3 free (hunting). */
	public int stage = 3;
	public Barrier barrier;
	public double hp = 150, hpMax = 150;
	public String tier = "walk";
	public boolean headGibbed;
	private static final int BODY_TICKS = 160;
	private int tearTicks, stuckTicks, vocalTimer;
	private double stepDist;
	/** Synced to clients for animation: stage (0-3) in the low bits, speed tier (0 walk, 1 run, 2 sprint) times 4 above. */
	public static final net.minecraft.network.syncher.EntityDataAccessor<Byte> DATA_ANIM =
			net.minecraft.network.syncher.SynchedEntityData.defineId(ZcZombie.class, net.minecraft.network.syncher.EntityDataSerializers.BYTE);

	@Override protected void defineSynchedData(net.minecraft.network.syncher.SynchedEntityData.Builder b) {
		super.defineSynchedData(b);
		b.define(DATA_ANIM, (byte) 3);
	}


	public ZcZombie(EntityType<? extends Zombie> type, Level level) {
		super(type, level);
		setPersistenceRequired();
		setSilent(true); // every zombie sound is a BO2 cue played by the server
		vocalTimer = 40 + random.nextInt(120);
	}

	public static AttributeSupplier.Builder createZcAttributes() {
		return Zombie.createAttributes()
				.add(Attributes.FOLLOW_RANGE, 64.0)
				.add(Attributes.SPAWN_REINFORCEMENTS_CHANCE, 0.0)
				.add(Attributes.ATTACK_DAMAGE, 10.0)
				.add(Attributes.KNOCKBACK_RESISTANCE, 0.6);
	}

	/** Set up health, speed and the window this zombie goes through. */
	public void setup(String tierId, int health, Barrier window) {
		ZombieTier t = Sheets.tier(tierId);
		this.tier = tierId;
		this.hp = this.hpMax = health;
		this.barrier = window;
		this.stage = window == null ? 3 : 0;
		getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(t.speedRatio() * Sheets.sys("zombie_sprint_speed_attr"));
		getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(Sheets.sys(t.hitDamageKey()));
		setAggressive(true);
	}

	@Override protected void registerGoals() {
		goalSelector.addGoal(0, new FloatGoal(this));
		goalSelector.addGoal(1, new BarrierGoal(this));
		goalSelector.addGoal(2, new HuntGoal(this));
		goalSelector.addGoal(7, new LookAtPlayerGoal(this, Player.class, 8.0F));
		targetSelector.addGoal(1, new NearestAttackableTargetGoal<>(this, Player.class, false));
	}

	/** Downed players are left alone: the horde goes for whoever is still standing. */
	@Override public boolean canAttack(net.minecraft.world.entity.LivingEntity target) {
		if (target instanceof net.minecraft.server.level.ServerPlayer sp && com.zombiecraft.game.Revive.isDowned(sp)) return false;
		return super.canAttack(target);
	}

	@Override public void aiStep() {
		super.aiStep();
		if (level().isClientSide) return;
		if (getTarget() instanceof net.minecraft.server.level.ServerPlayer sp && com.zombiecraft.game.Revive.isDowned(sp)) setTarget(null);
		entityData.set(DATA_ANIM, (byte) (stage | (tier.equals("walk") ? 0 : tier.equals("run") ? 1 : 2) << 2));
		if (isAlive() && --vocalTimer <= 0 && level() instanceof ServerLevel sl) {
			ZombieTier t = Sheets.tier(tier);
			Cue.at(tier.equals("walk") ? t.cueAmbient() : t.cueRun(), sl, position());
			vocalTimer = 200 + random.nextInt(300);
		}
		// BO2 footsteps (vanilla zombie sounds are silenced): one step per stride
		if (onGround() && level() instanceof ServerLevel sl) {
			stepDist += Math.hypot(getX() - xo, getZ() - zo);
			if (stepDist >= (tier.equals("walk") ? 1.5 : 1.1)) { stepDist = 0; Cue.at(tier.equals("walk") ? "fly_step_walk_npc_ceramic" : "fly_step_run_npc_ceramic", sl, position()); }
		}
		if (getY() < -200) discard();
	}

	/** BO2 leaves bodies on the floor for a while; vanilla removes them after 1 s with a white poof cloud. */
	@Override protected void tickDeath() {
		if (deathTime < Integer.MAX_VALUE - 1) deathTime++;
		if (deathTime >= BODY_TICKS && !level().isClientSide() && !isRemoved()) discard();
	}

	@Override protected boolean isSunSensitive() { return false; }
	@Override protected boolean convertsInWater() { return false; }
	@Override public boolean canBreakDoors() { return false; }
	@Override public boolean isBaby() { return false; }
	@Override public boolean removeWhenFarAway(double d) { return false; }
	@Override protected boolean shouldDropLoot() { return false; }
	@Override public boolean shouldDropExperience() { return false; }
	@Override protected void dropAllDeathLoot(ServerLevel level, DamageSource source) {}

	// all voices and footsteps come from our cues, vanilla zombie sounds stay quiet
	@Override protected SoundEvent getAmbientSound() { return null; }
	@Override protected SoundEvent getHurtSound(DamageSource source) { return null; }
	@Override protected SoundEvent getDeathSound() { return null; }

	/** Walks to the window, tears the boards one by one, then climbs in. */
	static final class BarrierGoal extends Goal {
		private final ZcZombie z;

		BarrierGoal(ZcZombie z) {
			this.z = z;
			setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
		}

		@Override public boolean canUse() { return z.barrier != null && z.stage < 3; }
		@Override public boolean canContinueToUse() { return z.barrier != null && z.stage < 3; }
		@Override public boolean requiresUpdateEveryTick() { return true; }

		private static double flat(Vec3 a, Vec3 b) { double dx = a.x - b.x, dz = a.z - b.z; return Math.sqrt(dx * dx + dz * dz); }

		@Override public void tick() {
			Barrier b = z.barrier;
			double speed = 1.0;
			z.stuckTicks++;
			switch (z.stage) {
				case 0 -> {
					if (flat(z.position(), b.outsideSpot) < 1.0) { z.stage = 1; z.tearTicks = 0; z.stuckTicks = 0; z.getNavigation().stop(); }
					else if (z.getNavigation().isDone() || z.tickCount % 20 == 0)
						z.getNavigation().moveTo(b.outsideSpot.x, b.outsideSpot.y, b.outsideSpot.z, speed);
				}
				case 1 -> {
					z.getLookControl().setLookAt(b.center);
					if (b.open()) { z.stage = 2; z.stuckTicks = 0; return; }
					if (++z.tearTicks >= (int) Sheets.sys("board_tear_ticks")) {
						z.tearTicks = 0;
						if (b.tear()) {
							z.swing(InteractionHand.MAIN_HAND);
							if (z.level() instanceof ServerLevel sl) {
								Cue.at("zmb_break_boards", sl, b.center);
								Cue.at(Sheets.tier(z.tier).cueTear(), sl, z.position());
							}
						}
					}
				}
				case 2 -> {
					if (flat(z.position(), b.insideSpot) < 1.3) { z.stage = 3; z.stuckTicks = 0; return; }
					if (z.getNavigation().isDone() || z.tickCount % 20 == 0)
						z.getNavigation().moveTo(b.insideSpot.x, b.insideSpot.y, b.insideSpot.z, speed);
					if (z.stuckTicks > 400) { z.teleportTo(b.insideSpot.x, b.insideSpot.y, b.insideSpot.z); z.stage = 3; }
				}
				default -> {}
			}
			if (z.stage == 0 && z.stuckTicks > 1200) { z.teleportTo(b.outsideSpot.x, b.outsideSpot.y, b.outsideSpot.z); z.stuckTicks = 0; }
		}
	}

	/** The vanilla zombie melee, but only once the zombie is inside. */
	static final class HuntGoal extends ZombieAttackGoal {
		private final ZcZombie z;

		HuntGoal(ZcZombie z) {
			super(z, 1.0, false);
			this.z = z;
		}

		@Override public boolean canUse() { return z.stage == 3 && super.canUse(); }
		@Override public boolean canContinueToUse() { return z.stage == 3 && super.canContinueToUse(); }
	}
}
