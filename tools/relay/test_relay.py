#!/usr/bin/env python3
"""Self-test: starts relay.py, plays a host (echo server behind it) and two joiners. Run: python test_relay.py"""
import socket, subprocess, sys, threading, time, os

PORT = 25871
here = os.path.dirname(os.path.abspath(__file__))
proc = subprocess.Popen([sys.executable, os.path.join(here, "relay.py"), "--port", str(PORT)])
time.sleep(1.0)

def line(s):
    b = b""
    while not b.endswith(b"\n"):
        c = s.recv(1)
        if not c: break
        b += c
    return b.decode().strip()

# the "Minecraft server": an echo server on a free local port
echo = socket.socket(); echo.bind(("127.0.0.1", 0)); echo.listen(5)
def echo_loop():
    while True:
        c, _ = echo.accept()
        def h(c=c):
            while (d := c.recv(4096)): c.sendall(d.upper())
            c.close()
        threading.Thread(target=h, daemon=True).start()
threading.Thread(target=echo_loop, daemon=True).start()

ok = True
try:
    host = socket.create_connection(("127.0.0.1", PORT)); host.sendall(b"HOST\n")
    code = line(host).split()[1]
    print("code", code)

    def host_loop():
        while True:
            l = line(host)
            if l.startswith("CONN "):
                d = socket.create_connection(("127.0.0.1", PORT)); d.sendall(("DATA %s\n" % l[5:]).encode())
                loc = socket.create_connection(echo.getsockname())
                threading.Thread(target=lambda: [loc.sendall(x) for x in iter(lambda: d.recv(4096), b"")], daemon=True).start()
                threading.Thread(target=lambda: [d.sendall(x) for x in iter(lambda: loc.recv(4096), b"")], daemon=True).start()
    threading.Thread(target=host_loop, daemon=True).start()

    for name in ("alpha", "bravo"):
        j = socket.create_connection(("127.0.0.1", PORT)); j.sendall(("JOIN %s\n" % code.lower()).encode())
        assert line(j) == "OK", "join refused"
        j.sendall(name.encode()); got = j.recv(100)
        print(name, "->", got)
        ok &= got == name.upper().encode()

    bad = socket.create_connection(("127.0.0.1", PORT)); bad.sendall(b"JOIN ZZZZZZ\n")
    r = line(bad); print("bad code ->", r); ok &= r.startswith("ERR")
finally:
    proc.kill()
print("PASS" if ok else "FAIL")
sys.exit(0 if ok else 1)
