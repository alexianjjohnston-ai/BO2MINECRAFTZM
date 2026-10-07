package com.zombiecraft.client;

import com.zombiecraft.bo2.TexturePack;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.repository.PackRepository;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/** Turns on the generated "Block Ops 2" resource pack (and reloads resources when it was just rebuilt). */
public final class PackSelector {
	private PackSelector() {}

	/** Call on the render thread. {@code rebuilt}: the pack files changed this session, so a selected pack must be reloaded. */
	public static void enable(boolean rebuilt) {
		Minecraft mc = Minecraft.getInstance();
		if (!Files.isRegularFile(TexturePack.dir(mc.gameDirectory.toPath()).resolve("pack.mcmeta"))) return;
		PackRepository repo = mc.getResourcePackRepository();
		repo.reload();
		String id = "file/" + TexturePack.FOLDER;
		if (!repo.getAvailableIds().contains(id)) return;
		List<String> selected = new ArrayList<>(repo.getSelectedIds());
		if (selected.contains(id)) {
			if (rebuilt) mc.reloadResourcePacks();
			return;
		}
		selected.add(id); // last = highest priority
		repo.setSelected(selected);
		mc.options.updateResourcePacks(repo);
	}
}
