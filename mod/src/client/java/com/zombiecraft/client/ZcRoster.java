package com.zombiecraft.client;

import com.zombiecraft.net.Payloads;

import java.util.List;
import java.util.UUID;

/** The last roster the server sent: the scoreboard rows and each player's stance and BO2 character. */
public final class ZcRoster {
	private ZcRoster() {}

	private static volatile List<Payloads.RosterEntry> entries = List.of();

	public static void set(List<Payloads.RosterEntry> list) { entries = list; }
	public static void clear() { entries = List.of(); }
	public static List<Payloads.RosterEntry> all() { return entries; }

	public static Payloads.RosterEntry get(UUID id) {
		for (Payloads.RosterEntry e : entries) if (e.id().equals(id)) return e;
		return null;
	}

	/** Position in the roster, which picks the player's BO2 character (the four survivors in order); a stranger gets one from the id. */
	public static int index(UUID id) {
		List<Payloads.RosterEntry> list = entries;
		for (int i = 0; i < list.size(); i++) if (list.get(i).id().equals(id)) return i;
		return Math.floorMod(id.hashCode(), 4);
	}
}
