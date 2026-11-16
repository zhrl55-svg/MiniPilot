#!/usr/bin/env python3
"""
Generate the MiniPilot "Convergence" app icons for iOS and Android.

Design
------
A continuous blue -> sky -> emerald -> lime -> gold -> orange -> amber
gradient ring (the "compass"), wrapped around a deep-blue disc holding a
green terminal prompt (`>_`) and an amber spark. The ring is a Pilot /
navigation metaphor, the prompt is the Agent that actually executes.

Platform conventions
--------------------
iOS
  * The 1024x1024 asset must be a full-bleed square with NO transparency
    and NO pre-rounded corners - iOS applies its own squircle mask.
  * Three variants ship: standard, dark (luminosity appearance), tinted.
  * The tinted variant must be grayscale and use the ALPHA channel to
    describe the shape, so the system can re-tint it. We therefore draw the
    silhouette in light gray on a transparent background (which also still
    reads correctly if a renderer keys off luminance).

Android
  * Adaptive icons are three layers: background (a colour), foreground
    (the artwork) and monochrome (Android 13+ Themed Icons).
  * The foreground canvas is 108dp. The launcher may mask it to the
    inscribed circle of the 72dp safe zone: centre (54,54), radius 33dp.
    The project XML additionally insets the foreground by 15%, which
    scales it by 0.7 about the centre - so to land the artwork's outer
    ring edge at radius 31dp in the final icon we must draw it at
    radius 31 / 0.7 = 44.3dp in the 108dp PNG.
  * Legacy (pre-API 26) launcher icons are separate square PNGs.

Usage
-----
    python3 scripts/gen_app_icons.py [--out-root .] [--dry-run]
"""

import argparse
import math
import os
import re
import shutil

from PIL import Image, ImageDraw

# ---------------------------------------------------------------------------
# Design tokens (SVG-ish user units on a 680x680 canvas)
# ---------------------------------------------------------------------------

CANVAS = 680
CX = CY = 340.0

BG_DARK = (17, 24, 39)          # #111827  brand ground
BG_DEEP = (11, 18, 32)          # #0B1220  deeper ground (night)
BG_LIGHT = (241, 245, 249)      # #F1F5F9  light ground (Android light variants)

RING_R = 240.0                  # gradient ring centre radius
RING_W = 22.0                   # gradient ring stroke width
RING_OUTER = RING_R + RING_W / 2 # 251 - outermost painted pixel
RING_INNER = RING_R - RING_W / 2 # 229

CORE_R = 170.0
CORE_FILL = (30, 58, 95)        # #1e3a5f
CORE_STROKE = (59, 130, 246)    # #3b82f6
PROMPT = (16, 185, 129)         # #10b981
SPARK = (250, 204, 21)          # #facc15
TINT = (229, 231, 235)          # #E5E7EB  tinted-icon silhouette

# Continuous gradient, sampled clockwise from 12 o'clock.
GRADIENT_STOPS = [
    (0.00, (59, 130, 246)),     # #3b82f6
    (0.20, (14, 165, 233)),     # #0ea5e9
    (0.40, (16, 185, 129)),     # #10b981
    (0.55, (132, 204, 22)),     # #84cc16
    (0.70, (234, 179, 8)),      # #eab308
    (0.85, (245, 158, 11)),     # #f59e0b
    (1.00, (250, 204, 21)),     # #facc15
]

# --- Android adaptive icon geometry (108dp canvas) -------------------------
ADAPTIVE_SIZE = 108
ADAPTIVE_SAFE_R = 33.0          # inscribed circle of the 72dp safe zone
FOREGROUND_INSET = 0.15         # must match the inset in the XML resources
FINAL_ART_R = 31.0              # desired outer ring radius after the inset

# --- iOS geometry ----------------------------------------------------------
IOS_FULL_BLEED = 0.66           # outer ring radius as a fraction of half-size

# --- Android legacy geometry ----------------------------------------------
LEGACY_ART_FRACTION = 0.72      # outer ring radius as a fraction of half-size


# ---------------------------------------------------------------------------
# Colour helpers
# ---------------------------------------------------------------------------

def lerp_color(t):
    """Sample the gradient at position t in [0, 1]."""
    if t <= 0:
        return GRADIENT_STOPS[0][1]
    if t >= 1:
        return GRADIENT_STOPS[-1][1]
    for i in range(len(GRADIENT_STOPS) - 1):
        p1, c1 = GRADIENT_STOPS[i]
        p2, c2 = GRADIENT_STOPS[i + 1]
        if p1 <= t <= p2:
            ratio = (t - p1) / (p2 - p1) if p2 > p1 else 0.0
            return tuple(int(round(c1[j] + (c2[j] - c1[j]) * ratio)) for j in range(3))
    return GRADIENT_STOPS[-1][1]


