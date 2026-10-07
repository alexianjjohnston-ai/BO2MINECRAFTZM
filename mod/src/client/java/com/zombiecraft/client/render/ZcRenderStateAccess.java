package com.zombiecraft.client.render;

/** Lets the item render state remember which cached model its item is drawn with. */
public interface ZcRenderStateAccess {
	String zombiecraft$getModel();
	void zombiecraft$setModel(String key);
}
