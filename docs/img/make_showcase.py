#!/usr/bin/env python3
"""Build Blindside README showcase images from privacy-safe raw captures.

Reads ONLY from docs/img/raw/:
  - phone-team-radar.png (phone "Equipo" tab with the team radar)
  - phone-alert.png (Blindside notifications)
  - watch-allies.png (Galaxy Watch screen with ally wedges)

Writes to docs/img/:
  - showcase.png / showcase-es.png (~1600x900)
  - team-radar.png (~720 px tall, rounded corners)
  - alert.png (~900 px wide, rounded corners)
  - watch-allies.png (~360 px circle on transparent background)

Requires: Python 3 + Pillow (pip install pillow).
Run: python docs/img/make_showcase.py
"""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

BASE = Path(__file__).resolve().parent
RAW = BASE / "raw"

BG = (14, 17, 17)          # #0E1111
ACCENT = (59, 227, 122)    # #3BE37A
CYAN = (77, 208, 225)      # #4DD0E1 ally cyan
YELLOW = (242, 184, 75)    # #F2B84B warn yellow
FRAME_FILL = (22, 29, 29)
TEXT_LIGHT = (230, 235, 235)

TAGLINE_EN = "Your team\u2019s radar \u2014 belt radar, team map, wrist alerts"
TAGLINE_ES = "El radar de tu equipo \u2014 radar de cintur\u00f3n, mapa del equipo, avisos en la mu\u00f1eca"


def load_font(bold, size):
    """Windows Segoe UI with fallback to Pillow's default bitmap font."""
    candidates = []
    if bold:
        candidates = ["C:/Windows/Fonts/segoeuib.ttf", "C:/Windows/Fonts/segoeui.ttf"]
    else:
        candidates = ["C:/Windows/Fonts/segoeui.ttf"]
    for path in candidates:
        try:
            return ImageFont.truetype(path, size)
        except Exception:
            continue
    return ImageFont.load_default()


def rounded_mask(size, radius):
    mask = Image.new("L", size, 0)
    d = ImageDraw.Draw(mask)
    d.rounded_rectangle([0, 0, size[0] - 1, size[1] - 1], radius=radius, fill=255)
    return mask


def rounded_image(img, radius):
    img = img.convert("RGBA")
    mask = rounded_mask(img.size, radius)
    img.putalpha(mask)
    return img


def circle_image(img, diameter):
    img = img.convert("RGBA").resize((diameter, diameter), Image.LANCZOS)
    mask = Image.new("L", (diameter, diameter), 0)
    d = ImageDraw.Draw(mask)
    d.ellipse([0, 0, diameter - 1, diameter - 1], fill=255)
    img.putalpha(mask)
    return img


