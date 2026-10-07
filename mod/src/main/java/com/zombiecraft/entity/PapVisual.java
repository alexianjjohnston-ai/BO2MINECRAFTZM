package com.zombiecraft.entity;

/** Shared Pack-a-Punch dimensions and animation timing, in blocks and game ticks. */
public final class PapVisual {
	private PapVisual() {}
	public static final int IDLE = 0, UPGRADING = 1, READY = 2;
	public static final int INTAKE_TICKS = 24, RETURN_TICKS = 20;
	// Bounds of the local BO2 machine at the prop renderer's 0.0225 scale.
	public static final double WIDTH = 1.93, DEPTH = 1.09, HEIGHT = 2.10;
	public static float ease(float value) {
		float t = Math.max(0, Math.min(1, value));
		return t * t * (3 - 2 * t);
	}
}
