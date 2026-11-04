#!/usr/bin/env python3
"""Rehearsal step 3a: census + de-hazard the REPLICA's symlinks.

Why this exists (finding F2/F4 of this rehearsal):
RootLayout.copyTree() follows symlinks (os.isDir / os.read.bytes both dereference).
On the real root that means
  * a BROKEN symlink            => java.nio.file.NoSuchFileException, migrate aborts
  * a self-referential loop     => FileSystemException "Too many levels of symbolic links"
  * a symlink to a socket       => FileSystemException "Operation not supported on socket"
  * a symlink pointing OUTSIDE  => the OUTSIDE target's bytes get copied INTO the
    the data root                 migrated tree (data amplification / foreign content leak)

To let the rehearsal exercise migrate()/rollback() end to end on realistic data we
remove the hazardous links FROM THE REPLICA ONLY (they stay untouched on the real
root). Every removal is listed in the evidence file.

usage: 03-census-repair.py <replicaRoot> [--apply]
       without --apply: census only (writes nothing but the list)
"""
import json
import os
import stat
import sys


def census(root: str):
    root_abs = os.path.abspath(root)
    broken, sockets, outside, loops, ok = [], [], [], [], 0
    total = 0
    for dp, dn, fn in os.walk(root_abs, followlinks=False):
        for name in list(dn) + fn:
            p = os.path.join(dp, name)
            if not os.path.islink(p):
                continue
            total += 1
            rel = os.path.relpath(p, root_abs)
            target = os.readlink(p)
            real = os.path.realpath(p)
            if not os.path.exists(p):
                broken.append({"path": rel, "target": target})
                continue
            if not real.startswith(root_abs.rstrip("/") + "/"):
                outside.append({"path": rel, "target": target, "resolved": real})
                continue
            try:
                st = os.stat(p)
            except OSError as e:
                loops.append({"path": rel, "error": str(e)})
                continue
            if stat.S_ISSOCK(st.st_mode):
                sockets.append({"path": rel, "target": target})
            else:
                ok += 1
    return {"root": root_abs, "total": total, "ok_in_root_resolvable": ok,
            "broken": broken, "socket": sockets, "outside": outside, "stat_fail": loops}


def main() -> int:
    root = sys.argv[1]
    apply_removal = "--apply" in sys.argv[2:]
    c = census(root)
    print(json.dumps({k: (len(v) if isinstance(v, list) else v) for k, v in c.items()}, indent=1))
    hazardous = c["broken"] + c["socket"] + c["outside"] + c["stat_fail"]
    if apply_removal:
        removed = 0
        for item in hazardous:
            p = os.path.join(os.path.abspath(root), item["path"])
            os.unlink(p)  # unlink removes the LINK only, never the target
            removed += 1
        print(f"[repair] removed {removed} hazardous symlink(s) from the REPLICA (real root untouched)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
