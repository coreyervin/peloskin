#!/usr/bin/env python3
"""Pretend to be Grupetto's DirCon feed (TCP 8081) so PeloSkin can be tested off the bike.

Sends FTMS Indoor Bike Data once a second following a made-up Power Zone ride.
Usage: python3 tools/fake_grupetto.py [--port 8081] [--hr]
  --hr      also send heart rate, as if a strap were paired to Grupetto
"""
import argparse
import math
import random
import socket
import struct
import threading
import time

FTMS_BIKE = bytes.fromhex("00002ad200001000800000805f9b34fb")
HR = bytes.fromhex("00002a3700001000800000805f9b34fb")


def frame(msg, seq, payload=b"", response=0):
    return struct.pack(">BBBBH", 1, msg, seq, response, len(payload)) + payload


def bike_data(cadence, power, speed_mph, resistance):
    flags = 0x0004 | 0x0020 | 0x0040  # speed (bit 0 clear), cadence, resistance, power
    return struct.pack("<HHHhh", flags, int(speed_mph * 1.60934 * 100), int(cadence * 2), int(resistance), int(power))


def ride(t):
    """Warm-up, then intervals, then cool-down. t in seconds."""
    if t < 300:
        base = 90 + t / 300 * 60
    elif t < 1500:
        base = 230 if (t // 120) % 2 == 0 else 160
    else:
        base = 110
    power = max(0, base + random.gauss(0, 8))
    cadence = 78 + power / 12 + random.gauss(0, 1.5)
    resistance = min(100, 25 + power / 8)
    # Peloton's power-to-speed curve (same one Grupetto uses).
    p = math.sqrt(power)
    speed = (-1.635 + 2.325 * p - 0.064 * p * p + 0.001 * p ** 3) if power >= 26 else 0
    return cadence, power, speed, resistance


def client(conn, args):
    subscribed = set()
    conn.settimeout(0.05)
    t = 0.0
    while True:
        try:
            data = conn.recv(512)
            if not data:
                return
            i = 0
            while i + 6 <= len(data):
                _, msg, seq, _, length = struct.unpack(">BBBBH", data[i:i + 6])
                body = data[i + 6:i + 6 + length]
                i += 6 + length
                if msg == 0x05:
                    uuid = body[:16]
                    if uuid == HR and not args.hr:
                        conn.sendall(frame(msg, seq, response=0x04))  # characteristic not found
                    else:
                        subscribed.add(uuid)
                        conn.sendall(frame(msg, seq, uuid))
                        print("client subscribed to", uuid.hex()[4:8])
        except socket.timeout:
            pass
        except OSError:
            return
        time.sleep(0.95)
        t += 1
        c, p, s, r = ride(t % 1800)
        try:
            if FTMS_BIKE in subscribed:
                conn.sendall(frame(0x06, 0, FTMS_BIKE + bike_data(c, p, s, r)))
            if HR in subscribed:
                conn.sendall(frame(0x06, 0, HR + bytes([0, int(100 + p / 4)])))
        except OSError:
            return


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8081)
    ap.add_argument("--hr", action="store_true")
    args = ap.parse_args()
    srv = socket.socket()
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("127.0.0.1", args.port))
    srv.listen()
    print(f"fake Grupetto feed on 127.0.0.1:{args.port}")
    while True:
        conn, addr = srv.accept()
        print("client connected", addr)
        threading.Thread(target=client, args=(conn, args), daemon=True).start()


if __name__ == "__main__":
    main()
