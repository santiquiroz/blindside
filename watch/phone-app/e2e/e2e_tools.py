import re
import sys
import time
import xml.etree.ElementTree as ElementTree

BAUD = 115200
DIAG_PREFIX = "diag "
SENT_MODULO = 1_000_000
PHONE_ITVL_UNITS = range(48, 81)
EDIT_TEXT_CLASS = "android.widget.EditText"
LINK = re.compile(r"link(\d)\[([^\]]*)\]")
BOUNDS = re.compile(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]")


def open_without_reset(port):
    import serial

    link = serial.Serial()
    link.port = port
    link.baudrate = BAUD
    link.timeout = 0.5
    # Opening the port with DTR/RTS asserted resets the ESP32 and would drop every BLE link mid-test.
    link.dtr = False
    link.rts = False
    link.open()
    return link


def read_diag_lines(port, seconds):
    deadline = time.monotonic() + seconds
    lines = []
    with open_without_reset(port) as link:
        while time.monotonic() < deadline:
            text = link.readline().decode("utf-8", "replace").strip()
            if text.startswith(DIAG_PREFIX):
                lines.append(text)
    return lines


def parse_links(line):
    return {int(slot): parse_fields(body) for slot, body in LINK.findall(line)}


def parse_fields(body):
    if body.strip() == "-":
        return None
    return dict(pair.split("=", 1) for pair in body.split() if "=" in pair)


def link_by_role(line, role):
    return next((fields for fields in parse_links(line).values() if fields and fields.get("role") == role), None)


def is_streaming(fields):
    return fields is not None and fields.get("trusted") == "1" and fields.get("sub") == "1"


# Per-link counters wrap at 1 000 000 (plan 04 Task 6), so growth is measured modulo that.
def sent_grew(first, last, role):
    before, after = link_by_role(first, role), link_by_role(last, role)
    if not (is_streaming(before) and is_streaming(after)):
        return False
    return (int(after["sent"]) - int(before["sent"])) % SENT_MODULO > 0


def watch_drops_nothing(line):
    watch = link_by_role(line, "watch")
    return watch is not None and watch.get("dropped") == "0"


def watch_streams(lines):
    return len(lines) >= 2 and sent_grew(lines[0], lines[-1], "watch")


def two_links_stream(lines):
    if len(lines) < 2:
        return False
    first, last = lines[0], lines[-1]
    return sent_grew(first, last, "watch") and sent_grew(first, last, "phone") and watch_drops_nothing(last)


def phone_interval_ok(lines):
    phone = link_by_role(lines[-1], "phone") if lines else None
    return phone is not None and int(phone.get("itvl", "-1")) in PHONE_ITVL_UNITS


# A free slot means the belt advertises again (plan 04: advertising while connections < capacity).
def phone_gone(lines):
    if len(lines) < 2:
        return False
    last = lines[-1]
    free_slot = None in parse_links(last).values()
    return link_by_role(last, "phone") is None and free_slot and watch_streams(lines) and watch_drops_nothing(last)


def dual_link_firmware(lines):
    return any("link0[" in line for line in lines)


CHECKS = {
    "watch": watch_streams,
    "two-links": two_links_stream,
    "phone-interval": phone_interval_ok,
    "phone-gone": phone_gone,
    "dual-firmware": dual_link_firmware,
}


def ui_nodes(xml_text):
    return ElementTree.fromstring(xml_text).iter("node")


def label_of(node):
    return node.get("text") or node.get("content-desc") or ""


def center_of(node):
    left, top, right, bottom = map(int, BOUNDS.match(node.get("bounds")).groups())
    return (left + right) // 2, (top + bottom) // 2


def find_center(xml_text, pattern):
    match = next((node for node in ui_nodes(xml_text) if re.fullmatch(pattern, label_of(node))), None)
    return center_of(match) if match is not None else None


def edit_field_center(xml_text):
    match = next((node for node in ui_nodes(xml_text) if node.get("class") == EDIT_TEXT_CLASS), None)
    return center_of(match) if match is not None else None