def draw_radar_motif(base_rgba, center):
    """Subtle concentric radar rings + crosshair on a transparent overlay."""
    overlay = Image.new("RGBA", base_rgba.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(overlay)
    cx, cy = center
    radii = [170, 260, 350, 440, 530]
    for i, r in enumerate(radii):
        color = YELLOW + (16,) if i == 2 else CYAN + (20,)
        d.ellipse([cx - r, cy - r, cx + r, cy + r], outline=color, width=2)
    # faint crosshair through the center
    cross = CYAN + (12,)
    d.line([0, cy, base_rgba.size[0], cy], fill=cross, width=1)
    d.line([cx, 0, cx, base_rgba.size[1]], fill=cross, width=1)
    return Image.alpha_composite(base_rgba, overlay)


def wrap_text(draw, text, font, max_width):
    words = text.split()
    lines, line = [], ""
    for w in words:
        trial = (line + " " + w).strip()
        if draw.textlength(trial, font=font) <= max_width:
            line = trial
        else:
            if line:
                lines.append(line)
            line = w
    if line:
        lines.append(line)
    return lines


def make_showcase(tagline, out_name):
    W, H = 1600, 900
    base = Image.new("RGB", (W, H), BG).convert("RGBA")

    # Radar-ring motif centered on the future watch position (right side).
    watch_outer_d = 372
    watch_d = 352
    # Phone geometry first, so text/watch can sit to its right.
    phone_raw = Image.open(RAW / "phone-team-radar.png").convert("RGB")
    content_h = 748
    content_w = round(phone_raw.size[0] * content_h / phone_raw.size[1])
    pad = 14
    frame_w, frame_h = content_w + pad * 2, content_h + pad * 2
    frame_x, frame_y = 80, (H - frame_h) // 2

    text_x = frame_x + frame_w + 64
    text_right = W - 80
    watch_cx = (text_x + text_right) // 2
    watch_cy = 648
    base = draw_radar_motif(base, (watch_cx, watch_cy))

    # Phone in a rounded frame.
    phone_small = phone_raw.resize((content_w, content_h), Image.LANCZOS)
    phone_rounded = rounded_image(phone_small, 30)
    frame = Image.new("RGBA", (frame_w, frame_h), (0, 0, 0, 0))
    fd = ImageDraw.Draw(frame)
    fd.rounded_rectangle([0, 0, frame_w - 1, frame_h - 1], radius=42, fill=FRAME_FILL + (255,))
    frame.paste(phone_rounded, (pad, pad), phone_rounded)
    fd.rounded_rectangle([1, 1, frame_w - 2, frame_h - 2], radius=41, outline=ACCENT + (255,), width=2)
    base.paste(frame, (frame_x, frame_y), frame)

    # Watch clipped to a circle with a thin bezel ring.
    watch_raw = Image.open(RAW / "watch-allies.png").convert("RGB")
    watch_circ = circle_image(watch_raw, watch_d)
    bezel = Image.new("RGBA", (watch_outer_d, watch_outer_d), (0, 0, 0, 0))
    bd = ImageDraw.Draw(bezel)
    bd.ellipse([0, 0, watch_outer_d - 1, watch_outer_d - 1], fill=(26, 34, 34, 255))
    off = (watch_outer_d - watch_d) // 2
    bezel.paste(watch_circ, (off, off), watch_circ)
    bd.ellipse([1, 1, watch_outer_d - 2, watch_outer_d - 2], outline=CYAN + (255,), width=3)
    base.paste(bezel, (watch_cx - watch_outer_d // 2, watch_cy - watch_outer_d // 2), bezel)

    # Title + tagline.
    draw = ImageDraw.Draw(base)
    title_font = load_font(True, 108)
    tag_font = load_font(False, 36)
    title = "Blindside"
    title_y = 180
    draw.text((text_x, title_y), title, font=title_font, fill=ACCENT + (255,))
    # thin accent rule under the title
    try:
        tw = draw.textlength(title, font=title_font)
        rule_y = title_y + 138
        draw.line([text_x, rule_y, text_x + min(tw, 420), rule_y], fill=ACCENT + (255,), width=4)
    except Exception:
        rule_y = title_y + 138
    max_w = text_right - text_x
    lines = wrap_text(draw, tagline, tag_font, max_w)
    ty = rule_y + 24
    for line in lines:
        draw.text((text_x, ty), line, font=tag_font, fill=TEXT_LIGHT + (255,))
        try:
            bbox = draw.textbbox((0, 0), line, font=tag_font)
            ty += (bbox[3] - bbox[1]) + 10
        except Exception:
            ty += 44

    base.convert("RGB").save(BASE / out_name)
    print(f"wrote {out_name} ({W}x{H})")


def make_team_radar():
    src = Image.open(RAW / "phone-team-radar.png").convert("RGB")
    h = 720
    w = round(src.size[0] * h / src.size[1])
    small = src.resize((w, h), Image.LANCZOS)
    out = rounded_image(small, 28)
    out.save(BASE / "team-radar.png")
    print(f"wrote team-radar.png ({w}x{h})")


def make_alert():
    src = Image.open(RAW / "phone-alert.png").convert("RGB")
    w = 900
    h = round(src.size[1] * w / src.size[0])
    small = src.resize((w, h), Image.LANCZOS)
    out = rounded_image(small, 24)
    out.save(BASE / "alert.png")
    print(f"wrote alert.png ({w}x{h})")


def make_watch():
    src = Image.open(RAW / "watch-allies.png").convert("RGB")
    out = circle_image(src, 360)
    out.save(BASE / "watch-allies.png")
    print("wrote watch-allies.png (360x360, circle on transparent)")


def main():
    for name in ("phone-team-radar.png", "phone-alert.png", "watch-allies.png"):
        if not (RAW / name).exists():
            raise SystemExit(f"missing input: {RAW / name}")
    make_showcase(TAGLINE_EN, "showcase.png")
    make_showcase(TAGLINE_ES, "showcase-es.png")
    make_team_radar()
    make_alert()
    make_watch()


if __name__ == "__main__":
    main()