def hex_of(rgb):
    return "#{:02X}{:02X}{:02X}".format(*rgb)


# ---------------------------------------------------------------------------
# Core artwork
# ---------------------------------------------------------------------------

def _gradient_ring(size, cx, cy, r_ring, w_ring, samples=None):
    """Paint a continuous gradient annulus onto a transparent RGBA image."""
    layer = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    ld = ImageDraw.Draw(layer)

    if samples is None:
        samples = max(360, int(r_ring * 2.5))
    step = w_ring / 2.0 + 1.0

    for i in range(samples):
        angle = -90.0 + i * (360.0 / samples)
        rad = math.radians(angle)
        x = cx + r_ring * math.cos(rad)
        y = cy + r_ring * math.sin(rad)
        ld.ellipse([x - step, y - step, x + step, y + step],
                   fill=lerp_color(i / samples) + (255,))

    mask = Image.new("L", (size, size), 0)
    md = ImageDraw.Draw(mask)
    md.ellipse([cx - r_ring - step, cy - r_ring - step,
                cx + r_ring + step, cy + r_ring + step], fill=255)
    md.ellipse([cx - r_ring + step, cy - r_ring + step,
                cx + r_ring - step, cy + r_ring - step], fill=0)

    layer.putalpha(mask)
    return layer


def _draw_prompt_and_spark(draw, cx, cy, unit, prompt_color, spark_color):
    """
    Draw the terminal prompt and spark.

    `unit` is pixels per SVG unit.
    """
    def p(v):
        return v * unit

    pw = max(1, int(round(30 * unit)))

    chevron = [
        (cx + p(-90), cy + p(-50)),
        (cx, cy),
        (cx + p(-90), cy + p(50)),
    ]
    draw.line(chevron, fill=prompt_color + (255,), width=pw, joint="curve")

    draw.line([
        (cx + p(20), cy + p(50)),
        (cx + p(80), cy + p(50)),
    ], fill=prompt_color + (255,), width=pw)

    spark = [
        (cx + p(115), cy + p(-25)),
        (cx + p(140), cy),
        (cx + p(115), cy + p(25)),
        (cx + p(90), cy),
    ]
    draw.polygon(spark, fill=spark_color + (255,))


def render_artwork(size, ground=None, art_scale=1.0, canvas=CANVAS,
                   centre=None, rounded_ground=False, core_fill=None):
    """
    Render the icon at `size` px.

    ground       : RGB background colour, or None for transparency.
    art_scale    : multiplier applied to the artwork radii.
    canvas       : the unit coordinate space the artwork is defined in.
    centre       : (x, y) in canvas units; defaults to the canvas centre.
    rounded_ground : draw the background as a rounded square (legacy icons).
    core_fill    : override for the inner disc colour (dark variants).
    """
    unit = size / canvas
    centre = centre or (CX, CY)
    core_fill = core_fill or CORE_FILL

    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)

    if ground is not None:
        if rounded_ground:
            # A squircle-ish ground: inset 40..640 of the 680 canvas with a
            # 120-unit corner radius, matching the design sheet.
            inset = 40 * unit
            radius = 120 * unit
            draw.rounded_rectangle(
                [inset, inset, size - inset, size - inset],
                radius=radius, fill=ground + (255,))
        else:
            draw.rectangle([0, 0, size, size], fill=ground + (255,))

    cx = centre[0] * unit
    cy = centre[1] * unit

    r_ring = RING_R * art_scale * unit
    w_ring = max(1, int(round(RING_W * art_scale * unit)))

    ring = _gradient_ring(size, cx, cy, r_ring, w_ring)
    img.paste(ring, (0, 0), ring)

    draw = ImageDraw.Draw(img)

    r_core = CORE_R * art_scale * unit
    draw.ellipse([cx - r_core, cy - r_core, cx + r_core, cy + r_core],
                 fill=core_fill + (255,))
    draw.ellipse([cx - r_core, cy - r_core, cx + r_core, cy + r_core],
                 outline=CORE_STROKE + (255,),
                 width=max(1, int(round(6 * art_scale * unit))))

    _draw_prompt_and_spark(draw, cx, cy, art_scale * unit, PROMPT, SPARK)

    return img


def render_ios(size, ground, art_scale=None, core_fill=None):
    """iOS app icon: full-bleed opaque square, no pre-rounded corners."""
    if art_scale is None:
        art_scale = (IOS_FULL_BLEED * (CANVAS / 2.0)) / RING_OUTER
    return render_artwork(size, ground=ground, art_scale=art_scale,
                          canvas=CANVAS, centre=(CX, CY), core_fill=core_fill)


