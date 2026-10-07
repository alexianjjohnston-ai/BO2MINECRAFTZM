package com.zombiecraft.client.render;

import net.minecraft.client.model.ZombieModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.util.Mth;

/** The humanoid zombie body with a hunched, lurching posture that gets more violent with speed, plus board-tearing and climbing poses. */
public class ZcZombieModel extends ZombieModel<ZcZombieState> {
	public ZcZombieModel(ModelPart root) { super(root); }

	@Override public void setupAnim(ZcZombieState s) {
		super.setupAnim(s); // walk cycle and arms-forward reach
		float t = s.ageInTicks;
		float phase = (s.variant & 7) * 0.8f;
		float lean = switch (s.tier) { case 0 -> 0.14f; case 1 -> 0.32f; default -> 0.52f; };
		// hunch: the torso leans forward and the head droops to one side, differently for each zombie
		body.xRot += lean;
		head.xRot += lean * 0.5f - 0.1f;
		head.zRot += ((s.variant & 1) == 0 ? 0.22f : -0.22f) + Mth.sin(t * 0.07f + phase) * 0.06f;
		head.yRot += Mth.sin(t * 0.05f + phase) * 0.18f;
		if (s.stage == 3) {
			// lurching: arms reach and wobble out of step with the legs
			rightArm.xRot += Mth.sin(t * 0.21f + phase) * 0.18f;
			leftArm.xRot += Mth.sin(t * 0.23f + phase + 1.7f) * 0.18f;
			rightArm.zRot += 0.1f + Mth.sin(t * 0.17f + phase) * 0.07f;
			leftArm.zRot -= 0.1f + Mth.sin(t * 0.19f + phase) * 0.07f;
			if (s.tier == 2) { // sprinters flail
				rightArm.xRot += Mth.sin(t * 0.9f + phase) * 0.55f - 0.4f;
				leftArm.xRot += Mth.sin(t * 0.9f + phase + Mth.PI) * 0.55f - 0.4f;
			}
		} else if (s.stage == 1) {
			// at the window, ripping boards: both arms high, hauling down in turn
			float pull = Mth.sin(t * 0.55f + phase);
			rightArm.xRot = -2.35f + pull * 0.65f;
			leftArm.xRot = -2.35f - pull * 0.65f;
			rightArm.zRot = 0.12f; leftArm.zRot = -0.12f;
			rightArm.yRot = 0f; leftArm.yRot = 0f;
			body.xRot = 0.18f + Math.abs(pull) * 0.12f;
			rightLeg.xRot = 0f; leftLeg.xRot = 0f;
		} else if (s.stage == 2) {
			// climbing through: reach up and in, knees pumping
			rightArm.xRot = -2.5f; leftArm.xRot = -2.5f;
			rightArm.zRot = 0.2f; leftArm.zRot = -0.2f;
			body.xRot = 0.45f;
			rightLeg.xRot = -0.9f * Mth.sin(t * 0.4f + phase);
			leftLeg.xRot = 0.9f * Mth.sin(t * 0.4f + phase);
		}
	}
}
