<div align="center">

# Blindside

**An open-source wrist radar for airsoft.**

Two 24 GHz radars on your belt. Contacts on your Galaxy Watch.<br>
A distinct buzz on your wrist when someone new moves into view.

**English** | [Español](README.es.md)

</div>

> [!NOTE]
> **Status: design phase.** This repository currently contains the v1 design and the research behind it. There is no firmware or watch app yet; those are the next milestones. First field target: a 5-hour game on 11 October 2026. Star or watch the repo to follow along.

---

## What it does

- **Two HLK-LD2450 mmWave radars** ride on the front of your belt, one over each front pocket, angled outward. Together they cover **about 180° in front of you, out to about 6 m**.
- **An ESP32 in a belt pouch** adds a timestamp to the raw radar frames and to the readings of two MPU6050 motion sensors (IMUs), one inside each radar box, then streams everything over Bluetooth LE.
- **A native Wear OS app on a Samsung Galaxy Watch 7** handles all the processing:
  - combines what the two radars see;
  - follows each contact over time;
  - compensates for your own turns and steps;
  - draws the contacts on a round radar display.
- **Newly confirmed contacts buzz your wrist** with a different rhythm for left, center or right. You don't have to look. Alerts are paced: at most one buzz per second, so contacts that show up together queue and buzz one after another (center first). There's also a per-sector pause and a cap of 10 per minute, so a crowd doesn't turn into one long buzz.
- No phone, no Wi-Fi and no cloud: the belt talks only to the watch you paired it with.

```
[box L: LD2450 + MPU6050] ─7 wires─┐
                                   ├─ ESP32 (belt pouch, "dumb" relay) ──BLE──▶ Galaxy Watch 7
[box R: LD2450 + MPU6050] ─7 wires─┘        USB power bank                     radar-core: fusion · tracking ·
                                                                                ego-motion · wrist pose → display + haptics
```

## What's different

