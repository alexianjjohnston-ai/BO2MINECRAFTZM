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
	public static final int PHASE_IDLE = 0, PHASE_COUNTDOWN = 1, PHASE_ACTIVE = 2, PHASE_INTERMISSION = 3, PHASE_GAMEOVER = 4,
			/** Online match lobby: the world is open, players gather at the spawn and the host starts the match. */
			PHASE_LOBBY = 5;
	public static final int FEEDBACK_SHOT = 0, FEEDBACK_RELOAD_START = 1, FEEDBACK_RELOAD_STOP = 2, FEEDBACK_HIT = 3;

	/** Immediate, server-confirmed combat effects. Weapon is the base item ID, including for upgraded guns. */
	public record CombatFeedback(int kind, int slot, String weapon, int durationTicks, boolean headshot, boolean killed) implements CustomPacketPayload {
		public static final Type<CombatFeedback> TYPE = new Type<>(id("combat_feedback"));
		public static final StreamCodec<RegistryFriendlyByteBuf, CombatFeedback> CODEC = StreamCodec.ofMember(
				(p, b) -> { b.writeVarInt(p.kind); b.writeVarInt(p.slot); b.writeUtf(p.weapon); b.writeVarInt(p.durationTicks); b.writeBoolean(p.headshot); b.writeBoolean(p.killed); },
				b -> new CombatFeedback(b.readVarInt(), b.readVarInt(), b.readUtf(), b.readVarInt(), b.readBoolean(), b.readBoolean()));
		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	/** The player was hurt by something at (x, z): drives the red hit-direction arc. */
	public record HitDirection(double x, double z) implements CustomPacketPayload {
		public static final Type<HitDirection> TYPE = new Type<>(id("hit_direction"));
		public static final StreamCodec<RegistryFriendlyByteBuf, HitDirection> CODEC = StreamCodec.ofMember(
				(p, b) -> { b.writeDouble(p.x); b.writeDouble(p.z); }, b -> new HitDirection(b.readDouble(), b.readDouble()));
		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	/** Everything the HUD needs, sent a few times a second. */
	public record StateSync(int phase, int round, int points, int mag, int reserve, String gun, String prompt, String message,
			boolean interactable, int zombiesLeft, int countdownSec, int roundsSurvived,
			int perks, int instaSec, int doubleSec, int kills, int headshots, int downs, int revives,
			int bleedSec, int revivePct, int grenades) implements CustomPacketPayload {
		public static final Type<StateSync> TYPE = new Type<>(id("state"));
		public static final StreamCodec<RegistryFriendlyByteBuf, StateSync> CODEC = StreamCodec.ofMember(StateSync::write, StateSync::read);

		/** Above the four perk bits of {@code perks}: the power is on, a perk bottle is being drunk, and which one (two bits from DRINK_PERK_SHIFT). */
		public static final int FLAG_POWER = 256, FLAG_DRINKING = 512, DRINK_PERK_SHIFT = 10;

		public boolean drinking() { return (perks & FLAG_DRINKING) != 0; }
		/** Bit number of the perk being drunk (0 Juggernog, 1 Speed Cola, 2 Double Tap, 3 Quick Revive). */
		public int drinkPerk() { return (perks >> DRINK_PERK_SHIFT) & 3; }

		void write(RegistryFriendlyByteBuf b) {
			b.writeVarInt(phase); b.writeVarInt(round); b.writeVarInt(points); b.writeVarInt(mag); b.writeVarInt(reserve);
			b.writeUtf(gun); b.writeUtf(prompt); b.writeUtf(message); b.writeBoolean(interactable);
			b.writeVarInt(zombiesLeft); b.writeVarInt(countdownSec); b.writeVarInt(roundsSurvived);
			b.writeVarInt(perks); b.writeVarInt(instaSec); b.writeVarInt(doubleSec);
			b.writeVarInt(kills); b.writeVarInt(headshots); b.writeVarInt(downs); b.writeVarInt(revives);
			b.writeVarInt(bleedSec); b.writeVarInt(revivePct); b.writeVarInt(grenades);
		}

		static StateSync read(RegistryFriendlyByteBuf b) {
			return new StateSync(b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readVarInt(),
					b.readUtf(), b.readUtf(), b.readUtf(), b.readBoolean(), b.readVarInt(), b.readVarInt(), b.readVarInt(),
					b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readVarInt());
		}

		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	/** One player's line on the scoreboard, plus the stance the BO2 character body is drawn in. */
	public record RosterEntry(java.util.UUID id, String name, int points, int kills, int downs, int revives, int headshots, int stance) {
		public static final int STAND = 0, PRONE = 1, DOWNED = 2, DEAD = 3;
	}

	/** Every player in the match, for the tab scoreboard and for drawing teammates as BO2 characters. */
	public record Roster(java.util.List<RosterEntry> entries) implements CustomPacketPayload {
		public static final Type<Roster> TYPE = new Type<>(id("roster"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Roster> CODEC = StreamCodec.ofMember(Roster::write, Roster::read);

		void write(RegistryFriendlyByteBuf b) {
			b.writeVarInt(entries.size());
			for (RosterEntry e : entries) {
				b.writeUUID(e.id); b.writeUtf(e.name, 32);
				b.writeVarInt(e.points); b.writeVarInt(e.kills); b.writeVarInt(e.downs); b.writeVarInt(e.revives); b.writeVarInt(e.headshots); b.writeVarInt(e.stance);
			}
		}

		static Roster read(RegistryFriendlyByteBuf b) {
			int n = b.readVarInt();
			java.util.List<RosterEntry> list = new java.util.ArrayList<>(n);
			for (int i = 0; i < n; i++)
				list.add(new RosterEntry(b.readUUID(), b.readUtf(32), b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readVarInt()));
			return new Roster(list);
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
	public record Input(boolean fireHeld, boolean fireClick, boolean interactHeld, boolean reload, boolean melee, boolean prone, boolean ads, boolean grenade) implements CustomPacketPayload {
		public static final Type<Input> TYPE = new Type<>(id("input"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Input> CODEC = StreamCodec.ofMember(
				(p, b) -> { b.writeBoolean(p.fireHeld); b.writeBoolean(p.fireClick); b.writeBoolean(p.interactHeld); b.writeBoolean(p.reload); b.writeBoolean(p.melee); b.writeBoolean(p.prone); b.writeBoolean(p.ads); b.writeBoolean(p.grenade); },
				b -> new Input(b.readBoolean(), b.readBoolean(), b.readBoolean(), b.readBoolean(), b.readBoolean(), b.readBoolean(), b.readBoolean(), b.readBoolean()));
		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	/** A joining player's lobby code; the host's game refuses players whose code is missing or wrong. */
	public record JoinCode(String code) implements CustomPacketPayload {
		public static final Type<JoinCode> TYPE = new Type<>(id("join_code"));
		public static final StreamCodec<RegistryFriendlyByteBuf, JoinCode> CODEC = StreamCodec.ofMember((p, b) -> b.writeUtf(p.code, 16), b -> new JoinCode(b.readUtf(16)));
		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	/** The host pressed START MATCH in the lobby. */
	public record StartMatch() implements CustomPacketPayload {
		public static final Type<StartMatch> TYPE = new Type<>(id("start_match"));
		public static final StreamCodec<RegistryFriendlyByteBuf, StartMatch> CODEC = StreamCodec.ofMember((p, b) -> {}, b -> new StartMatch());
		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	/** Which Tranzit place this game is set at (depot, town, power, diner, farm), sent to every player who joins. */
	public record Place(String place) implements CustomPacketPayload {
		public static final Type<Place> TYPE = new Type<>(id("place"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Place> CODEC = StreamCodec.ofMember((p, b) -> b.writeUtf(p.place, 32), b -> new Place(b.readUtf(32)));
		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	/** The game over screen is done: leave the match for the title screen. */
	public record EndMatch() implements CustomPacketPayload {
		public static final Type<EndMatch> TYPE = new Type<>(id("end_match"));
		public static final StreamCodec<RegistryFriendlyByteBuf, EndMatch> CODEC = StreamCodec.ofMember((p, b) -> {}, b -> new EndMatch());
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
		PayloadTypeRegistry.playS2C().register(HitDirection.TYPE, HitDirection.CODEC);
		PayloadTypeRegistry.playS2C().register(Roster.TYPE, Roster.CODEC);
		PayloadTypeRegistry.playS2C().register(EndMatch.TYPE, EndMatch.CODEC);
		PayloadTypeRegistry.playS2C().register(Place.TYPE, Place.CODEC);
		PayloadTypeRegistry.playS2C().register(CuePlay.TYPE, CuePlay.CODEC);
		PayloadTypeRegistry.playS2C().register(CueStop.TYPE, CueStop.CODEC);
		PayloadTypeRegistry.playS2C().register(Shot.TYPE, Shot.CODEC);
		PayloadTypeRegistry.playC2S().register(Input.TYPE, Input.CODEC);
		PayloadTypeRegistry.playC2S().register(StartMatch.TYPE, StartMatch.CODEC);
		PayloadTypeRegistry.playC2S().register(JoinCode.TYPE, JoinCode.CODEC);
	}
}
