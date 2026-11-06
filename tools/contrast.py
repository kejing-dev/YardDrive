#!/usr/bin/env python3
"""
D13 contrast table, the build.md pre-QA gate: every text colour x background pair the panels and 3D labels draw.

* Fixed colours (the design palette roles interaction / passable / alert / error, the ink #111418, the brand colours of the
  3D boards and tags) are computed exactly with the WCAG 2.x relative-luminance formula.
* The SpatialUI vibrant roles (labelPrimary, fillSecondary, fillTertiary, the Regular / Thick glass) have no fixed sRGB
  value: the app's `PALETTE` log line prints them as #FF000000 because the glass vibrancy draws them. They are measured on
  emulator screenshots instead: in a box around one piece of text, the background is the median colour of the pixels in
  the background side of the median, the text colour is the median of the stroke cores (stroke pixels = past the midpoint
  between the background and the most extreme pixel; cores = the 10 % of them nearest the extreme, so anti-aliased edges
  do not count and the result does not depend on how much empty background the box holds).

Usage: python3 tools/contrast.py SHOT_DIR [--md OUT.md]   exit 1 when any pair is below its threshold.
Needs Pillow (python3 without -I picks up the user site-packages).
"""
import sys

from PIL import Image


def lin(c):
    c = c / 255.0
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4


def lum(rgb):
    r, g, b = rgb[:3]
    return 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b)


def ratio(a, b):
    la, lb = lum(a), lum(b)
    hi, lo = max(la, lb), min(la, lb)
    return (hi + 0.05) / (lo + 0.05)


def hexrgb(h):
    h = h.lstrip("#")
    if len(h) == 8:  # AARRGGBB
        h = h[2:]
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


def over(fg_argb, bg):
    """Composite an #AARRGGBB colour over an opaque rgb."""
    h = fg_argb.lstrip("#")
    a = int(h[0:2], 16) / 255.0
    f = hexrgb(h)
    return tuple(round(a * f[i] + (1 - a) * bg[i]) for i in range(3))


def hx(rgb):
    return "#%02X%02X%02X" % tuple(rgb)


INK = hexrgb("#111418")
WHITE = (255, 255, 255)
ASPHALT = hexrgb("#4A4D52")
SKY = hexrgb("#BFD9EE")

# (pair, where it is used, fg rgb, bg rgb, threshold): Yard Drive state colours are FILLS with ink on them (Tag / Badge /
# Key kinds), never coloured strokes on the glass (D13).
FIXED = [
    ("ink #111418 / interaction fill #5A9CFF", "primary keys (I'm seated / Start / Go again / Continue / One step / Go), selected segment items, ON keys, Next chip, card icon discs", INK, hexrgb("#5A9CFF"), 4.5),
    ("ink / left #7FB2FF", "Full L key at full lock, HUB / MRD left turns tag, P2 cue Full lock left", INK, hexrgb("#7FB2FF"), 4.5),
    ("ink / right #FFA14A", "Full R key at full lock, HUB / MRD right turns tag, P2 cue Full lock right, replay note", INK, hexrgb("#FFA14A"), 4.5),
    ("ink / cue #5BD6C8", "P2 coach cue (look / straighten / stop), library look line when you look there, tour blocks lit", INK, hexrgb("#5BD6C8"), 4.5),
    ("ink / pass #7FE0A8", "result icon disc + segment chips ✓, lesson Done chip, P2 segment done / That's the set, hit values ≤ 15 cm", INK, hexrgb("#7FE0A8"), 4.5),
    ("ink / fail #FF8F7A", "result icon disc (failed), P2 line touched / not this time / lost tracking, hit values > 30 cm", INK, hexrgb("#FF8F7A"), 4.5),
    ("ink / warn #E0A030", "wrong gear letter + Shift to R first, corner gap < 0.10 m (P2 / MRD), hit values 15–30 cm", INK, hexrgb("#E0A030"), 4.5),
    ("ink / card #E6E8EA", "S00 and S20 solid card (D33)", INK, hexrgb("#E6E8EA"), 4.5),
    ("ink / danger #FF8478", "Quit app key, quit / clear confirm rows (question text + row)", INK, hexrgb("#FF8478"), 4.5),
    ("white / sign blue #2B5FB3", "3D sign 考场 on the test-centre building", WHITE, hexrgb("#2B5FB3"), 4.5),
    ("yellow #F2C94C / asphalt (graphic)", "bay lines in the mirrors and the top view (graphics: 3:1)", hexrgb("#F2C94C"), ASPHALT, 3.0),
    ("white #F2F2EE / asphalt (graphic)", "white lines in the mirrors and the top view (graphics: 3:1)", hexrgb("#F2F2EE"), ASPHALT, 3.0),
]


