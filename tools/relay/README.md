# Block Ops 2 join-code relay

Lets friends join with a 6-character code, with nobody forwarding a port. The host's game dials out to the relay; the relay pipes each joiner to it.

1. Run `relay.py` on any machine with a public address (a cheap VPS, Fly.io, Railway, or a home PC with TCP port 25565 forwarded once):
   `python relay.py --port 25565` (Python 3.8+, no packages). Open that TCP port in the machine's firewall.
2. Everyone who plays (host and joiners) puts the relay's address in `config/zombiecraft.properties` inside the game folder:
   `relay=your.server.example:25565`
   (or `-Dzombiecraft.relay=...`). To ship it to players, set it once in the packaged config.
3. Host: ONLINE GAME: ON, START MATCH. The lobby shows `JOIN CODE`. Friends pick Join Game, type the code, and appear in the lobby.

`python test_relay.py` checks the relay (host + two joiners + a bad code). Without `relay=` the playit.gg steps are shown as before.
The relay only forwards bytes to a registered host's own game port; it holds no accounts and stores nothing.
