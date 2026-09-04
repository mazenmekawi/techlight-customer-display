#!/usr/bin/env python3
"""Small defensive source repair applied after the generated KDS Pro layer.

The repository builds its final Android sources through a chain of generators. This
script keeps the build deterministic and only repairs generated Java when a helper
was referenced but omitted or when a duplicate case label slipped into a switch.
"""
from pathlib import Path
import re

ROOT = Path('app/src/main/java/sa/techlight/customerdisplay')


def read(path: Path) -> str:
    if not path.exists():
        raise SystemExit(f'Missing generated source: {path}')
    return path.read_text(encoding='utf-8')


def write_if_changed(path: Path, before: str, after: str) -> None:
    if before != after:
        path.write_text(after, encoding='utf-8')
        print(f'Patched {path}')
    else:
        print(f'No patch needed for {path}')


def ensure_settings_action_helper() -> None:
    path = ROOT / 'KitchenProActivity.java'
    text = read(path)
    if 'addSettingsAction(' not in text or re.search(r'\b(?:private|public|protected)\s+void\s+addSettingsAction\s*\(', text):
        write_if_changed(path, text, text)
        return

    anchor = '    private TextView action('
    if anchor not in text:
        raise SystemExit('Cannot insert addSettingsAction helper: action() anchor missing')

    helper = '''    private void addSettingsAction(LinearLayout panel, String label,
                                           View.OnClickListener listener, boolean danger) {
        TextView button = action(label, false);
        if (danger) button.setTextColor(red);
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(46));
        params.setMargins(0, dp(8), 0, 0);
        panel.addView(button, params);
    }

'''
    patched = text.replace(anchor, helper + anchor, 1)
    write_if_changed(path, text, patched)


def repair_metadata_safe_helper() -> None:
    path = ROOT / 'KitchenProMetadataStore.java'
    text = read(path)
    patched = text
    # Generated code historically referenced safeClear() although safe() is the
    # canonical null-safe trimming helper in this class.
    if 'safeClear(' in patched and not re.search(r'\bString\s+safeClear\s*\(', patched):
        if re.search(r'\bString\s+safe\s*\(', patched):
            patched = patched.replace('safeClear(', 'safe(')
        else:
            anchor = patched.rfind('\n}')
            if anchor < 0:
                raise SystemExit('Cannot add safeClear helper: class terminator missing')
            helper = '\n    private static String safeClear(String value) {\n        return value == null ? "" : value.trim();\n    }\n'
            patched = patched[:anchor] + helper + patched[anchor:]
    write_if_changed(path, text, patched)


def dedupe_switch_cases(text: str) -> str:
    """Remove only repeated case expressions inside the same switch block."""
    lines = text.splitlines(keepends=True)
    out: list[str] = []
    # Stack entries: [brace depth immediately after switch opening, seen labels]
    switch_stack: list[tuple[int, set[str]]] = []
    depth = 0
    pending_switch = False
    case_re = re.compile(r'^\s*case\s+(.+?)\s*:\s*(?:.*)?$')

    for line in lines:
        if re.search(r'\bswitch\s*\(', line):
            pending_switch = True

        # A switch may open on the same line or the next line.
        opens = line.count('{')
        closes = line.count('}')
        next_depth = depth + opens - closes
        if pending_switch and opens:
            switch_stack.append((depth + 1, set()))
            pending_switch = False

        match = case_re.match(line.rstrip('\r\n'))
        if match and switch_stack:
            label = match.group(1).strip()
            start_depth, seen = switch_stack[-1]
            if label in seen:
                print(f'Removed duplicate switch case: {label}')
                depth = next_depth
                while switch_stack and depth < switch_stack[-1][0]:
                    switch_stack.pop()
                continue
            seen.add(label)

        out.append(line)
        depth = next_depth
        while switch_stack and depth < switch_stack[-1][0]:
            switch_stack.pop()

    return ''.join(out)


def repair_voice_parser() -> None:
    path = ROOT / 'KitchenVoiceIntentParser.java'
    text = read(path)
    patched = text
    if 'safe(input)' in patched and not re.search(r'\bString\s+safe\s*\(', patched):
        patched = patched.replace('safe(input)', '(input == null ? "" : input.trim())')
    patched = dedupe_switch_cases(patched)
    write_if_changed(path, text, patched)


def verify() -> None:
    activity = read(ROOT / 'KitchenProActivity.java')
    metadata = read(ROOT / 'KitchenProMetadataStore.java')
    parser = read(ROOT / 'KitchenVoiceIntentParser.java')

    if 'addSettingsAction(' in activity and not re.search(
            r'\b(?:private|public|protected)\s+void\s+addSettingsAction\s*\(', activity):
        raise SystemExit('addSettingsAction still unresolved')
    if 'safeClear(' in metadata and not re.search(r'\bString\s+safeClear\s*\(', metadata):
        raise SystemExit('safeClear still unresolved')
    if 'safe(input)' in parser and not re.search(r'\bString\s+safe\s*\(', parser):
        raise SystemExit('voice parser safe(input) still unresolved')
    print('Kitchen Pro generated-source verification passed')


ensure_settings_action_helper()
repair_metadata_safe_helper()
repair_voice_parser()
verify()
