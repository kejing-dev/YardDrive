"""Renders every res/drawable/ic_*.xml (Material Symbols vectors) into one labelled sheet (profile D23 audit).
    python3 tools/icon_sheet.py OUT.png"""
import glob, os, re, subprocess, sys, tempfile
ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
CHROME = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
files = sorted(glob.glob(os.path.join(ROOT, "app/src/main/res/drawable/ic_*.xml")))
cells = []
for f in files:
    s = open(f).read()
    vw = re.search(r'viewportWidth="([\d.]+)"', s).group(1)
    vh = re.search(r'viewportHeight="([\d.]+)"', s).group(1)
    paths = re.findall(r'pathData="([^"]+)"', s)
    groups = re.findall(r'<group([^>]*)>', s)
    name = os.path.basename(f)[:-4]
    inner = "".join('<path fill="#1b1b1b" d="%s"/>' % p for p in paths)
    if groups:  # the launcher foreground: show it on its tile colour
        parts = []
        for g, p in zip(groups, paths):
            tx = float(re.search(r'translateX="([-\d.]+)"', g).group(1)); ty = float(re.search(r'translateY="([-\d.]+)"', g).group(1))
            sx = float(re.search(r'scaleX="([-\d.]+)"', g).group(1)); sy = float(re.search(r'scaleY="([-\d.]+)"', g).group(1))
            parts.append('<g transform="translate(%s %s) scale(%s %s)"><path fill="#fff" d="%s"/></g>' % (tx, ty, sx, sy, p))
        inner = '<rect width="108" height="108" fill="#1D4E89"/>' + "".join(parts)
    cells.append('<div style="display:inline-block;width:150px;margin:6px;text-align:center;font:12px sans-serif"><svg width="72" height="72" viewBox="0 0 %s %s">%s</svg><br>%s</div>' % (vw, vh, inner, name))
html = '<html><body style="margin:8px;background:#f4f5f2">%s</body></html>' % "".join(cells)
with tempfile.TemporaryDirectory() as d:
    p = os.path.join(d, "s.html"); open(p, "w").write(html)
    subprocess.run([CHROME, "--headless=new", "--disable-gpu", "--hide-scrollbars", "--window-size=1000,%d" % (130 * ((len(files) + 5) // 6) + 30), "--screenshot=" + sys.argv[1], "file://" + p], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
print(sys.argv[1], len(files), "icons")
