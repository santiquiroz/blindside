import argparse
import re
import subprocess
import sys
import time
from dataclasses import dataclass

LINK_WINDOW_S = 30.0
DIAG_PERIOD_S = 5.0
BOOT_WAIT_S = 15.0
BAUD = 115200
APP_PACKAGE = "io.github.santiquiroz.blindside"
APP_COMPONENT = APP_PACKAGE + "/io.github.santiquiroz.blindside.wear.MainActivity"

DUAL_LINK = re.compile(r"link\d\[role=watch trusted=(\d) sub=(\d)[^\]]* sent=(\d+)")
MVP_LINK = re.compile(r" link\[conn=\d sub=(\d) trusted=(\d)")
MVP_SENT = re.compile(r" tx\[sent=(\d+)")


@dataclass(frozen=True)
class WatchLink:
    trusted: bool
    subscribed: bool
    sent: int


@dataclass(frozen=True)
class Reading:
    seconds: float
    link: WatchLink


def watch_link_in(line):
    return dual_watch_link(line) or mvp_watch_link(line)


def dual_watch_link(line):
    match = DUAL_LINK.search(line)
    if match is None:
        return None
    return WatchLink(match.group(1) == "1", match.group(2) == "1", int(match.group(3)))


# Firmware 0.1.0 has one link and no role: that link is the watch.
def mvp_watch_link(line):
    link = MVP_LINK.search(line)
    sent = MVP_SENT.search(line)
    if link is None or sent is None:
        return None
    return WatchLink(link.group(2) == "1", link.group(1) == "1", int(sent.group(1)))


def verdict(readings):
    streaming = [r for r in readings if r.link.trusted and r.link.subscribed]
    if not streaming or streaming[0].seconds > LINK_WINDOW_S:
        return "FAIL no trusted, subscribed watch link within 30 s"
    first = streaming[0]
    rising = [r for r in streaming[1:] if r.link.sent > first.link.sent]
    if not rising:
        return "FAIL the watch link never sent more packets"
    return f"PASS trusted+sub at {first.seconds:.0f} s, sent {first.link.sent} -> {rising[0].link.sent}"


def open_port(name):
    import serial

    port = serial.Serial()
    port.port = name
    port.baudrate = BAUD
    port.timeout = 1
    # Deasserted DTR and RTS keep the DevKit's auto-reset circuit from rebooting the belt.
    port.dtr = False
    port.rts = False
    port.open()
    return port


def read_line(port):
    return port.readline().decode("utf-8", errors="replace").strip()


def wait_for_diag(port):
    end = time.monotonic() + BOOT_WAIT_S
    while time.monotonic() < end:
        if read_line(port).startswith("diag "):
            return True
    return False


def start_watch_app(device):
    shell = ["adb", "-s", device, "shell"]
    subprocess.run(shell + ["input", "keyevent", "KEYCODE_WAKEUP"], check=True)
    subprocess.run(shell + ["am", "force-stop", APP_PACKAGE], check=True)
    subprocess.run(shell + ["am", "start", "-n", APP_COMPONENT], check=True)


def reading_from(line, seconds):
    link = watch_link_in(line)
    return None if link is None else Reading(seconds, link)


def collect(port, started):
    readings = []
    while time.monotonic() - started < LINK_WINDOW_S + DIAG_PERIOD_S + 1:
        line = read_line(port)
        seconds = time.monotonic() - started
        if line.startswith("diag "):
            print(f"[{seconds:5.1f} s] {line}", flush=True)
            readings.append(reading_from(line, seconds))
    return [r for r in readings if r is not None]


def run(port, device):
    if not wait_for_diag(port):
        return "FAIL the belt printed no diag line"
    started = time.monotonic()
    start_watch_app(device)
    return verdict(collect(port, started))


def main(argv):
    parser = argparse.ArgumentParser(description="Spec section 7 step 2: the watch alone reaches a trusted, streaming link.")
    parser.add_argument("--port", default="COM6")
    parser.add_argument("--watch", required=True, help="adb serial of the watch")
    args = parser.parse_args(argv)
    port = open_port(args.port)
    try:
        result = run(port, args.watch)
    finally:
        port.close()
    print(f"REGRESSION {result}", flush=True)
    return 0 if result.startswith("PASS") else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
