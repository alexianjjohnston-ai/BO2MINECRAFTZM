package com.zombiecraft.game;

/** Set by the client when the local BO2 model cache is usable, so the (integrated) server can swap the chest for the box mesh. */
public final class LocalAssets {
	private LocalAssets() {}

	public static volatile boolean models;
}
