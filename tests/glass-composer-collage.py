"""Combine existing product composer captures without scaling, with in-image labels."""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

folder = Path('/private/tmp/gsu-evid/composer-compare')
items = [('LIGHT  alpha=0.950', 'light-095.png'),
         ('LIGHT  alpha=0.969 (current)', 'light-current.png'),
         ('DARK  alpha=0.941 (current)', 'dark-current.png')]
images = [(label, Image.open(folder / name).convert('RGB')) for label, name in items]
assert len({img.size for _, img in images}) == 1, 'crops differ'
w, h = images[0][1].size
canvas = Image.new('RGB', (3 * w, h + 56), 'white')
draw = ImageDraw.Draw(canvas)
try:
    font = ImageFont.truetype('/System/Library/Fonts/Supplemental/Arial.ttf', 18)
except OSError:
    font = ImageFont.load_default()
for i, (label, image) in enumerate(images):
    draw.text((i*w + 8, 6), label, font=font, fill='black')
    canvas.paste(image, (i*w, 32))
draw.text((8, h + 35), 'Identical injected color-gradient background; product page, 1:1 crops.', font=font, fill='black')
path = folder / 'composer-comparison.png'
canvas.save(path)
print(path, canvas.size, 'source crops', (w, h))
