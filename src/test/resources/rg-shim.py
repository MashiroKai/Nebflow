#!/usr/bin/python3
"""Narrow ripgrep fixture for RgHelperSpec; reject unsupported invocations."""
import os
import sys

args = sys.argv[1:]
if args == ["--version"]:
    print("rg test shim")
elif args == ["--files", "--no-ignore", "--no-messages", "."]:
    if not (os.path.isfile("one.txt") and os.path.isfile("sub/two.txt")):
        sys.exit("missing cwd fixture")
    print("./one.txt\n./sub/two.txt")
elif args == ["--files", "--glob", "/**", "--sort=modified", "--color=never", "--no-ignore", "--no-messages", "."]:
    try:
        for directory, _, files in os.walk("."):
            for name in sorted(files):
                print(os.path.join(directory, name))
    except BrokenPipeError:
        os._exit(0)
else:
    sys.exit("unsupported rg-shim arguments: " + repr(args))
