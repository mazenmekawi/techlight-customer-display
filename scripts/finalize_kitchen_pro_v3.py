#!/usr/bin/env python3
"""Apply the TechLight KDS Pro 7.1 operational post-generation patch."""
from pathlib import Path
import base64
import bz2
import hashlib

EXPECTED_SHA256 = "9939154e26c8812acfe7d6626d1b3bc0a4bfb8b8fe86d5f906923e91d9c0a980"
parts_dir = Path(__file__).with_name("kds_pro_v3_payload")
encoded = "".join(path.read_text(encoding="ascii").strip()
                  for path in sorted(parts_dir.glob("payload_*.txt")))
source = bz2.decompress(base64.b64decode(encoded.encode("ascii")))
actual = hashlib.sha256(source).hexdigest()
if actual != EXPECTED_SHA256:
    raise SystemExit(f"KDS Pro 7.1 patch checksum mismatch: {actual}")
exec(compile(source, str(parts_dir), "exec"), {"__name__": "__main__"})
