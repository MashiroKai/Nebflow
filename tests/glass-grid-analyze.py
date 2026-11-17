"""Read the paired screenshot; output residual text contrast and red/blue separation."""
import csv
import json
import os
from PIL import Image

out = os.environ.get('GSU_EVID', '/private/tmp/gsu-evid')
img = Image.open(os.path.join(out, 'parameter-grid.png')).convert('RGB')
alphas = [.85, .88, .90, .92, .94, .96, .98, 1, 0]
blurs = [0, 8, 16, 24, 32]
rows = []
for i, (alpha, blur) in enumerate((a, b) for a in alphas for b in blurs):
    y0 = i * 160 + 20
    def pixels(x):
        return [img.getpixel((x + dx, y0 + dy)) for dy in range(20, 100) for dx in range(20, 230)]
    text, plain, red, blue = (pixels(x) for x in (20, 290, 560, 830))
    # Mean absolute paired luminance residual over the interior, in 8-bit levels.
    residual = sum(abs(sum(t)/3 - sum(p)/3) for t, p in zip(text, plain))/len(text)
    redavg = [sum(p[c] for p in red)/len(red) for c in range(3)]
    blueavg = [sum(p[c] for p in blue)/len(blue) for c in range(3)]
    separation = sum((redavg[c]-blueavg[c])**2 for c in range(3))**.5
    rows.append(dict(alpha=alpha, blur=blur, text_mae=round(residual, 4), red_blue_rgb_distance=round(separation, 4), red=redavg, blue=blueavg))
# Sensitivity controls, not a recognizability threshold or a consumer acceptance test.
get = lambda a, b: next(r for r in rows if r['alpha'] == a and r['blur'] == b)
assert get(1, 24)['red_blue_rgb_distance'] == 0
assert get(.94, 24)['red_blue_rgb_distance'] > 0
assert get(.85, 0)['text_mae'] > get(.96, 24)['text_mae']
assert get(.94, 0)['text_mae'] > get(.94, 32)['text_mae']
with open(os.path.join(out, 'grid-readings.json'), 'w') as f:
    json.dump(rows, f, indent=2)
with open(os.path.join(out, 'grid-readings.csv'), 'w', newline='') as f:
    w = csv.DictWriter(f, fieldnames=['alpha','blur','text_mae','red_blue_rgb_distance'], extrasaction='ignore')
    w.writeheader(); w.writerows(rows)
print(f'PASS analyzed {len(rows)} samples; metrics and raw data: {out}/grid-readings.json')
