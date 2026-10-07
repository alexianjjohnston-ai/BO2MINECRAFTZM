package com.zombiecraft;

import com.zombiecraft.cmd.DevCommands;
import com.zombiecraft.entity.ZcEntities;
import com.zombiecraft.game.Game;
import com.zombiecraft.item.ModItems;
import com.zombiecraft.net.Payloads;
import com.zombiecraft.sheet.Sheets;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ZombiecraftMod implements ModInitializer {
	public static final Logger LOG = LoggerFactory.getLogger("zombiecraft");

	@Override
	public void onInitialize() {
		LOG.info("Block Ops 2: {} weapons, {} rounds, {} windows, {} cues loaded from the sheets", Sheets.WEAPONS.size(), Sheets.ROUNDS.size(), Sheets.WINDOWS.size(), Sheets.CUES.size());
		Payloads.register();
		com.zombiecraft.block.ModBlocks.register();
		ModItems.register();
		ZcEntities.register();
		Game.register();
		DevCommands.register();
		com.zombiecraft.game.Bench.register();
		com.zombiecraft.game.FeedbackBench.register();
		com.zombiecraft.game.PapBench.register();
	}
}
