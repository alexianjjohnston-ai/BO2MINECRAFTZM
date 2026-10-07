package com.zombiecraft.client.render;

import net.minecraft.client.renderer.entity.state.ZombieRenderState;

/** What the zombie renderer needs besides the vanilla humanoid state: the server-synced anim stage and speed tier, plus a per-zombie look. */
public class ZcZombieState extends ZombieRenderState {
	/** 0 walk to the window, 1 tear boards, 2 climb in, 3 free (hunting). */
	public int stage = 3;
	/** 0 walk, 1 run, 2 sprint. */
	public int tier;
	/** Stable per-zombie number: picks the skin and the head tilt. */
	public int variant;
}
