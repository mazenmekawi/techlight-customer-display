#!/usr/bin/env python3
from pathlib import Path
import base64
import bz2
import hashlib
import json

PAYLOAD_SHA256 = "9b18b651c243ba03b60990beeb9f219c6004b5c72ded0ad5cf5d485f9fe37c3d"
parts_dir = Path(__file__).with_name("kds_pro_payload")
payload = "".join(path.read_text(encoding="ascii") for path in sorted(parts_dir.glob("payload_*.txt")))
raw = bz2.decompress(base64.b64decode(payload.encode("ascii")))
actual = hashlib.sha256(raw).hexdigest()
if actual != PAYLOAD_SHA256:
    raise SystemExit(f"Kitchen Pro payload checksum mismatch: {actual}")
files = json.loads(raw.decode("utf-8"))
for name, content in files.items():
    path = Path(name)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")
print(f"Applied {len(files)} Kitchen Pro source files (sha256={actual})")
