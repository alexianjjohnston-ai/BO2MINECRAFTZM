#!/usr/bin/env python3
"""
Block Ops 2 join-code relay. Run it on any machine with a public IP (a cheap VPS, Fly.io, Railway, a home server with one forwarded port).
Players never need to forward ports: the host's game dials OUT to this relay and gets a 6-character code; friends type the code and the
relay pipes their connection to the host.

    python relay.py [--port 25570]

Protocol (one text line, then raw bytes), all on the one TCP port:
  HOST\\n          host control connection. Relay answers "CODE ABC123\\n", then sends "CONN <id>\\n" per joiner and "PING\\n" now and then.
  JOIN <code>\\n   joiner. Relay asks the host for a data connection, then answers "OK\\n" and pipes bytes. "ERR <why>\\n" and close on failure.
  DATA <id>\\n     the host's answer to a CONN line: a new connection that becomes the other end of that joiner.
Pure standard library, Python 3.8+.
"""
import argparse
import asyncio
import secrets

ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"  # no 0/O/1/I
MAX_HOSTS = 2000
MAX_PLAYERS_PER_HOST = 8
PAIR_TIMEOUT = 10
LINE_TIMEOUT = 10

hosts = {}    # code -> Host
pending = {}  # id -> (future, host)


class Host:
    def __init__(self, writer):
        self.writer = writer
        self.active = 0


def new_code():
    while True:
        c = "".join(secrets.choice(ALPHABET) for _ in range(6))
        if c not in hosts:
            return c


async def pipe(r, w):
    try:
        while True:
            data = await r.read(65536)
            if not data:
                break
            w.write(data)
            await w.drain()
    except (ConnectionError, asyncio.CancelledError, OSError):
        pass
    finally:
        try:
            w.close()
        except Exception:
            pass


async def splice(r1, w1, r2, w2):
    await asyncio.gather(pipe(r1, w2), pipe(r2, w1))


async def handle(reader, writer):
    try:
        line = await asyncio.wait_for(reader.readline(), LINE_TIMEOUT)
    except (asyncio.TimeoutError, ConnectionError, OSError):
        writer.close(); return
    parts = line.decode("ascii", "ignore").split()
    try:
        if parts[:1] == ["HOST"]:
            await do_host(reader, writer)
        elif len(parts) == 2 and parts[0] == "JOIN":
            await do_join(reader, writer, parts[1].upper())
        elif len(parts) == 2 and parts[0] == "DATA":
            await do_data(reader, writer, parts[1])
        else:
            writer.close()
    except Exception as e:  # never let one connection take the relay down
        print("error:", e)
        writer.close()


async def do_host(reader, writer):
    if len(hosts) >= MAX_HOSTS:
        writer.write(b"ERR busy\n"); writer.close(); return
    code = new_code()
    host = Host(writer)
    hosts[code] = host
    print("host registered", code, "(%d hosts)" % len(hosts))
    writer.write(("CODE %s\n" % code).encode()); await writer.drain()

    async def ping():
        while True:
            await asyncio.sleep(20)
            writer.write(b"PING\n"); await writer.drain()
    task = asyncio.ensure_future(ping())
    try:
        while await reader.read(1024):  # the host sends nothing; EOF = it left
            pass
    except (ConnectionError, OSError):
        pass
    finally:
        task.cancel()
        hosts.pop(code, None)
        writer.close()
        print("host left", code)


async def do_join(reader, writer, code):
    host = hosts.get(code)
    if host is None:
        writer.write(b"ERR no such code\n"); writer.close(); return
    if host.active >= MAX_PLAYERS_PER_HOST:
        writer.write(b"ERR full\n"); writer.close(); return
    cid = secrets.token_hex(8)
    fut = asyncio.get_event_loop().create_future()
    pending[cid] = (fut, host)
    host.active += 1
    try:
        host.writer.write(("CONN %s\n" % cid).encode()); await host.writer.drain()
        hr, hw = await asyncio.wait_for(fut, PAIR_TIMEOUT)
    except Exception:
        writer.write(b"ERR host did not answer\n"); writer.close()
        host.active -= 1; pending.pop(cid, None); return
    try:
        writer.write(b"OK\n"); await writer.drain()
        await splice(reader, writer, hr, hw)
    finally:
        host.active -= 1
        writer.close(); hw.close()


async def do_data(reader, writer, cid):
    entry = pending.pop(cid, None)
    if entry is None or entry[0].done():
        writer.close(); return
    entry[0].set_result((reader, writer))
    # the joiner's handler now owns both ends; keep this coroutine alive until the pipe closes
    while not writer.is_closing():
        await asyncio.sleep(1)


async def main(port):
    server = await asyncio.start_server(handle, "0.0.0.0", port)
    print("Block Ops 2 relay listening on port", port)
    async with server:
        await server.serve_forever()


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=25570)
    asyncio.run(main(ap.parse_args().port))
