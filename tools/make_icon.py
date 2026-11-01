"""Yard Drive icon (design appIcon): asphalt teal-grey tile #2F4858, a yellow U-shaped bay (open at the top), a white
top-down car half in the bay with its nose up, and a short white arc arrow off its tail to the lower right (reversing).
The foreground is res/drawable/ic_launcher_foreground.xml (strokes and fills in the 108-unit adaptive viewport); this
script renders it into the PICO 3D launcher layers (icon_3d_layer_0/1, icon_3d_sdf_0/1, 1024 px) with headless Chrome
and optionally exports a 512 PNG.

    python3 tools/make_icon.py [export_512.png]
"""
import os
import re
import subprocess
import sys
import tempfile

from PIL import Image

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
RES = os.path.join(ROOT, "app", "src", "main", "res")
BG = "#2F4858"
CHROME = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"


def argb(c):
    c = c.lstrip("#")
    if len(c) == 8:
        a = int(c[:2], 16) / 255.0
        return "#" + c[2:], a
    return "#" + c, 1.0


def fg_svg_body():
    s = open(os.path.join(RES, "drawable", "ic_launcher_foreground.xml")).read()
    out = []
    for m in re.finditer(r"<path(.*?)/>", s, re.S):
        a = dict(re.findall(r'android:(\w+)="([^"]*)"', m.group(1)))
        fill, fa = argb(a.get("fillColor", "#00000000"))
        attrs = 'd="%s" fill="%s" fill-opacity="%.2f"' % (a["pathData"], fill, fa)
        if "strokeColor" in a:
            sc, sa = argb(a["strokeColor"])
            attrs += ' stroke="%s" stroke-opacity="%.2f" stroke-width="%s" stroke-linecap="%s" stroke-linejoin="%s"' % (
                sc, sa, a.get("strokeWidth", "1"), a.get("strokeLineCap", "butt"), a.get("strokeLineJoin", "miter"))
        out.append("<path %s/>" % attrs)
    return "".join(out)


def svg(tile, glyphs, size=1024):
    k = size / 108.0
    body = []
    if tile:
        body.append('<rect x="40" y="40" width="%d" height="%d" rx="220" fill="%s"/>' % (size - 80, size - 80, BG))
    if glyphs:
        body.append('<g transform="scale(%.5f)">%s</g>' % (k, fg_svg_body()))
    return '<svg xmlns="http://www.w3.org/2000/svg" width="%d" height="%d" viewBox="0 0 %d %d">%s</svg>' % (size, size, size, size, "".join(body))


def render(svg_text, out_png, size=1024):
    with tempfile.TemporaryDirectory() as d:
        html = os.path.join(d, "i.html")
        open(html, "w").write('<html><body style="margin:0;background:transparent">%s</body></html>' % svg_text)
        subprocess.run([CHROME, "--headless=new", "--disable-gpu", "--hide-scrollbars", "--default-background-color=00000000", "--window-size=%d,%d" % (size, size), "--screenshot=" + out_png, "file://" + html],
                       check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def alpha_white(im):
    w = Image.new("RGBA", im.size, (255, 255, 255, 0))
    w.putalpha(im.split()[3])
    return w


def main():
    draw = os.path.join(RES, "drawable")
    l0p = os.path.join(draw, "icon_3d_layer_0.png")
    l1p = os.path.join(draw, "icon_3d_layer_1.png")
    render(svg(True, False), l0p)
    render(svg(False, True), l1p)
    l0 = Image.open(l0p).convert("RGBA")
    l1 = Image.open(l1p).convert("RGBA")
    l0.save(l0p)
    l1.save(l1p)
    alpha_white(l0).save(os.path.join(draw, "icon_3d_sdf_0.png"))
    alpha_white(l1).save(os.path.join(draw, "icon_3d_sdf_1.png"))
    if len(sys.argv) > 1:
        Image.alpha_composite(l0, l1).resize((512, 512), Image.LANCZOS).save(sys.argv[1])
        print("export", sys.argv[1])


if __name__ == "__main__":
    main()
