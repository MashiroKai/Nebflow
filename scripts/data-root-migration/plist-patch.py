#!/usr/bin/env python3
"""plist-patch.py — binary-safe launchd plist path patcher for the .nebflow → .stand migration.

  --check <plist> <src>      exit 0 if patchable refs exist, 1 if none (or on error)
  --patch <plist> <src> <dst>  backup NOT done here (caller does .bak); rewrites plist in place

Rules:
  * any string value containing <src> NOT followed by [-\\w] (protects .nebflow-stable) → <dst>
  * NEBFLOW_HOME whose value equals <src> (or nests under it) → <dst>
  * file rewritten as XML plist (launchd accepts xml natively); mode/owner preserved
"""
import os
import plistlib
import re
import sys
import tempfile


def rewrite(value: str, src: str, dst: str) -> str:
    return re.sub(re.escape(src) + r"(?![-\w])", lambda m: dst, value)


def walk(obj, src, dst):
    hits = 0
    if isinstance(obj, dict):
        for k, v in list(obj.items()):
            if isinstance(v, str):
                nv = rewrite(v, src, dst)
                if nv != v:
                    obj[k] = nv
                    hits += 1
            else:
                hits += walk(v, src, dst)
    elif isinstance(obj, list):
        for i, v in enumerate(obj):
            if isinstance(v, str):
                nv = rewrite(v, src, dst)
                if nv != v:
                    obj[i] = nv
                    hits += 1
            else:
                hits += walk(v, src, dst)
    return hits


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        return 2
    mode, path, src = sys.argv[1], sys.argv[2], sys.argv[3]
    dst = sys.argv[4] if len(sys.argv) > 4 else None
    try:
        with open(path, "rb") as f:
            data = plistlib.load(f)
    except Exception as e:  # noqa: BLE001
        print(f"plist-patch: cannot parse {path}: {e}", file=sys.stderr)
        return 1
    hits = walk(data, src, dst or "")
    if mode == "--check":
        return 0 if hits > 0 else 1
    if mode == "--patch":
        if hits == 0:
            print(f"plist-patch: no refs to {src} in {path}; nothing to do")
            return 0
        d = os.path.dirname(path) or "."
        fd, tmp = tempfile.mkstemp(dir=d, suffix=".plist.tmp")
        try:
            with os.fdopen(fd, "wb") as f:
                plistlib.dump(data, f, fmt=plistlib.FMT_XML)
            shutil_copymode(path, tmp)
            os.replace(tmp, path)
        except Exception:
            if os.path.exists(tmp):
                os.unlink(tmp)
            raise
        print(f"plist-patch: {hits} ref(s) rewritten in {path} ({src} → {dst}); saved as XML")
        return 0
    print(f"unknown mode {mode}", file=sys.stderr)
    return 2


def shutil_copymode(src, dst):
    import shutil
    shutil.copymode(src, dst)


if __name__ == "__main__":
    sys.exit(main())
