# Bench E2E (phase-2 spec §7): reads the belt's serial lines from COM6 and checks one scenario.
import argparse
import re
import subprocess
import sys
import time
import unittest

WATCH_ACTIVITY = "io.github.santiquiroz.blindside/.wear.MainActivity"
FIRST_STREAM_DEADLINE_S = 30
MIN_ACL_BUFFERS = 8
MIN_HOST_STACK_BYTES = 1024
NO_BOOT = {"fw": None, "acl_total": None}
LINK = re.compile(
    r"link(\d)\[role=(\w+) trusted=(\d) sub=(\d) mtu=(\d+) itvl=(\d+) lat=(\d+) sup=(\d+) sent=(\d+) dropped=(\d+)\]")
TAIL = re.compile(
    r"bonds=(\d+) pair=(\w+) disc=(-?\d+) acl=(\d+) hstk=(\d+) tx\[fail=(\d+) dropped=(\d+) skipped=(\d+)\]")
BOOT_FW = re.compile(r"blindside fw (\S+)")
BOOT_ACL = re.compile(r"ble: controller acl buffers=(\d+)")


def link_fields(match):
    return {"slot": int(match[1]), "role": match[2], "trusted": match[3] == "1", "sub": match[4] == "1",
            "mtu": int(match[5]), "itvl": int(match[6]), "lat": int(match[7]), "sent": int(match[9]),
            "dropped": int(match[10])}


def parse_diag(line, at_s):
    tail = TAIL.search(line)
    if not line.startswith("diag ") or tail is None:
        return None
    return {"at_s": at_s, "links": [link_fields(m) for m in LINK.finditer(line)], "bonds": int(tail[1]),
            "pair": tail[2], "disc": int(tail[3]), "acl": int(tail[4]), "hstk": int(tail[5]),
            "skipped": int(tail[8])}


def first_match(pattern, lines):
    return next((found[1] for found in map(pattern.search, lines) if found), None)


def boot_facts(lines):
    acl_total = first_match(BOOT_ACL, lines)
    return {"fw": first_match(BOOT_FW, lines), "acl_total": int(acl_total) if acl_total else None}


def streaming(diag, role):
    return [link for link in diag["links"] if link["role"] == role and link["trusted"] and link["sub"]]


def streaming_link(diag, role):
    return next(iter(streaming(diag, role)), None)


def first_stream_index(diags, role):
    return next((index for index, diag in enumerate(diags) if streaming_link(diag, role)), len(diags))


def stream_run(diags, role):
    return [streaming_link(diag, role) for diag in diags[first_stream_index(diags, role):]]


def strictly_rising(counts):
    return all(later > earlier for earlier, later in zip(counts, counts[1:]))


def one_slot(run):
    return len({link["slot"] for link in run}) <= 1


def streamed_in_two_lines(diags, role):
    return len(stream_run(diags, role)) >= 2


# A line without the link means it dropped; another slot or a lower `sent` (it restarts at 0) means it reconnected.
def kept_streaming(diags, role):
    run = stream_run(diags, role)
    return all(run) and one_slot(run) and strictly_rising([link["sent"] for link in run])


def stream_rules(diags, role):
    return [(f"{role} streaming in 2+ diag lines", streamed_in_two_lines(diags, role)),
            (f"{role} stopped streaming", kept_streaming(diags, role))]


def watch_never_dropped(diags):
    return all(link["dropped"] == 0 for diag in diags for link in streaming(diag, "watch"))


def skipped_flat(diags):
    return diags[-1]["skipped"] == diags[0]["skipped"]


def first_stream_in_time(diags, role):
    return any(streaming(diag, role) and diag["at_s"] <= FIRST_STREAM_DEADLINE_S for diag in diags)


def scenario_rules(diags, check):
    rules = {
        "watch": [("watch streaming within 30 s", first_stream_in_time(diags, "watch"))] + stream_rules(diags, "watch"),
        "both": stream_rules(diags, "watch") + stream_rules(diags, "phone"),
        "watch-steady": stream_rules(diags, "watch") + [("tx[skipped] flat", skipped_flat(diags))],
    }
    return rules[check]


