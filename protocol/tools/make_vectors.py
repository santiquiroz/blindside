# Single source of truth for both ends of the BLE link: writes the Kotlin and C++ test vectors.
import json
import pathlib
import struct

ROOT = pathlib.Path(__file__).resolve().parents[2]

FRAME_HEADER = bytes([0xAA, 0xFF, 0x03, 0x00])
FRAME_TAIL = bytes([0x55, 0xCC])
CMD_HEADER = bytes([0xFD, 0xFC, 0xFB, 0xFA])
CMD_TAIL = bytes([0x04, 0x03, 0x02, 0x01])

TLV_RADAR = 0x01
TLV_IMU = 0x02
TLV_STATUS = 0x03
TLV_LINK = 0x04


# The LD2450 uses sign-magnitude, not two's complement: bit 15 set means positive.
def sign_magnitude(value: int) -> int:
    magnitude = abs(value)
    if magnitude > 0x7FFF:
        raise ValueError(f"magnitude out of range: {value}")
    return (0x8000 | magnitude) if value > 0 else magnitude


def target_bytes(x_mm: int, y_mm: int, speed_cms: int, resolution_mm: int) -> bytes:
    return struct.pack(
        "<HHHH",
        sign_magnitude(x_mm),
        sign_magnitude(y_mm),
        sign_magnitude(speed_cms),
        resolution_mm,
    )


EMPTY_TARGET = bytes(8)


def targets_block(targets) -> bytes:
    raw = b"".join(target_bytes(*t) for t in targets)
    return raw + EMPTY_TARGET * (3 - len(targets))


def ld2450_frame(targets) -> bytes:
    return FRAME_HEADER + targets_block(targets) + FRAME_TAIL


def command(word: int, value: bytes = b"") -> bytes:
    payload = struct.pack("<H", word) + value
    return CMD_HEADER + struct.pack("<H", len(payload)) + payload + CMD_TAIL


def header(flags: int, seq: int, t_ms: int) -> bytes:
    return struct.pack("<BBHI", 1, flags, seq & 0xFFFF, t_ms & 0xFFFFFFFF)


def tlv(kind: int, payload: bytes) -> bytes:
    return struct.pack("<BB", kind, len(payload)) + payload


def radar_section(radar_id: int, t_ms: int, targets) -> bytes:
    return tlv(TLV_RADAR, struct.pack("<BI", radar_id, t_ms) + targets_block(targets))


def imu_section(imu_id: int, t_first_ms: int, samples, gyro_sums) -> bytes:
    body = struct.pack("<BIB", imu_id, t_first_ms, len(samples))
    for sample in samples:
        body += struct.pack("<6h", *sample)
    body += struct.pack("<3I", *[s & 0xFFFFFFFF for s in gyro_sums])
    return tlv(TLV_IMU, body)


def status_section(entries) -> bytes:
    body = b"".join(struct.pack("<BHBB", *e) for e in entries)
    return tlv(TLV_STATUS, body)


def link_section(interval_units: int, latency: int, supervision_units: int) -> bytes:
    return tlv(TLV_LINK, struct.pack("<HHH", interval_units, latency, supervision_units))


OFFICIAL_TARGET = (-782, 1713, -16, 320)
RIGHT_TARGET = (500, 3000, 25, 360)
IMU_SAMPLES_A = [(0, 0, 4096, 10, -5, 3), (1, -1, 4095, 11, -5, 3), (2, -2, 4094, 12, -4, 2),
                 (-1, 1, 4097, 9, -6, 4), (0, 0, 4096, 10, -5, 3)]
IMU_SAMPLES_B = [(10, 20, 4090, -3, 0, 655), (11, 21, 4091, -3, 1, 656), (12, 22, 4092, -2, 1, 657),
                 (13, 23, 4093, -2, 0, 658), (14, 24, 4094, -1, 0, 659)]


