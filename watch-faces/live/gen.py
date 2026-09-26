"""Generate dawft source folders for live-data watch faces (MoYoung type C, 240x286 screen)."""
import math, os, sys
from PIL import Image, ImageDraw, ImageFont, ImageFilter

W, H = 240, 280          # background size; placed at y=3 like other 240x286 (tpls 56) faces
Y0 = 3
REG = "/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf"
BOLD = "/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf"

def font(path, size): return ImageFont.truetype(path, size)

def glyph(text, w, h, fnt, fg, bg):
    """Render text centred in a w x h box. bg is the background crop under the element,
    so the digit blends in exactly (the watch itself has no alpha channel)."""
    im = bg.copy()
    d = ImageDraw.Draw(im)
    l, t, r, b = d.textbbox((0, 0), text, font=fnt)
    d.text(((w - (r - l)) / 2 - l, (h - (b - t)) / 2 - t), text, font=fnt, fill=fg)
    return im

def save(im, folder, name): im.save(os.path.join(folder, name), format="BMP")

def ring_frame(size, pct, color, track, bg, width):
    im = bg.resize((size * 4, size * 4))   # 4x supersample for smooth arcs
    d = ImageDraw.Draw(im)
    pad = width * 2
    box = [pad, pad, size * 4 - pad, size * 4 - pad]
    d.arc(box, 0, 360, fill=track, width=width * 4)
    if pct > 0:
        d.arc(box, -90, -90 + 360 * pct / 100, fill=color, width=width * 4)
    return im.resize((size, size), Image.LANCZOS)

def build(name, theme):
    folder = os.path.join(os.getcwd(), name); os.makedirs(folder, exist_ok=True)
    bg = theme["background"]()
    d = ImageDraw.Draw(bg)
    # static labels and colon baked into the background
    lab = font(BOLD, 10)
    for x, text, col in [(18, "STEPS", theme["steps"]), (104, "BPM", theme["hr"]), (178, "BATT %", theme["batt"])]:
        d.text((x, 240 - Y0), text, font=lab, fill=col)
    colon = font(REG, 54)
    d.text((113, 36 - Y0), ":", font=colon, fill=theme["time"])
    # heart in ring centre
    cx, cy = 120, 188 - Y0
    heart = [(cx, cy + 12), (cx - 13, cy - 1), (cx - 13, cy - 7), (cx - 8, cy - 12), (cx - 3, cy - 12), (cx, cy - 8),
             (cx + 3, cy - 12), (cx + 8, cy - 12), (cx + 13, cy - 7), (cx + 13, cy - 1)]
    d.polygon(heart, fill=theme["hr"])
    save(bg, folder, "background000.bmp")

    tfont, sfont, dfont = font(REG, 62), font(BOLD, 17), font(BOLD, 14)
    def under(x, y, w, h):  # background patch under an element at screen coords
        return bg.crop((x, y - Y0, x + w, y - Y0 + h))
    # Every digit of a set shares one bitmap, so sample the patch under the first digit.
    for i in range(10):
        save(glyph(str(i), 44, 64, tfont, theme["time"], under(20, 36, 44, 64)), folder, f"time{i:03d}.bmp")
        save(glyph(str(i), 10, 16, dfont, theme["muted"], under(124, 108, 10, 16)), folder, f"dnum{i:03d}.bmp")
        save(glyph(str(i), 11, 18, sfont, theme["steps"], under(18, 256, 11, 18)), folder, f"steps{i:03d}.bmp")
        save(glyph(str(i), 11, 18, sfont, theme["hr"], under(104, 256, 11, 18)), folder, f"hr{i:03d}.bmp")
        save(glyph(str(i), 11, 18, sfont, theme["batt"], under(178, 256, 11, 18)), folder, f"batt{i:03d}.bmp")
    for i, day in enumerate(["SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT"]):
        save(glyph(day, 40, 16, dfont, theme["muted"], under(76, 108, 40, 16)), folder, f"day{i:03d}.bmp")
    for i in range(11):
        save(ring_frame(104, i * 10, theme["ring"], theme["ring_track"], under(68, 136, 104, 104), 9), folder, f"ring{i:03d}.bmp")

    lines = [
        ("0x01", 0,   0,   Y0, W,   H,  "background000.bmp", "BACKGROUND"),
        ("0x40", 1,  20,  36, 44,  64,  "time000.bmp",  "TIME_H1"),
        ("0x41", 1,  66,  36, 44,  64,  "time000.bmp",  "TIME_H2"),
        ("0x43", 1, 130,  36, 44,  64,  "time000.bmp",  "TIME_M1"),
        ("0x44", 1, 176,  36, 44,  64,  "time000.bmp",  "TIME_M2"),
        ("0x60", 11, 76, 108, 40,  16,  "day000.bmp",   "DAY_NAME"),
        ("0x30", 18, 124, 108, 10, 16,  "dnum000.bmp",  "DAY_NUM"),
        ("0x70", 58, 68, 136, 104, 104, "ring000.bmp",  "STEPS_PROGBAR"),
        ("0x62", 28, 18, 256, 11,  18,  "steps000.bmp", "STEPS"),
        ("0x65", 38, 104, 256, 11, 18,  "hr000.bmp",    "HR"),
        ("0xD2", 48, 178, 256, 11, 18,  "batt000.bmp",  "BATT"),
    ]
    with open(os.path.join(folder, "watchface.txt"), "w") as f:
        f.write(f"fileType       C\nfileID         0x81\ndataCount      {len(lines)}\nblobCount      69\nfaceNumber     {theme['number']}\n\n")
        f.write("#              TYPE  INDEX      X    Y    W    H    FILENAME\n")
        for t, idx, x, y, w, h, fn, cm in lines:
            f.write(f"faceData       {t}    {idx:03d}    {x:3d}  {y:3d}  {w:3d}  {h:3d}    {fn:<18} # {cm}\n")
        f.write("\n# keep digits uncompressed\n")
        for i in range(1, 58):
            f.write(f"blobCompression {i:03d}  NONE\n")

