package com.zombiecraft.cmd;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.zombiecraft.game.Game;
import com.zombiecraft.game.PlayerGame;
import com.zombiecraft.game.WeaponSystem;
import com.zombiecraft.net.Payloads;
import com.zombiecraft.sheet.Sheets;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** /zc commands used for testing: start, round N, points N, give ID, status. Players do not need them. */
public final class DevCommands {
	private DevCommands() {}

	public static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registry, env) -> dispatcher.register(Commands.literal("zc")
				.requires(s -> s.hasPermission(2))
				.then(Commands.literal("start").executes(c -> { Game g = Game.INSTANCE; if (g != null) g.start(); return 1; }))
				.then(Commands.literal("round").then(Commands.argument("n", IntegerArgumentType.integer(1, 200)).executes(c -> {
					Game g = Game.INSTANCE; int n = IntegerArgumentType.getInteger(c, "n");
					if (g == null || g.level == null) return 0;
					g.round = n - 1; g.zombiesToSpawn = 0; g.alive.forEach(z -> z.discard()); g.alive.clear();
					g.phase = Payloads.PHASE_INTERMISSION; g.intermission = 20;
					return 1;
				})))
				.then(Commands.literal("points").then(Commands.argument("n", IntegerArgumentType.integer(0)).executes(c -> {
					Game g = Game.INSTANCE; ServerPlayer p = c.getSource().getPlayerOrException();
					if (g == null) return 0;
					g.pg(p).points = IntegerArgumentType.getInteger(c, "n");
					return 1;
				})))
				.then(Commands.literal("give").then(Commands.argument("weapon", StringArgumentType.word()).executes(c -> {
					Game g = Game.INSTANCE; ServerPlayer p = c.getSource().getPlayerOrException();
					if (g == null) return 0;
					PlayerGame pg = g.pg(p);
					WeaponSystem.give(p, pg, WeaponSystem.slotFor(p, pg), Sheets.weapon(StringArgumentType.getString(c, "weapon")).id(), false);
					return 1;
				})))
				.then(Commands.literal("status").executes(c -> {
					Game g = Game.INSTANCE;
					if (g == null) return 0;
					c.getSource().sendSuccess(() -> Component.literal("phase=" + g.phase + " round=" + g.round + " left=" + (g.zombiesToSpawn + g.alive.size())
							+ " alive=" + g.alive.size() + " tick=" + g.tick), false);
					return 1;
				}))));
	}
}