def build_vectors():
    vectors = {}

    vectors["sign_magnitude"] = [
        {"raw": 0x0000, "value": 0},
        {"raw": 0x030E, "value": -782},
        {"raw": 0x86B1, "value": 1713},
        {"raw": 0x0010, "value": -16},
        {"raw": 0x8010, "value": 16},
        {"raw": 0x8000, "value": 0},
        {"raw": 0xFFFF, "value": 32767},
        {"raw": 0x7FFF, "value": -32767},
    ]

    official = ld2450_frame([OFFICIAL_TARGET])
    vectors["ld2450_official_frame"] = {
        "hex": official.hex(),
        "targets": [{"x_mm": -782, "y_mm": 1713, "speed_cms": -16, "resolution_mm": 320}],
    }

    vectors["ld2450_commands"] = {
        "enable_config": command(0x00FF, struct.pack("<H", 0x0001)).hex(),
        "end_config": command(0x00FE).hex(),
        "multi_target": command(0x0090).hex(),
        "read_firmware": command(0x00A0).hex(),
        "set_baud_256000": command(0x00A1, struct.pack("<H", 0x0007)).hex(),
        "restart": command(0x00A3).hex(),
        "bluetooth_off": command(0x00A4, struct.pack("<H", 0x0000)).hex(),
    }

    one_radar = header(0x0F, 1, 1000) + radar_section(0, 995, [OFFICIAL_TARGET])
    vectors["bundle_one_radar"] = {
        "hex": one_radar.hex(),
        "expected": {
            "version": 1, "flags": 0x0F, "seq": 1, "t_ms": 1000, "truncated": False,
            "radar_frames": [{"radar_id": 0, "t_ms": 995, "targets": [
                {"x_mm": -782, "y_mm": 1713, "speed_cms": -16, "resolution_mm": 320}]}],
            "imu_batches": [], "statuses": [],
        },
    }

    sums_a = (100, -6, 7)
    sums_b = (-20000, 3, 3_000_000_000)
    # Fill order (spec §4.2): IMU, STATUS and LINK first, then RADAR sorted by t_ms.
    typical = (
        header(0x0F, 65535, 123456)
        + imu_section(0, 123380, IMU_SAMPLES_A, sums_a)
        + imu_section(1, 123380, IMU_SAMPLES_B, sums_b)
        + status_section([(0, 2, 0, 7), (1, 0, 1, 7)])
        + radar_section(0, 123400, [OFFICIAL_TARGET])
        + radar_section(1, 123410, [RIGHT_TARGET])
    )
    assert len(typical) == 242, len(typical)
    vectors["bundle_typical"] = {
        "hex": typical.hex(),
        "size": len(typical),
        "expected": {
            "version": 1, "flags": 0x0F, "seq": 65535, "t_ms": 123456, "truncated": False,
            "radar_frames": [
                {"radar_id": 0, "t_ms": 123400, "targets": [
                    {"x_mm": -782, "y_mm": 1713, "speed_cms": -16, "resolution_mm": 320}]},
                {"radar_id": 1, "t_ms": 123410, "targets": [
                    {"x_mm": 500, "y_mm": 3000, "speed_cms": 25, "resolution_mm": 360}]},
            ],
            "imu_batches": [
                {"imu_id": 0, "t_first_ms": 123380, "samples": [list(s) for s in IMU_SAMPLES_A],
                 "gyro_sums_u32": [s & 0xFFFFFFFF for s in sums_a]},
                {"imu_id": 1, "t_first_ms": 123380, "samples": [list(s) for s in IMU_SAMPLES_B],
                 "gyro_sums_u32": [s & 0xFFFFFFFF for s in sums_b]},
            ],
            "statuses": [
                {"radar_id": 0, "bad_frames": 2, "restarts": 0, "baud_index": 7},
                {"radar_id": 1, "bad_frames": 0, "restarts": 1, "baud_index": 7},
            ],
        },
    }

    with_link = header(0x0F, 9, 7000) + link_section(36, 0, 500) + radar_section(0, 6990, [OFFICIAL_TARGET])
    vectors["bundle_with_link"] = {
        "hex": with_link.hex(),
        "size": len(with_link),
        "expected": {
            "version": 1, "flags": 0x0F, "seq": 9, "t_ms": 7000, "truncated": False,
            "link": {"interval_units": 36, "latency": 0, "supervision_units": 500},
            "radar_frames": [{"radar_id": 0, "t_ms": 6990, "targets": [
                {"x_mm": -782, "y_mm": 1713, "speed_cms": -16, "resolution_mm": 320}]}],
            "imu_batches": [], "statuses": [],
        },
    }

    unknown = header(0x03, 7, 5000) + tlv(0x7E, bytes([1, 2, 3])) + radar_section(1, 4990, [RIGHT_TARGET])
    vectors["bundle_unknown_tlv"] = {
        "hex": unknown.hex(),
        "expected_radar_ids": [1],
    }

    full_radar = radar_section(0, 4990, [OFFICIAL_TARGET])
    truncated = header(0x01, 8, 5100) + full_radar[:12]
    vectors["bundle_truncated"] = {
        "hex": truncated.hex(),
        "expected": {"truncated": True, "radar_frames": 0},
    }

    return vectors


def c_array(name: str, data: bytes) -> str:
    body = ", ".join(f"0x{b:02X}" for b in data)
    return f"static const uint8_t {name}[] = {{{body}}};\nstatic const size_t {name}_LEN = {len(data)};\n"


def write_c_header(vectors) -> str:
    lines = [
        "// Generated by protocol/tools/make_vectors.py. Do not edit by hand.",
        "#pragma once",
        "#include <stddef.h>",
        "#include <stdint.h>",
        "",
        c_array("VEC_LD2450_OFFICIAL_FRAME", bytes.fromhex(vectors["ld2450_official_frame"]["hex"])),
    ]
    for key, hex_value in vectors["ld2450_commands"].items():
        lines.append(c_array(f"VEC_CMD_{key.upper()}", bytes.fromhex(hex_value)))
    for key in ["bundle_one_radar", "bundle_typical", "bundle_with_link", "bundle_unknown_tlv", "bundle_truncated"]:
        lines.append(c_array(f"VEC_{key.upper()}", bytes.fromhex(vectors[key]["hex"])))
    return "\n".join(lines)


def main():
    vectors = build_vectors()
    json_path = ROOT / "protocol" / "vectors" / "vectors.json"
    json_path.parent.mkdir(parents=True, exist_ok=True)
    json_path.write_text(json.dumps(vectors, indent=2) + "\n", encoding="utf-8", newline="\n")
    header_path = ROOT / "firmware" / "test" / "vectors.h"
    header_path.parent.mkdir(parents=True, exist_ok=True)
    header_path.write_text(write_c_header(vectors) + "\n", encoding="utf-8", newline="\n")
    print(f"wrote {json_path.relative_to(ROOT)} and {header_path.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
