"""Join OCR and CIE76 grid measurements; output the 88 requested cells."""
import csv
import os
from pathlib import Path

root = Path(os.environ.get('GSU_EVID', '/private/tmp/gsu-evid'))
rows = []
for theme in ('light', 'dark'):
    folder = root / ('dense-' + theme)
    with (folder / 'grid-readings.csv').open() as f:
        readings = {(r['alpha'], r['blur']): r for r in csv.DictReader(f)}
    with (folder / 'grid-ocr.csv').open() as f:
        for row in csv.DictReader(f):
            if float(row['alpha']) < .90 or int(row['blur']) == 0:
                continue
            measure = readings[row['alpha'], row['blur']]
            rows.append({'theme': theme, 'alpha': row['alpha'], 'blur': row['blur'],
                         'ocr': row['ocr'], 'delta_e76': measure['delta_e76'],
                         'ocr_pass': row['ocr'].upper() != 'STAND',
                         'color_pass': float(measure['delta_e76']) >= 8})
assert len(rows) == 88
with (root / 'dense-summary.csv').open('w', newline='') as f:
    writer = csv.DictWriter(f, fieldnames=list(rows[0]))
    writer.writeheader(); writer.writerows(rows)
for theme in ('light', 'dark'):
    for blur in (8, 16, 24, 32):
        passed = [r['alpha'] for r in rows if r['theme'] == theme and int(r['blur']) == blur and r['ocr_pass'] and r['color_pass']]
        print(theme, blur, ','.join(passed) or 'none')
print('Wrote', root / 'dense-summary.csv')
