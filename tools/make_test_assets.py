"""Generates image fixtures for JVM and instrumented tests.

Usage: python3 -I tools/make_test_assets.py
Needs Pillow and webpmux (libwebp) on PATH.
"""
import os
import shutil
import subprocess
import sys
import tempfile

from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUTS = [
    os.path.join(ROOT, "app/src/test/resources/images"),
    os.path.join(ROOT, "app/src/androidTest/assets/images"),
]
W, H = 64, 48


def frame(i):
    im = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    ImageDraw.Draw(im).rectangle([i * 8, 8, i * 8 + 16, 40], fill=(255, 40 * i, 0, 255))
    return im


def build_anim_webp(frames, path):
    """Assembles an animated WebP with real alpha, dispose-to-background and alpha blending.

    webpmux frame options: +duration+x+y+dispose+blend, where dispose 1 is background
    and +b is alpha blending. Frame 1 uses dispose none and no blend, the rest dispose
    to background and blend.
    """
    with tempfile.TemporaryDirectory() as tmp:
        args = ["webpmux"]
        for i, im in enumerate(frames):
            part = os.path.join(tmp, "f%d.webp" % i)
            im.save(part, lossless=True, exact=True)
            opts = "+200+0+0+0-b" if i == 0 else "+200+0+0+1+b"
            args += ["-frame", part, opts]
        args += ["-loop", "0", "-bgcolor", "0,0,0,0", "-o", path]
        subprocess.run(args, check=True)


def build(out):
    frames = [frame(i) for i in range(4)]
    frames[0].save(os.path.join(out, "anim.gif"), save_all=True, append_images=frames[1:],
                   duration=200, loop=0, disposal=2, transparency=0)
    frames[0].save(os.path.join(out, "still.gif"))
    frames[0].save(os.path.join(out, "anim.png"), save_all=True, append_images=frames[1:],
                   duration=200, loop=0, disposal=1, blend=1)
    frames[0].save(os.path.join(out, "still.png"))
    frames[0].save(os.path.join(out, "still.webp"), lossless=True)
    frames[0].convert("RGB").save(os.path.join(out, "still.jpg"), quality=90)
    frames[0].convert("RGB").save(os.path.join(out, "still.bmp"))
    build_anim_webp(frames, os.path.join(out, "anim.webp"))
    exif = Image.Exif()
    exif[0x0112] = 6  # Orientation: rotate 90 degrees clockwise to display.
    Image.new("RGB", (40, 20), (0, 128, 255)).save(os.path.join(out, "rotated.jpg"), exif=exif)
    with open(os.path.join(out, "corrupt.jpg"), "wb") as f:
        f.write(b"\xff\xd8\xff\xe0" + b"\x00" * 200)


def main():
    for out in OUTS:
        shutil.rmtree(out, ignore_errors=True)
        os.makedirs(out)
        build(out)
    print("wrote fixtures to", ", ".join(OUTS))


if __name__ == "__main__":
    sys.exit(main())
