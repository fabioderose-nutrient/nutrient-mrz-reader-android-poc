"""Generates synthetic specimen documents (ICAO 9303 'Utopia' data, no real person).

Usage: python3 tools/make_specimens.py <output_dir>
Writes specimen_td3.jpg (clean data page), specimen_td3_photo.jpg (phone-like photo:
tilt, blur, uneven light, noise), specimen_td3_portrait_tilted.jpg (page turned to portrait
and tilted on a desk), specimen_td1_back.jpg (ID card back) and no_mrz.jpg.
Needs Pillow. Fonts: Courier New Bold and Arial from macOS (OCR-B is not shipped with macOS);
set SPECIMEN_FONT_DIR to a folder with the same .ttf files on other systems.
"""
import os
import random
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

FONTS = Path(os.environ.get("SPECIMEN_FONT_DIR", "/System/Library/Fonts/Supplemental"))
MONO = str(FONTS / "Courier New Bold.ttf")
SANS = str(FONTS / "Arial.ttf")
SANS_B = str(FONTS / "Arial Bold.ttf")

PX_PER_MM = 12
TD3_MRZ = [
    "P<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<<<<<<<<<",
    "L898902C36UTO7408122F1204159ZE184226B<<<<<10",
]
TD1_MRZ = [
    "I<UTOD231458907<<<<<<<<<<<<<<<",
    "7408122F1204159UTO<<<<<<<<<<<6",
    "ERIKSSON<<ANNA<MARIA<<<<<<<<<<",
]


def mm(v):
    return int(v * PX_PER_MM)


def draw_mrz(d, lines, left_mm, top_mm):
    pitch = 2.54 * PX_PER_MM  # OCR-B character pitch
    font = ImageFont.truetype(MONO, int(pitch / 0.6))
    for i, line in enumerate(lines):
        y = mm(top_mm) + i * mm(4.6)
        for j, c in enumerate(line):
            d.text((mm(left_mm) + j * pitch, y), c, font=font, fill=(20, 20, 25))


def background(w, h, seed):
    rnd = random.Random(seed)
    img = Image.new("RGB", (w, h), (236, 240, 232))
    d = ImageDraw.Draw(img)
    for _ in range(140):  # faint guilloche-like curves
        x, y = rnd.randint(0, w), rnd.randint(0, h)
        r = rnd.randint(40, 260)
        d.arc((x - r, y - r, x + r, y + r), rnd.randint(0, 360), rnd.randint(0, 360), fill=(214, 222, 210), width=2)
    return img


def td3_page():
    w, h = mm(125), mm(88)
    img = background(w, h, 3)
    d = ImageDraw.Draw(img)
    label = ImageFont.truetype(SANS, mm(2.0))
    value = ImageFont.truetype(SANS_B, mm(3.0))
    title = ImageFont.truetype(SANS_B, mm(4.0))
    d.text((mm(6), mm(4)), "UTOPIA", font=title, fill=(30, 50, 110))
    d.text((mm(48), mm(4)), "PASSPORT", font=title, fill=(30, 50, 110))
    # Portrait placeholder.
    d.rounded_rectangle((mm(6), mm(18), mm(38), mm(60)), radius=mm(1.5), fill=(190, 196, 205))
    d.ellipse((mm(14), mm(24), mm(30), mm(42)), fill=(150, 156, 168))
    d.rounded_rectangle((mm(10), mm(43), mm(34), mm(60)), radius=mm(6), fill=(150, 156, 168))
    fields = [
        ("Type", "P", 48, 12), ("Code", "UTO", 66, 12), ("Passport No.", "L898902C3", 86, 12),
        ("Surname", "ERIKSSON", 48, 20), ("Given names", "ANNA MARIA", 48, 28),
        ("Nationality", "UTOPIAN", 48, 36), ("Date of birth", "12 AUG 74", 48, 44),
        ("Sex", "F", 100, 44), ("Place of birth", "ZENITH", 48, 52),
        ("Date of issue", "16 APR 07", 48, 60), ("Date of expiry", "15 APR 12", 88, 60),
    ]
    for lab, val, x, y in fields:
        d.text((mm(x), mm(y)), lab, font=label, fill=(80, 80, 90))
        d.text((mm(x), mm(y + 2.4)), val, font=value, fill=(20, 20, 30))
    draw_mrz(d, TD3_MRZ, 6, 72)
    return img


def td1_back():
    w, h = mm(85.6), mm(54)
    img = background(w, h, 7)
    d = ImageDraw.Draw(img)
    d.text((mm(5), mm(5)), "UTOPIA  IDENTITY CARD", font=ImageFont.truetype(SANS_B, mm(3.2)), fill=(30, 50, 110))
    draw_mrz(d, TD1_MRZ, 4, 33)
    return img


def phone_photo(page):
    """Places the page on a desk, tilts it, blurs it and adds uneven light and sensor noise."""
    page = page.rotate(2.5, expand=True, fillcolor=(90, 80, 70), resample=Image.BICUBIC)
    canvas = Image.new("RGB", (page.width + 260, page.height + 300), (95, 84, 72))
    canvas.paste(page, (130, 150))
    canvas = canvas.filter(ImageFilter.GaussianBlur(1.6))
    shade = Image.linear_gradient("L").resize(canvas.size).rotate(90)
    canvas = Image.composite(canvas, Image.new("RGB", canvas.size, (40, 40, 40)), shade.point(lambda v: 170 + v // 3))
    rnd = random.Random(1)
    px = canvas.load()
    for _ in range(canvas.width * canvas.height // 6):
        x, y = rnd.randrange(canvas.width), rnd.randrange(canvas.height)
        r, g, b = px[x, y]
        n = rnd.randint(-28, 28)
        px[x, y] = (max(0, min(255, r + n)), max(0, min(255, g + n)), max(0, min(255, b + n)))
    return canvas.resize((canvas.width * 3 // 4, canvas.height * 3 // 4))


def portrait_tilted(page):
    """The page turned a quarter turn (portrait shot) with a 12 degree tilt, on a plain desk."""
    turned = page.rotate(78, expand=True, fillcolor=(90, 80, 70), resample=Image.BICUBIC)
    canvas = Image.new("RGB", (turned.width + 200, turned.height + 200), (90, 80, 70))
    canvas.paste(turned, (100, 100))
    return canvas


def main(out):
    out = Path(out)
    out.mkdir(parents=True, exist_ok=True)
    page = td3_page()
    page.save(out / "specimen_td3.jpg", quality=92)
    phone_photo(page).save(out / "specimen_td3_photo.jpg", quality=85)
    portrait_tilted(page).save(out / "specimen_td3_portrait_tilted.jpg", quality=88)
    td1_back().save(out / "specimen_td1_back.jpg", quality=92)
    blank = background(mm(125), mm(88), 11)
    ImageDraw.Draw(blank).text((mm(10), mm(30)), "NO MACHINE READABLE ZONE HERE", font=ImageFont.truetype(SANS_B, mm(5)), fill=(30, 30, 30))
    blank.save(out / "no_mrz.jpg", quality=90)


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "specimens")
