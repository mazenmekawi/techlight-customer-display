from __future__ import annotations

import base64
import hashlib
import io
import tarfile
from pathlib import Path

PAYLOAD_DIR = Path('scripts/kds_stable_payload/parts')
DESTINATION = Path('app/src/main/java/sa/techlight/customerdisplay')
EXPECTED = {
    'KitchenStableActivity.java': 'e10d2d8c8561147fb7f637acec490ace3b61b13b24dabbefb1f02e066ff2fc6e',
    'KitchenStableMetaStore.java': '012c6becfffeefb512c53c20fb30928f71abbe30c212d454d4a262d6b6c44569',
}

parts = sorted(PAYLOAD_DIR.glob('part_*.txt'))
if not parts:
    raise SystemExit(f'Missing stable source payload parts: {PAYLOAD_DIR}')

try:
    encoded = ''.join(''.join(part.read_text(encoding='ascii').split()) for part in parts)
    archive_bytes = base64.b64decode(encoded, validate=True)
except Exception as exc:
    raise SystemExit(f'Invalid stable source payload: {exc}') from exc

DESTINATION.mkdir(parents=True, exist_ok=True)
seen: set[str] = set()
with tarfile.open(fileobj=io.BytesIO(archive_bytes), mode='r:gz') as archive:
    for member in archive.getmembers():
        if not member.isfile() or member.name not in EXPECTED:
            raise SystemExit(f'Unexpected payload member: {member.name}')
        source = archive.extractfile(member)
        if source is None:
            raise SystemExit(f'Could not read payload member: {member.name}')
        content = source.read()
        digest = hashlib.sha256(content).hexdigest()
        if digest != EXPECTED[member.name]:
            raise SystemExit(f'Hash mismatch for {member.name}: {digest}')
        (DESTINATION / member.name).write_bytes(content)
        seen.add(member.name)

missing = sorted(set(EXPECTED) - seen)
if missing:
    raise SystemExit('Stable payload is incomplete: ' + ', '.join(missing))

print('Applied verified TechLight Kitchen Stable 6.8 Java sources')
