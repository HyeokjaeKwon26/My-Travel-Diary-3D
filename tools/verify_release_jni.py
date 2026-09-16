"""Check the native zstd field lookup contract in the optimized release mapping.

R8 cannot see GetFieldID calls inside the .so. A successful build alone missed
this regression: stripped srcPos/dstPos fields caused an ART native abort on
the first real Timeline import. Run after assembleRelease.
"""
from pathlib import Path
import re
import sys

mapping = Path(sys.argv[1] if len(sys.argv) > 1 else
               "app/build/outputs/mapping/release/mapping.txt").read_text(encoding="utf-8")
name = "com.github.luben.zstd.ZstdInputStreamNoFinalizer"
block = re.search(r"^" + re.escape(name) + r" -> " + re.escape(name)
                  + r":\n(.*?)(?=^[^\s#]|\Z)", mapping, re.M | re.S)
if block is None:
    raise SystemExit("Release JNI contract failed: zstd class name was changed or removed")
for field in ("srcPos", "dstPos"):
    if not re.search(r"^    long " + field + r" -> " + field + r"$", block[1], re.M):
        raise SystemExit(f"Release JNI contract failed: missing/renamed field {field}")
print("Release JNI contract passed: zstd stream class and native-accessed fields preserved")
