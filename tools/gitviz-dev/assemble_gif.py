# assemble_gif.py — 用 PIL 把 Playwright 抓的帧拼成 GIF（本机无 ffmpeg/gifski）。
# 帧来源：capture.mjs 落的 .frames-flip-*.png / .frames-multi-*.png。
# 缩放：桌面 1440 宽帧按 0.5 缩到 720 宽（GIF 体积可控，字仍可读）。
import glob
import os
import sys

from PIL import Image

EV = os.environ.get('GITVIZ_EVIDENCE_DIR') or '/Users/kaiyu/Claude code/Stand/.stand/tmp/gitviz-mvp-20261119/evidence'


def build(pattern, out, width, duration):
    files = sorted(glob.glob(os.path.join(EV, pattern)))
    if not files:
        print(f'[skip] {pattern}: no frames')
        return None
    frames = []
    for f in files:
        im = Image.open(f).convert('RGB')
        h = round(im.height * width / im.width)
        frames.append(im.resize((width, h), Image.LANCZOS))
    out_path = os.path.join(EV, out)
    frames[0].save(out_path, save_all=True, append_images=frames[1:], duration=duration, loop=0, optimize=True)
    size = os.path.getsize(out_path)
    print(f'[ok] {out}: {len(frames)} frames, {size // 1024} KB, {width}px')
    return {'file': out, 'frames': len(frames), 'bytes': size, 'width': width, 'frame_ms': duration}


if __name__ == '__main__':
    res = []
    r1 = build('.frames-flip-*.png', 'gif-01-flip-to-secondary-panel.gif', 720, 110)
    r2 = build('.frames-multi-*.png', 'gif-02-multiselect-forward.gif', 720, 700)
    for r in (r1, r2):
        if r:
            res.append(r)
    print('GIF_JSON=' + repr(res))
