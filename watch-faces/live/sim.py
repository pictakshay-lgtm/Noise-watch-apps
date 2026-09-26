"""Simulate how the watch draws a dumped face: blobs are NNN.bmp by blob index."""
import sys, re
from PIL import Image, ImageDraw
folder, out = sys.argv[1], sys.argv[2]
vals = dict(H1=1, H2=0, M1=0, M2=8, DAY=6, DNUM="26", STEPS="6420", PCT=6, HR="72", BATT="85")
screen = Image.new("RGB", (240, 286), (0, 0, 0))
b = lambda i: Image.open(f"{folder}/{i:03d}.bmp").convert("RGB")
for line in open(f"{folder}/watchface.txt"):
    m = re.match(r"faceData\s+(0x\w+)\s+(\d+)\s+(\d+)\s+(\d+)\s+(\d+)\s+(\d+)", line)
    if not m: continue
    t, idx, x, y, w, h = m.group(1).lower(), *map(int, m.groups()[1:])
    def digits(s):
        for k, ch in enumerate(s): screen.paste(b(idx + int(ch)), (x + k * (w + 2), y))
    if t == "0x01": screen.paste(b(idx), (x, y))
    elif t in ("0x40", "0x41", "0x43", "0x44"): screen.paste(b(idx + vals[{"0x40":"H1","0x41":"H2","0x43":"M1","0x44":"M2"}[t]]), (x, y))
    elif t == "0x60": screen.paste(b(idx + vals["DAY"]), (x, y))
    elif t == "0x30": digits(vals["DNUM"])
    elif t == "0x70": screen.paste(b(idx + vals["PCT"]), (x, y))
    elif t == "0x62": digits(vals["STEPS"])
    elif t == "0x65": digits(vals["HR"])
    elif t == "0xd2": digits(vals["BATT"])
# rounded screen mask for realism
mask = Image.new("L", screen.size, 0); ImageDraw.Draw(mask).rounded_rectangle([0,0,239,285], 36, fill=255)
bgc = Image.new("RGB", (240, 286), (26, 26, 26)); bgc.paste(screen, (0, 0), mask)
bgc.resize((480, 572), Image.NEAREST).save(out)
