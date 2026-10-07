package com.zombiecraft.net;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** All Zombiecraft network messages. Server is authoritative; clients only draw and play sounds. */
public final class Payloads {
	private Payloads() {}

	public static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath("zombiecraft", path); }

	/** Game phases shown on the HUD. */
	public static final int PHASE_IDLE = 0, PHASE_COUNTDOWN = 1, PHASE_ACTIVE = 2, PHASE_INTERMISSION = 3, PHASE_GAMEOVER = 4;
	public static final int FEEDBACK_SHOT = 0, FEEDBACK_RELOAD_START = 1, FEEDBACK_RELOAD_STOP = 2, FEEDBACK_HIT = 3;

	/** Immediate, server-confirmed combat effects. Weapon is the base item ID, including for upgraded guns. */
	public record CombatFeedback(int kind, int slot, String weapon, int durationTicks, boolean headshot, boolean killed) implements CustomPacketPayload {
		public static final Type<CombatFeedback> TYPE = new Type<>(id("combat_feedback"));
		public static final StreamCodec<RegistryFriendlyByteBuf, CombatFeedback> CODEC = StreamCodec.ofMember(
				(p, b) -> { b.writeVarInt(p.kind); b.writeVarInt(p.slot); b.writeUtf(p.weapon); b.writeVarInt(p.durationTicks); b.writeBoolean(p.headshot); b.writeBoolean(p.killed); },
				b -> new CombatFeedback(b.readVarInt(), b.readVarInt(), b.readUtf(), b.readVarInt(), b.readBoolean(), b.readBoolean()));
		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	/** Everything the HUD needs, sent a few times a second. */
	public record StateSync(int phase, int round, int points, int mag, int reserve, String gun, String prompt, String message,
			boolean interactable, int zombiesLeft, int countdownSec, int roundsSurvived,
			int perks, int instaSec, int doubleSec, int kills, int headshots, int downs, int revives,
			int bleedSec, int revivePct) implements CustomPacketPayload {
		public static final Type<StateSync> TYPE = new Type<>(id("state"));
		public static final StreamCodec<RegistryFriendlyByteBuf, StateSync> CODEC = StreamCodec.ofMember(StateSync::write, StateSync::read);

		void write(RegistryFriendlyByteBuf b) {
			b.writeVarInt(phase); b.writeVarInt(round); b.writeVarInt(points); b.writeVarInt(mag); b.writeVarInt(reserve);
			b.writeUtf(gun); b.writeUtf(prompt); b.writeUtf(message); b.writeBoolean(interactable);
			b.writeVarInt(zombiesLeft); b.writeVarInt(countdownSec); b.writeVarInt(roundsSurvived);
			b.writeVarInt(perks); b.writeVarInt(instaSec); b.writeVarInt(doubleSec);
			b.writeVarInt(kills); b.writeVarInt(headshots); b.writeVarInt(downs); b.writeVarInt(revives);
			b.writeVarInt(bleedSec); b.writeVarInt(revivePct);
		}

		static StateSync read(RegistryFriendlyByteBuf b) {
			return new StateSync(b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readVarInt(),
					b.readUtf(), b.readUtf(), b.readUtf(), b.readBoolean(), b.readVarInt(), b.readVarInt(), b.readVarInt(),
					b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readVarInt());
		}

		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	/** Play a cue (BO2 sound, or its vanilla fallback) for this client. */
	public record CuePlay(String cue, double x, double y, double z, boolean at, float volume, float pitch) implements CustomPacketPayload {
		public static final Type<CuePlay> TYPE = new Type<>(id("cue"));
		public static final StreamCodec<RegistryFriendlyByteBuf, CuePlay> CODEC = StreamCodec.ofMember(CuePlay::write, CuePlay::read);

		void write(RegistryFriendlyByteBuf b) {
			b.writeUtf(cue); b.writeDouble(x); b.writeDouble(y); b.writeDouble(z); b.writeBoolean(at); b.writeFloat(volume); b.writeFloat(pitch);
		}

		static CuePlay read(RegistryFriendlyByteBuf b) {
			return new CuePlay(b.readUtf(), b.readDouble(), b.readDouble(), b.readDouble(), b.readBoolean(), b.readFloat(), b.readFloat());
		}

		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	/** Stop a looping cue (key is the cue id plus an optional owner tag). */
	public record CueStop(String cue) implements CustomPacketPayload {
		public static final Type<CueStop> TYPE = new Type<>(id("cue_stop"));
		public static final StreamCodec<RegistryFriendlyByteBuf, CueStop> CODEC = StreamCodec.ofMember((p, b) -> b.writeUtf(p.cue), b -> new CueStop(b.readUtf()));
		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	/** What the player is holding down. Sent when it changes, and every tick while firing. */
	public record Input(boolean fireHeld, boolean fireClick, boolean interactHeld, boolean reload, boolean melee, boolean prone) implements CustomPacketPayload {
		public static final Type<Input> TYPE = new Type<>(id("input"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Input> CODEC = StreamCodec.ofMember(
				(p, b) -> { b.writeBoolean(p.fireHeld); b.writeBoolean(p.fireClick); b.writeBoolean(p.interactHeld); b.writeBoolean(p.reload); b.writeBoolean(p.melee); b.writeBoolean(p.prone); },
				b -> new Input(b.readBoolean(), b.readBoolean(), b.readBoolean(), b.readBoolean(), b.readBoolean(), b.readBoolean()));
		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	/** Test harness only: ask the client to save a screenshot of its own frame. */
	public record Shot(String name) implements CustomPacketPayload {
		public static final Type<Shot> TYPE = new Type<>(id("shot"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Shot> CODEC = StreamCodec.ofMember((p, b) -> b.writeUtf(p.name), b -> new Shot(b.readUtf()));
		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	public static void register() {
		PayloadTypeRegistry.playS2C().register(StateSync.TYPE, StateSync.CODEC);
		PayloadTypeRegistry.playS2C().register(CombatFeedback.TYPE, CombatFeedback.CODEC);
		PayloadTypeRegistry.playS2C().register(CuePlay.TYPE, CuePlay.CODEC);
		PayloadTypeRegistry.playS2C().register(CueStop.TYPE, CueStop.CODEC);
		PayloadTypeRegistry.playS2C().register(Shot.TYPE, Shot.CODEC);
		PayloadTypeRegistry.playC2S().register(Input.TYPE, Input.CODEC);
	}
}