def render_ios_tinted(size):
    """
    iOS tinted variant.

    The system re-tints this using the alpha channel, so the silhouette is
    opaque and the background is transparent. The silhouette is the ring
    annulus plus the inner disc, with the prompt knocked out so the glyph
    stays legible once flattened to a single colour.
    """
    unit = size / CANVAS
    art_scale = (IOS_FULL_BLEED * (CANVAS / 2.0)) / RING_OUTER

    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)

    cx = CX * unit
    cy = CY * unit

    r_out = RING_OUTER * art_scale * unit
    r_in = RING_INNER * art_scale * unit
    draw.ellipse([cx - r_out, cy - r_out, cx + r_out, cy + r_out], fill=TINT + (255,))
    draw.ellipse([cx - r_in, cy - r_in, cx + r_in, cy + r_in], fill=(0, 0, 0, 0))

    r_core = CORE_R * art_scale * unit
    draw.ellipse([cx - r_core, cy - r_core, cx + r_core, cy + r_core],
                 fill=TINT + (255,))

    # Knock the prompt + spark out of the disc.
    draw = ImageDraw.Draw(img)
    unit_s = art_scale * unit
    pw = int(round(30 * unit_s))
    draw.line([
        (cx - 90 * unit_s, cy - 50 * unit_s),
        (cx, cy),
        (cx - 90 * unit_s, cy + 50 * unit_s),
    ], fill=(0, 0, 0, 0), width=pw, joint="curve")
    draw.line([
        (cx + 20 * unit_s, cy + 50 * unit_s),
        (cx + 80 * unit_s, cy + 50 * unit_s),
    ], fill=(0, 0, 0, 0), width=pw)
    draw.polygon([
        (cx + 115 * unit_s, cy - 25 * unit_s),
        (cx + 140 * unit_s, cy),
        (cx + 115 * unit_s, cy + 25 * unit_s),
        (cx + 90 * unit_s, cy),
    ], fill=(0, 0, 0, 0))

    return img


def render_android_foreground(size, core_fill=None):
    """
    Android adaptive foreground layer (transparent outside the artwork).

    The XML insets this drawable by 15%, scaling it by 0.7 about the
    centre, so the outer ring edge is drawn at radius 31 / 0.7 = 44.3dp in
    the 108dp canvas. That lands it just inside the 33dp safe circle.

    `art_scale` maps the 680-unit design canvas onto the 108dp canvas, so
    the artwork radii are expressed in dp directly: art_scale * RING_OUTER
    must equal the target radius in dp.
    """
    target_png_r = FINAL_ART_R / (1.0 - 2.0 * FOREGROUND_INSET)
    art_scale = target_png_r / RING_OUTER

    return render_artwork(
        size,
        ground=None,
        art_scale=art_scale,
        canvas=ADAPTIVE_SIZE,
        centre=(ADAPTIVE_SIZE / 2.0, ADAPTIVE_SIZE / 2.0),
        core_fill=core_fill,
    )


def render_android_legacy(size, ground=None):
    """Legacy (pre-API 26) square launcher icon, self-contained."""
    art_scale = (LEGACY_ART_FRACTION * (CANVAS / 2.0)) / RING_OUTER
    return render_artwork(size, ground=ground or BG_DARK, art_scale=art_scale,
                          canvas=CANVAS, centre=(CX, CY))


# ---------------------------------------------------------------------------
# Writers
# ---------------------------------------------------------------------------

ANDROID_DENSITIES = {
    "mdpi": (108, 48),
    "hdpi": (162, 72),
    "xhdpi": (216, 96),
    "xxhdpi": (324, 144),
    "xxxhdpi": (432, 192),
}


def write_ios(root, dry=False):
    out = os.path.join(root, "src/ios/Assets.xcassets/AppIcon.appiconset")
    written = []

    # The standard and dark variants must be fully opaque AND carry no alpha
    # channel - App Store validation rejects 1024px assets that have one.
    standard = render_ios(1024, BG_DARK).convert("RGB")
    dark = render_ios(1024, BG_DEEP, core_fill=(20, 43, 74)).convert("RGB")
    tinted = render_ios_tinted(1024)

    jobs = [
        ("Icon-1024.png", standard),
        ("Icon-1024-Dark.png", dark),
        ("Icon-1024-Tinted.png", tinted),
    ]
    for name, img in jobs:
        path = os.path.join(out, name)
        if not dry:
            os.makedirs(out, exist_ok=True)
            img.save(path)
        written.append(path)
    return written