def onedark_bg():
    im = Image.new("RGB", (W, H), (0, 0, 0))
    d = ImageDraw.Draw(im)
    for y in range(130):  # soft navy glow at the top, fading to black by y=130
        a = 1 - y / 130
        d.line([(0, y), (W, y)], fill=(int(20 * a), int(26 * a), int(46 * a)))
    return im

def web_bg():
    im = Image.new("RGB", (W, H), (0, 0, 0))
    d = ImageDraw.Draw(im)
    for y in range(H):
        t = y / H
        if t < 0.46: c = (10, 26 + int(8 * t), 74 + int(20 * t))
        elif t < 0.54: s = (t - 0.46) / 0.08; c = (int(10 + 110 * s), int(30 - 16 * s), int(92 - 62 * s))
        else: c = (120 - int(40 * (t - .54)), 14, 30)
        d.line([(0, y), (W, y)], fill=c)
    web = im.copy(); wd = ImageDraw.Draw(web)
    cx, cy = 120, 300
    for i in range(15):   # radial strands
        a = math.pi + i * math.pi / 14
        wd.line([(cx, cy), (cx + 420 * math.cos(a), cy + 420 * math.sin(a))], fill=(255, 255, 255), width=1)
    for r in range(40, 420, 34):   # rings
        wd.arc([cx - r, cy - r, cx + r, cy + r], 180, 360, fill=(255, 255, 255), width=1)
    im = Image.blend(im, web, 0.22)
    d = ImageDraw.Draw(im)
    # darken areas behind live elements so they sit on a solid colour
    d.rectangle([0, 30, W, 128], fill=(10, 28, 80))
    d.ellipse([60, 128, 180, 248], fill=(0, 0, 0))
    d.rectangle([0, 234, W, H], fill=(0, 0, 0))
    return im

THEMES = {
    "onedark_live": dict(number=50101, background=onedark_bg, bg_solid=(0, 0, 0),
                         time=(255, 255, 255), muted=(150, 160, 180), steps=(61, 220, 132), hr=(255, 84, 112),
                         batt=(200, 206, 220), ring=(61, 220, 132), ring_track=(22, 48, 34)),
    "web_live": dict(number=50102, background=web_bg, bg_solid=None,
                     time=(255, 255, 255), muted=(190, 200, 230), steps=(255, 255, 255), hr=(255, 70, 90),
                     batt=(200, 210, 240), ring=(230, 30, 50), ring_track=(50, 10, 16)),
}

for name in (sys.argv[1:] or THEMES):
    build(name, THEMES[name])
    print("built", name)
