"""OCR control: same cropped glass text pixels, no contrast enhancement."""
import csv
import io
import os
import subprocess
from PIL import Image

out = os.environ.get('GSU_EVID', '/private/tmp/gsu-evid')
img = Image.open(os.path.join(out, 'parameter-grid.png')).convert('RGB')
alphas = [.85, .88, .90, .92, .94, .96, .98, 1, 0]
blurs = [0, 8, 16, 24, 32]
rows = []
for i, (alpha, blur) in enumerate((a, b) for a in alphas for b in blurs):
    y = i * 160 + 20
    crop = img.crop((20, y, 270, y + 120))
    buf = io.BytesIO()
    crop.save(buf, format='PNG')
    proc = subprocess.run(['tesseract', 'stdin', 'stdout', '--psm', '7'], input=buf.getvalue(), capture_output=True, check=True)
    text = proc.stdout.decode().strip()
    rows.append({'alpha': alpha, 'blur': blur, 'ocr': text})
    if (alpha, blur) in [(0, 0), (1, 24), (.94, 24), (.96, 24)]:
        crop.save(os.path.join(out, f'ocr-alpha-{alpha}-blur-{blur}.png'))
assert next(r['ocr'] for r in rows if r['alpha'] == 0 and r['blur'] == 0) == 'STAND', 'OCR positive control failed'
assert next(r['ocr'] for r in rows if r['alpha'] == 1 and r['blur'] == 24) != 'STAND', 'OCR negative control failed'
with open(os.path.join(out, 'grid-ocr.csv'), 'w', newline='') as f:
    writer = csv.DictWriter(f, fieldnames=['alpha', 'blur', 'ocr'])
    writer.writeheader()
    writer.writerows(rows)
print('PASS OCR controls; output:', os.path.join(out, 'grid-ocr.csv'))