DIY "heartbeat sensors" built on the same radar already exist; see [Prior art](#prior-art--credits). The best-documented one mounts a radar and a small screen on the rifle, and another is a fixed Raspberry Pi display. Blindside takes a different approach:

- **Belt sensors, wrist display.** The radars follow your hips, the most stable part of your body when you move. The display is where you already glance.
- **Two radars, fused.** Wider coverage, and a center zone where a contact seen by both radars earns higher confidence.
- **Motion compensation.** A radar you wear sees "ghosts" whenever you move. The IMUs in the radar boxes track your turns and step counting estimates your walking speed, so walls and trees can be told apart from people.
- **Built around how you hold an M4:**
  - The radar angles are biased toward your support side, where the muzzle usually points.
  - The app detects when you read the watch on the inside of your support wrist ("tactical" wear, palm-up grip) and rotates the display so up means where you're aiming. Setup is a 10-second zeroing against a teammate.
  - With a vertical foregrip the watch can't be read while aiming, so vibration does the talking.
- **Stealth first.**
  - The screen stays off by default and vibration is the main channel.
  - The belt emits no light during play: the status LED stays off, and the always-on power LEDs are removed or taped over during assembly.
  - The radar module's own Bluetooth is switched off, and outside a short pairing window that you open yourself, only your paired watch can connect to the belt.
- **Record and replay.** A match can be recorded raw and replayed on a PC to tune the filters against real data.

## Honest limits

- **It does not detect heartbeats.** It detects **moving** people with a 24 GHz Doppler radar (FMCW, the same kind used in presence sensors).
- **Range is about 6 m,** with 120° per radar.
- **No reliable through-wall detection.** Don't count on it to see through walls or cover. The signal does pass through a thin, dry plastic cover with no metal in it; that is how the enclosure works. We have not yet tested foliage, fabric (wet or dry) or rain. Wet or metal-coated materials in front of the radar are expected to degrade it.
- **People standing perfectly still fade out.** The app holds their last position for a few seconds.
- **Teammates show up too.** v1 can't tell friend from foe; that's on the roadmap.
- **Ghosts are still possible.** Worn on a moving body the radar sees clutter, and v1 fights it rather than eliminating it. It works best when you're still or advancing slowly.

## Hardware (v1)

| Part | Rough price (USD, varies by store) | Notes |
|---|---|---|
| ESP32-WROOM-32 DevKit (30-pin) | ~US$5–10 | NimBLE peripheral, 2 UARTs + 2 I2C buses |
| 2× HLK-LD2450 | ~US$6–15 each | 24 GHz, up to 3 targets, ±60°, ~6 m. The Ai-Thinker RD-03D uses the same frame format (untested). |
| 2× MPU6050 (GY-521) | ~US$1–3 each | One inside each radar box, rigid with its radar; no magnetometer needed |
| JST ZH 1.5 mm 4-pin cables | ~US$5–10 per kit | The LD2450 connector is not 2.54 mm |
| ESP32 screw-terminal board | ~US$5–10 | No soldering, and no Dupont connectors to wiggle loose |
| Two 7-wire cables, 50–80 cm | — | Pouch to radar boxes. One old Ethernet cable (8 wires) per box works, or two old USB cables (4 wires each) per box. |
| USB power bank | — | The belt draws about 300 mA at 5 V, so 10,000 mAh lasts well over 15 h |
| Samsung Galaxy Watch 7 | — | Wear OS 6 (API 36); other Wear OS watches untested |
| 3D-printed radar boxes | — | Hold the angles, stop BBs, and pass the 24 GHz signal through a solid-infill window (no metal in front of the radar). Aluminum foil or a piece of tin can between each radar and your body, insulated from the electronics, cuts down the radar's rear lobe, which would otherwise pick up your own movement. |

## Wiring

Everything runs at **3.3 V**: power the radars and the IMUs from the ESP32's **3V3** pin (not 5 V) and share a common **GND**. On the UART, the sensor's TX goes to the ESP32's RX and vice versa. All pins live in [firmware/include/blindside_config.h](firmware/include/blindside_config.h).

**Radars — 2× HLK-LD2450 (UART, 256000 baud, 3.3 V):**

| LD2450 pin | Radar A → ESP32 | Radar B → ESP32 |
|---|---|---|
| VCC (3.3 V) | 3V3 | 3V3 |
| GND | GND | GND |
| TX (OT1) → ESP32 RX | GPIO16 | GPIO26 |
| RX (RX1) ← ESP32 TX | GPIO17 | GPIO27 |

Radar B rides the GPIO matrix because UART1's default pins (9/10) belong to the SPI flash.

**IMUs — 2× MPU6050 / GY-521 (I2C, 100 kHz, address 0x68):** each IMU has its own I2C bus, so both keep the default address — no AD0 jumper, no conflict.

| GY-521 pin | IMU A → ESP32 | IMU B → ESP32 |
|---|---|---|
| VCC | 3V3 | 3V3 |
| GND | GND | GND |
| SDA | GPIO32 | GPIO21 |
| SCL | GPIO33 | GPIO22 |

- **Radar A + IMU A go in the left hip box, Radar B + IMU B in the right box.** Each IMU must be rigid with its own radar — the watch uses it to cancel your turns and steps.
- The LD2450 connector is JST ZH 1.5 mm, not 2.54 mm.
- On first boot the firmware takes each LD2450 off Bluetooth and sets it to 256000 baud. Confirm over USB serial that the `diag` line shows `radar 0: baud=256000` and `radar 1: baud=256000`, and that the IMUs read `imu0[ok=1 who=0x68 …]` (a clone reporting 0x70/0x71/0x98 is also fine). The full bench checklist is in [firmware/HARDWARE_CHECKLIST.md](firmware/HARDWARE_CHECKLIST.md).

## How it works (short version)

1. **The belt stays simple.** The ESP32 never interprets targets. Every 100 ms it bundles the raw LD2450 frames and the 50 Hz readings of both IMUs, each with its own timestamp, into one BLE notification, or several consecutive ones when the bundle doesn't fit.
2. **The watch does the thinking.** The processing lives in `radar-core`, a pure Kotlin module you can unit-test on a PC. For each bundle it:
   - decodes the radar frames (their unusual sign-bit format is handled explicitly);
   - drops readings from your own arms and rifle;
   - converts everything to hip coordinates, then compensates for your turns (IMU) and your steps;
   - keeps each contact as a track with a Kalman filter;
   - confirms a new track only after it shows up in 3 of 5 windows;
   - applies the ghost rules;
   - picks the frame the display uses from your wrist pose;
   - in Vista mode (screen always on), redraws the radar at 30 fps, predicting each track forward between bundles.
3. **Everything is replayable.** A recording file (`.bsrec`) stores the raw BLE data plus the watch's own sensors, so any field session can be replayed exactly to test changes.

The full design (in Spanish) is in [docs/superpowers/specs/2026-09-30-blindside-v1-design.md](docs/superpowers/specs/2026-09-30-blindside-v1-design.md).

## Roadmap

- **v1:**
  - belt node and watch app;
  - fused 180° radar with motion compensation;
  - haptic direction cues;
  - stealth mode and an "eliminated" mode;
  - calibration wizards;
  - match recording.
- **v1.5:**
  - vibration motors in the radar boxes, so the belt itself tells direction by location (needs a purchase);
  - auto-silence when you're aiming;
  - tap a contact to mark it as a friend;
  - a kneeling profile;
  - a quick shoulder switch mid-game;
  - a replay viewer and heatmap;
  - a sentry node that guards a doorway and alerts your wrist;
  - a classic "COD" mode with a snapshot every 4 seconds;
  - a tournament mode;
  - maybe a thermal sensor, if the recordings show it's needed.
- **v2:**
  - UWB friend-or-foe;
  - squad link over ESP-NOW;
  - 360° coverage;
  - a rifle-rail node;
  - a phone app and ATAK/CoT export;
  - spatial audio through an earpiece.
- **Long term, beyond airsoft:**
  - with the same hardware:
    - sentry/tripwire mode;
    - match statistics;
    - reaction drills;
    - "someone behind me" while walking;
    - gait analysis with the two hip IMUs;
    - Home Assistant presence;
    - accessibility aid (complements a cane);
  - with new parts:
    - a rear radar for motorcycles in RevScope;
    - a 60 GHz vital-signs sensor.
- **"Halo mode" (long term):** a 360° motion tracker with friendlies in another colour, at 15-25 m. It needs RFbeam K-LD7, V-LD3 or K-MD7, or TI IWRL6432 radars. All of them are ≤ 20 dBm and safe when pointed away from the body. See [the long-range radar report](docs/research/reports/Radares%20de%20largo%20alcance%20y%20salud.md) (Spanish).

## Repository layout

```
docs/
  superpowers/specs/     v1 design specification (Spanish)
  research/reports/      research report (Spanish)
  research/research_notes/  sourced notes: LD2450, biomechanics, Wear OS/BLE,
                            tracking algorithms, prior art & rules, UX ideas
firmware/                (coming) PlatformIO + NimBLE-Arduino
watch/                   (coming) radar-core (pure Kotlin) + wear-app (Wear OS)
```

## Fair play, legal and safety

- **Airsoft fields:** none of the rulebooks we found mention radar, but many fields ban thermal optics as a "wallhack", and the same argument applies here.
  - Ask your field before playing, declare the device at check-in, and make sure the other players know. Never use it to detect or follow people outside a game.
  - Blindside will ship an **"eliminated" mode** that turns off the radar alerts and the display until you respawn. A tournament mode is planned for v1.5.
- **Radio:** check your local rules before using a 24 GHz radar.
  - US: FCC §15.249.
  - EU: CEPT ERC 70-03 lists 24.05–24.25 GHz for radiodetermination and 24.00–24.25 GHz for non-specific short-range devices (100 mW e.i.r.p.; primary text not verified).
  - Colombia: ANE Resolution 105/2020 covers 24.05–24.25 GHz.
  - The LD2450 datasheet states a 24.00–24.25 GHz sweep, so in Colombia the lowest 50 MHz (24.00–24.05 GHz) sits in a grey zone (a 2024 addition, Res. ANE 153/2024, may cover it; unverified).
  - Don't modify the LD2450's firmware, antenna or power.
  - Publishing the code is fine; selling assembled kits may require homologation, CE marking or FCC certification.
- **Security:** the LD2450 ships with its own Bluetooth on, so anyone nearby could reconfigure it; Blindside's firmware turns it off. The belt accepts a single connection. It takes a new pairing only during a 60-second window, which opens when no watch is paired yet or when you hold the ESP32's BOOT button for 3 s within the first minute after it starts up (holding it while powering on enters flash mode instead). Pairing uses a random 6-digit key unique to each belt. Outside that window only the paired watch can connect. Inside it, another device can connect but can't read anything without the key, so pair away from other players. Holding BOOT for 10 s or more (also within the first minute) clears the pairing; after that, forget the belt in the watch's Bluetooth settings and pair again.
- **Not a safety device.** Don't rely on it to protect anyone.
- **Not affiliated** with Activision (Call of Duty), 20th Century Studios (Aliens) or Hi-Link.

## Prior art & credits

Blindside stands on the shoulders of these projects. Two have no license, and Rob Smith's code uses a non-commercial license that is not open source and is incompatible with the AGPL, so ideas are credited here and no code is copied:

- **ScienceShack: [Heartbeat Sensor from Modern Warfare 2](https://hackaday.io/project/205879-heartbeat-sensor-from-modern-warfare-2)** ([code](https://github.com/jrtage/MW2-Heartbeat-Sensor)). LD2450, XIAO RP2350 and an OLED on an M-LOK rail mount. Their note on the module's X-axis orientation saved us a headache.
- **[Bronsonalan/mw2-heartbeat-sensor](https://github.com/Bronsonalan/mw2-heartbeat-sensor).** Raspberry Pi and LD2450, with a phosphor-style display and a tracking layer.
- **Rob Smith: [a real working Aliens M314 motion tracker](https://hackaday.io/project/203817-a-real-working-alien-motion-tracker)** ([code](https://github.com/RobSmithDev/alienmotiontracker)). 60 GHz radar on a Raspberry Pi.

Technical references:
- Hi-Link's [Serial Communication Protocol V1.03](https://make.net.za/wp-content/datasheets/HLK%20LD2450%20Serial%20Communication%20Protocol%20v1.03.pdf), [Instruction Manual V1.00](https://www.tinytronics.nl/product_files/006000_HLK-LD2450-Instruction-Manual.pdf) and [User Guide](https://d.hlktech.net/download/HLK-LD2450/1/HLK-LD2450%20operation%20manual.doc..pdf). The User Guide mislabels its sign and Bluetooth on/off examples; the decoder follows the protocol document.
- The [ESPHome LD2450 component](https://esphome.io/components/sensor/ld2450/), used as a cross-check. It is GPLv3, and Blindside's decoder is written from the Hi-Link protocol document.
- x-io Technologies' [Fusion](https://github.com/xioTechnologies/Fusion), for its IMU rest-detection defaults.
- TI's [mmWave group tracker tuning guide](https://e2e.ti.com/cfs-file/__key/communityserver-discussions-components-files/1023/3D_5F00_people_5F00_counting_5F00_tracker_5F00_layer_5F00_tuning_5F00_guide-_2800_1_2900_.pdf), for tracking ideas.

The full sourced bibliography is in [docs/research/](docs/research/).

## Contributing

Ideas and issues are welcome, especially:
- enclosure and mounting designs;
- tests with other Wear OS watches or the RD-03D;
- field reports and raw `.bsrec` recordings, once the app exists.

## License

[GNU AGPL-3.0](LICENSE). If you ship a modified version, share the source.
