"""Build the tiny test face's dawft source folder: one flat background, RLE-compressed (~4 KB .bin).
It has no clock, just blue, green and red blocks, so it's easy to see whether an upload landed.
    python tiny.py && dawft create folder=tiny_test tiny_test.bin"""
import os
from PIL import Image, ImageDraw

W, H = 240, 280
os.makedirs("tiny_test", exist_ok=True)
im = Image.new("RGB", (W, H), (0, 0, 0))
d = ImageDraw.Draw(im)
# flat blocks only (no anti-aliasing), so every row compresses to a few bytes
d.rectangle([0, 0, W, 90], fill=(0, 70, 160))
d.rectangle([0, 190, W, H], fill=(200, 20, 40))
d.rectangle([60, 110, 180, 170], fill=(40, 200, 110))
d.rectangle([90, 125, 150, 155], fill=(255, 255, 255))
im.save("tiny_test/background000.bmp", format="BMP")
with open("tiny_test/watchface.txt", "w") as f:
    f.write("fileType       C\nfileID         0x81\ndataCount      1\nblobCount      1\nfaceNumber     50103\n\n"
            "#              TYPE  INDEX      X    Y    W    H    FILENAME\n"
            "faceData       0x01    000      0    3  240  280    background000.bmp  # BACKGROUND\n\n"
            "blobCompression 000  RLE_LINE\n")
print("built tiny_test")