def measure(path, box):
    im = Image.open(path).convert("RGB").crop(box)
    px = list(im.getdata())
    ls = sorted((lum(p), p) for p in px)
    n = len(ls)
    med = ls[n // 2][0]
    lo, hi = ls[max(0, int(n * 0.005))][0], ls[min(n - 1, int(n * 0.995))][0]
    dark_text = (med - lo) > (hi - med)
    # background: the pixels on the background side of the median
    bgp = [p for _, p in (ls[int(n * 0.45):int(n * 0.95)] if dark_text else ls[int(n * 0.05):int(n * 0.55)])]
    bg = tuple(sorted(p[i] for p in bgp)[len(bgp) // 2] for i in range(3))
    # text: the stroke pixels, i.e. past the midpoint between the background and the extreme; their median colour
    lb = lum(bg)
    ext = lo if dark_text else hi
    cut = (lb + ext) / 2
    strokes = [p for l, p in ls if (l <= cut if dark_text else l >= cut)]
    strokes = strokes if len(strokes) >= 3 else [p for _, p in (ls[:3] if dark_text else ls[-3:])]
    # stroke cores: the 10 % of stroke pixels nearest the extreme (anti-aliased edges excluded), median colour
    strokes.sort(key=lum, reverse=not dark_text)
    core = strokes[: max(3, len(strokes) // 10)]
    fg = tuple(sorted(p[i] for p in core)[len(core) // 2] for i in range(3))
    return fg, bg


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    shots = sys.argv[1].rstrip("/")
    md_out = sys.argv[sys.argv.index("--md") + 1] if "--md" in sys.argv else None
    from contrast_samples import EXEMPT, SAMPLES  # (pair, where, shot, box, threshold)

    rows = []
    for pair, where, fg, bg, th in FIXED:
        r = ratio(fg, bg)
        rows.append((pair, where, "exact", hx(fg), hx(bg), r, th))
    for pair, where, shot, box, th in SAMPLES:
        fg, bg = measure(f"{shots}/{shot}.png", box)
        rows.append((pair, where, f"{shot}.png {box}", hx(fg), hx(bg), ratio(fg, bg), th))
    out = ["| # | 前景 / 底 | 用在哪 | 取法 | 前景 | 底 | 对比度 | 要求 | 结论 |", "|---|---|---|---|---|---|---|---|---|"]
    bad = 0
    for i, (pair, where, how, f, b, r, th) in enumerate(rows, 1):
        ok = r >= th
        bad += 0 if ok else 1
        out.append(f"| {i} | {pair} | {where} | {how} | {f} | {b} | {r:.2f} : 1 | ≥ {th} | {'过' if ok else '**不过**'} |")
    for where, shot, box in EXEMPT:
        fg, bg = measure(f"{shots}/{shot}.png", box)
        out.append(f"| – | （豁免）{where} | WCAG 1.4.3 不要求 | {shot}.png {box} | {hx(fg)} | {hx(bg)} | {ratio(fg, bg):.2f} : 1 | — | 豁免 |")
    text = "\n".join(out)
    print(text)
    print(f"\n{len(rows)} pairs, {bad} below threshold")
    if md_out:
        open(md_out, "w", encoding="utf-8").write(text + "\n")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