def write_android(root, dry=False):
    res = os.path.join(root, "src/android/app/src/main/res")
    written = []

    for dens, (fg_size, legacy_size) in ANDROID_DENSITIES.items():
        d = os.path.join(res, "mipmap-" + dens)
        fg = render_android_foreground(fg_size)
        fg_dark = render_android_foreground(fg_size, core_fill=(20, 43, 74))
        legacy = render_android_legacy(legacy_size)

        jobs = [
            (d, "ic_launcher_foreground.png", fg),
            (d, "ic_launcher_foreground_light.png", fg),
            (d, "ic_launcher_foreground_dark.png", fg_dark),
            (d, "ic_launcher.png", legacy),
        ]

        night = os.path.join(res, "mipmap-night-" + dens)
        if os.path.isdir(night):
            jobs.append((night, "ic_launcher_foreground.png", fg_dark))
            jobs.append((night, "ic_launcher.png",
                         render_android_legacy(legacy_size, ground=BG_DEEP)))

        for folder, name, img in jobs:
            path = os.path.join(folder, name)
            if not dry:
                os.makedirs(folder, exist_ok=True)
                img.save(path)
            written.append(path)

    return written


MONOCHROME_XML = '''<?xml version="1.0" encoding="utf-8"?>
<!--
  T-android-themed-icons: monochrome counterpart to the "Convergence" app icon
  (gradient compass ring around a terminal prompt). Required for Android 13+
  Themed Icons (Settings -> Wallpaper & style -> Themed icons). The system
  tints this drawable with the active monochromatic palette at runtime, so the
  artwork is provided in flat white on transparency.

  Canvas is the standard adaptive-icon 108dp x 108dp. The visible safe zone is
  the inscribed circle centred at (54,54) with radius 33, so the glyph below
  is sized to stay inside it under every launcher mask (circle / squircle /
  rounded square). The prompt chevron's round caps are the outermost marks:
  their farthest point is 27.2dp from the centre plus a 5dp half-stroke, i.e.
  ~32.2dp, which leaves a ~0.8dp margin inside the safe circle.
-->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path
        android:strokeColor="#FFFFFFFF"
        android:strokeWidth="10"
        android:strokeLineCap="round"
        android:strokeLineJoin="round"
        android:fillColor="#00000000"
        android:pathData="M 32,38 L 46,54 L 32,70" />
    <path
        android:strokeColor="#FFFFFFFF"
        android:strokeWidth="10"
        android:strokeLineCap="round"
        android:fillColor="#00000000"
        android:pathData="M 50,70 L 66,70" />
    <path
        android:fillColor="#FFFFFFFF"
        android:pathData="M 72,46 L 79,54 L 72,62 L 65,54 Z" />
</vector>
'''


def write_android_monochrome(root, dry=False):
    path = os.path.join(root, "src/android/app/src/main/res/drawable/ic_launcher_monochrome.xml")
    if not dry:
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8") as f:
            f.write(MONOCHROME_XML)
    return [path]


def write_android_backgrounds(root, dry=False):
    """
    Adaptive-icon background colours.

    The artwork itself is theme-neutral (the ring and disc carry all the
    colour), so the background is just the ground the artwork sits on:
    light in the light configuration, dark in the dark one.
    """
    res = os.path.join(root, "src/android/app/src/main/res")
    written = []

    files = {
        os.path.join(res, "values/ic_launcher_background.xml"):
            '<?xml version="1.0" encoding="utf-8"?>\n'
            '<resources>\n'
            '    <color name="ic_launcher_background">{}</color>\n'
            '</resources>\n'.format(hex_of(BG_LIGHT)),
        os.path.join(res, "values-night/ic_launcher_background.xml"):
            '<?xml version="1.0" encoding="utf-8"?>\n'
            '<resources>\n'
            '    <color name="ic_launcher_background">{}</color>\n'
            '</resources>\n'.format(hex_of(BG_DARK)),
    }

    for path, content in files.items():
        if not dry:
            os.makedirs(os.path.dirname(path), exist_ok=True)
            with open(path, "w", encoding="utf-8") as f:
                f.write(content)
        written.append(path)

    alt = os.path.join(res, "values/ic_launcher_alt_colors.xml")
    if os.path.exists(alt):
        if not dry:
            with open(alt, "r", encoding="utf-8") as f:
                content = f.read()
            content = re.sub(
                r'(<color name="ic_launcher_bg_force_light">)[^<]*(</color>)',
                r'\g<1>' + hex_of(BG_LIGHT) + r'\g<2>', content)
            content = re.sub(
                r'(<color name="ic_launcher_bg_force_dark">)[^<]*(</color>)',
                r'\g<1>' + hex_of(BG_DARK) + r'\g<2>', content)
            with open(alt, "w", encoding="utf-8") as f:
                f.write(content)
        written.append(alt)

    return written


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out-root", default=".")
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    root = os.path.abspath(args.out_root)

    written = []
    written += write_ios(root, args.dry_run)
    written += write_android(root, args.dry_run)
    written += write_android_monochrome(root, args.dry_run)
    written += write_android_backgrounds(root, args.dry_run)

    print("{} files {}:".format(len(written), "planned" if args.dry_run else "written"))
    for w in written:
        print("  " + os.path.relpath(w, root).replace("\\", "/"))


if __name__ == "__main__":
    main()