def read_text(path):
    with open(path, encoding="utf-8") as handle:
        return handle.read()


def print_center(center):
    if center is None:
        return 1
    print(f"{center[0]} {center[1]}")
    return 0


def cmd_check(args):
    name, port, seconds = args
    lines = read_diag_lines(port, float(seconds))
    for line in lines[-2:]:
        print(line)
    return 0 if CHECKS[name](lines) else 1


def cmd_tap_target(args):
    xml_file, pattern = args
    return print_center(find_center(read_text(xml_file), pattern))


def cmd_has_text(args):
    xml_file, pattern = args
    return 0 if find_center(read_text(xml_file), pattern) else 1


def cmd_edit_field(args):
    return print_center(edit_field_center(read_text(args[0])))


SAMPLE_WATCH = ("diag up=10 link0[role=watch trusted=1 sub=1 mtu=255 itvl=36 lat=0 sup=400 sent=100 dropped=0] "
                "link1[-] bonds=1 disc=0 tx[fail=0 dropped=0 skipped=0]")
SAMPLE_BOTH_A = ("diag up=15 link0[role=watch trusted=1 sub=1 mtu=255 itvl=36 lat=0 sup=400 sent=160 dropped=0] "
                 "link1[role=phone trusted=1 sub=1 mtu=247 itvl=64 lat=0 sup=400 sent=90 dropped=0] bonds=2 disc=0")
SAMPLE_BOTH_B = ("diag up=20 link0[role=watch trusted=1 sub=1 mtu=255 itvl=36 lat=0 sup=400 sent=220 dropped=0] "
                 "link1[role=phone trusted=1 sub=1 mtu=247 itvl=64 lat=0 sup=400 sent=150 dropped=2] bonds=2 disc=0")
SAMPLE_GONE = ("diag up=25 link0[role=watch trusted=1 sub=1 mtu=255 itvl=36 lat=0 sup=400 sent=280 dropped=0] "
               "link1[-] bonds=2 disc=0")
SAMPLE_MVP = "diag up=10 link[trusted=1 sub=1 mtu=255] tx[sent=100]"
SAMPLE_UI = ('<hierarchy><node text="Radar" content-desc="" class="android.widget.TextView" bounds="[0,2000][270,2100]" />'
             '<node text="" content-desc="Compartir" class="android.view.View" bounds="[900,500][1000,600]" />'
             '<node text="" class="android.widget.EditText" bounds="[100,800][900,900]" /></hierarchy>')


def cmd_selftest(_args):
    assert watch_streams([SAMPLE_WATCH, SAMPLE_BOTH_A])
    assert two_links_stream([SAMPLE_BOTH_A, SAMPLE_BOTH_B])
    assert not two_links_stream([SAMPLE_WATCH, SAMPLE_BOTH_A])
    assert phone_interval_ok([SAMPLE_BOTH_B])
    assert phone_gone([SAMPLE_BOTH_B, SAMPLE_GONE])
    assert not phone_gone([SAMPLE_BOTH_A, SAMPLE_BOTH_B])
    assert dual_link_firmware([SAMPLE_WATCH]) and not dual_link_firmware([SAMPLE_MVP])
    assert find_center(SAMPLE_UI, "Radar") == (135, 2050)
    assert find_center(SAMPLE_UI, "Compartir") == (950, 550)
    assert find_center(SAMPLE_UI, "Rad") is None
    assert edit_field_center(SAMPLE_UI) == (500, 850)
    print("selftest ok")
    return 0


COMMANDS = {
    "check": cmd_check,
    "tap-target": cmd_tap_target,
    "has-text": cmd_has_text,
    "edit-field": cmd_edit_field,
    "selftest": cmd_selftest,
}


def main(argv):
    if not argv or argv[0] not in COMMANDS:
        print("usage: e2e_tools.py check|tap-target|has-text|edit-field|selftest ...", file=sys.stderr)
        return 2
    try:
        return COMMANDS[argv[0]](argv[1:])
    except Exception as error:  # a busy port, a bad dump or a failed assert is a failed probe, never a crash of the run
        print(f"e2e_tools: {error!r}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
