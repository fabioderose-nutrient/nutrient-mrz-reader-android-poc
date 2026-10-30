"""Draws the redaction audit exported by RedactionAuditTest, one review image per document.

Usage: python3 tools/render_audit.py <audit dir>   (writes <audit dir>/review/<name>.png)
Left panel: Keywords method, right panel: MRZ anchor method. Thin grey boxes = OCR words,
green = MRZ box, cyan arrow = MRZ frame (origin, text direction).
"""
import json
import math
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

COLORS = {
    "PHOTO": (124, 58, 237), "MRZ": (15, 118, 110), "PERSONAL_DATA": (220, 38, 38), "BACK_DATA": (220, 38, 38),
    "HEADER": (234, 88, 12), "SURNAME": (220, 38, 38), "GIVEN_NAMES": (220, 38, 38), "DOCUMENT_NUMBER": (234, 88, 12),
    "PERSONAL_NUMBER": (234, 88, 12), "DATE_OF_BIRTH": (37, 99, 235), "DATE_OF_ISSUE": (37, 99, 235),
    "DATE_OF_EXPIRY": (37, 99, 235),
}
FONT = ImageFont.truetype("/System/Library/Fonts/Supplemental/Arial Bold.ttf", 15)
SMALL = ImageFont.truetype("/System/Library/Fonts/Supplemental/Arial.ttf", 13)


def panel(img, data, method, width=600):
    k = width / img.width
    im = img.convert("RGB").resize((width, int(img.height * k)))
    over = Image.new("RGBA", im.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(over)
    for w in data.get("words", []):
        b = [v * k for v in w["b"]]
        d.rectangle(b, outline=(120, 120, 120, 110))
    if "mrzBox" in data:
        d.rectangle([v * k for v in data["mrzBox"]], outline=(22, 163, 74, 255), width=2)
    for reg in data.get(method, []):
        c = COLORS.get(reg["field"], (219, 39, 119))
        for r in reg["rects"]:
            d.rectangle([v * k for v in r], fill=c + (70,), outline=c + (255,), width=2)
        top = min(reg["rects"], key=lambda r: r[1])
        d.text((top[0] * k + 2, max(0, top[1] * k - 16)), reg["field"].lower(), fill=c + (255,), font=FONT)
    f = data.get("frame")
    if f:
        ox, oy, a = f["ox"] * k, f["oy"] * k, math.radians(f["angle"])
        L = 40 * f["pxPerMm"] * k
        d.line([ox, oy, ox + L * math.cos(a), oy + L * math.sin(a)], fill=(6, 182, 212, 255), width=3)
        d.ellipse([ox - 4, oy - 4, ox + 4, oy + 4], fill=(6, 182, 212, 255))
    im = Image.alpha_composite(im.convert("RGBA"), over).convert("RGB")
    head = Image.new("RGB", (width, 22), "white")
    ImageDraw.Draw(head).text((4, 3), method, fill="black", font=FONT)
    out = Image.new("RGB", (width, im.height + 22), "white")
    out.paste(head, (0, 0))
    out.paste(im, (0, 22))
    return out


def main(audit_dir):
    audit = Path(audit_dir)
    review = audit / "review"
    review.mkdir(exist_ok=True)
    for js in sorted(audit.glob("*.json")):
        data = json.loads(js.read_text())
        img = Image.open(audit / (js.stem + ".jpg"))
        a, b = panel(img, data, "KEYWORDS"), panel(img, data, "MRZ_ANCHOR")
        info = (f'{data["file"]} | {data["status"]} {data.get("checks", "")} | rot {data["rotation"]} | '
                f'lang {data.get("ocrLanguage")} | {len(data.get("words", []))} words | '
                f'{" ".join(data.get("lines", []))}')
        sheet = Image.new("RGB", (a.width + b.width + 10, max(a.height, b.height) + 24), "white")
        ImageDraw.Draw(sheet).text((4, 4), info[:230], fill="black", font=SMALL)
        sheet.paste(a, (0, 24))
        sheet.paste(b, (a.width + 10, 24))
        sheet.save(review / f"{js.stem}.png")
    print(f"{len(list(review.glob('*.png')))} review images in {review}")


if __name__ == "__main__":
    main(sys.argv[1])