def link_rules(diags):
    return [("dropped(watch)=0", watch_never_dropped(diags)),
            ("free controller buffers never 0", all(diag["acl"] > 0 for diag in diags)),
            ("host stack >= 1024 B", all(diag["hstk"] >= MIN_HOST_STACK_BYTES for diag in diags))]


# After --reset the boot lines must have been read: a missing one is a failure, not a skipped check.
def boot_rules(boot, boot_expected, expect_fw):
    return [("missing boot line 'blindside fw'", not boot_expected or boot["fw"] is not None),
            ("missing boot line 'ble: controller acl buffers='", not boot_expected or boot["acl_total"] is not None),
            ("controller acl buffers >= 8", boot["acl_total"] is None or boot["acl_total"] >= MIN_ACL_BUFFERS),
            (f"firmware {expect_fw}", expect_fw is None or boot["fw"] == expect_fw)]


def verdict(diags, boot, check, expect_fw, boot_expected):
    if len(diags) < 3:
        return ["fewer than 3 diag lines in 0.2.0 format"]
    rules = scenario_rules(diags, check) + link_rules(diags) + boot_rules(boot, boot_expected, expect_fw)
    return [name for name, passed in rules if not passed]


def adb_devices():
    out = subprocess.run(["adb", "devices"], capture_output=True, text=True, check=True).stdout
    return [line.split("\t")[0] for line in out.splitlines()[1:] if line.endswith("\tdevice")]


def is_watch(device):
    props = subprocess.run(["adb", "-s", device, "shell", "getprop", "ro.build.characteristics"],
                           capture_output=True, text=True).stdout
    return "watch" in props


def start_watch_app():
    watches = [device for device in adb_devices() if is_watch(device)]
    if not watches:
        raise SystemExit("FAIL: no watch on adb")
    subprocess.run(["adb", "-s", watches[0], "shell", "am", "start", "-n", WATCH_ACTIVITY], check=True,
                   capture_output=True)


# Opening the port with DTR/RTS asserted resets the DevKit through its auto-reset circuit.
def open_without_reset(port):
    # pyserial is imported here so --self-test (and CI) runs where only the bench PC has it installed.
    import serial

    link = serial.Serial()
    link.port = port
    link.baudrate = 115200
    link.timeout = 0.5
    link.dtr = False
    link.rts = False
    link.open()
    return link


# EN low for 150 ms with GPIO0 high: a normal boot, so the boot lines can be read.
def reset_board(link):
    link.rts = True
    time.sleep(0.15)
    link.rts = False


def prepare_bench(link, reset, start_app):
    if reset:
        reset_board(link)
    if start_app:
        start_watch_app()


def read_lines(port, seconds, reset, start_app):
    lines = []
    started = time.time()
    with open_without_reset(port) as link:
        prepare_bench(link, reset, start_app)
        while time.time() - started < seconds:
            line = link.readline().decode("utf-8", errors="replace").strip()
            print(line, flush=True)
            lines.append((line, time.time() - started))
    return lines


def run_self_test():
    tests = unittest.defaultTestLoader.loadTestsFromName("test_bench_e2e")
    return 0 if unittest.TextTestRunner(verbosity=2).run(tests).wasSuccessful() else 1


def parse_args():
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", default="COM6")
    parser.add_argument("--seconds", type=float, default=45)
    parser.add_argument("--check", choices=["watch", "both", "watch-steady"], default="watch")
    parser.add_argument("--start-watch-app", action="store_true")
    parser.add_argument("--reset", action="store_true")
    parser.add_argument("--expect-fw")
    parser.add_argument("--self-test", action="store_true", help="check the verdict rules on sample lines, no bench")
    return parser.parse_args()


def run_bench(args):
    lines = read_lines(args.port, args.seconds, args.reset, args.start_watch_app)
    diags = [diag for diag in (parse_diag(line, at_s) for line, at_s in lines) if diag]
    missing = verdict(diags, boot_facts([line for line, _ in lines]), args.check, args.expect_fw, args.reset)
    print(f"FAIL {args.check}: {', '.join(missing)}" if missing else f"PASS {args.check}")
    return 1 if missing else 0


def main():
    args = parse_args()
    return run_self_test() if args.self_test else run_bench(args)


if __name__ == "__main__":
    sys.exit(main())
