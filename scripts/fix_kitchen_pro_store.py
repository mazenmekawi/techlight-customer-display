#!/usr/bin/env python3
"""Repair KitchenOrderStoreV2 capabilities required by generated Kitchen Pro sources.

This runs after the Pro payload is applied. It is intentionally idempotent so CI can
execute it on every build without changing already-correct source files.
"""
from pathlib import Path
import re

PATH = Path("app/src/main/java/sa/techlight/customerdisplay/KitchenOrderStoreV2.java")

if not PATH.exists():
    raise SystemExit(f"Missing generated store source: {PATH}")

text = PATH.read_text(encoding="utf-8")
signature = re.compile(r"\bpublic\s+synchronized\s+int\s+trimHistoryTo\s*\(\s*int\s+\w+\s*\)")

if not signature.search(text):
    anchor = "    public synchronized void clearActive()"
    if anchor not in text:
        raise SystemExit("Cannot insert trimHistoryTo: clearActive anchor missing")

    method = '''    /**
     * Keep only the newest {@code keep} completed tickets and return how many
     * older history entries were removed. Active orders are never touched.
     */
    public synchronized int trimHistoryTo(int keep) {
        int target = Math.max(0, keep);
        int removed = 0;
        while (history.size() > target) {
            history.remove(history.size() - 1);
            removed++;
        }
        if (removed > 0) persist();
        return removed;
    }

'''
    text = text.replace(anchor, method + anchor, 1)
    PATH.write_text(text, encoding="utf-8")
    print("Added KitchenOrderStoreV2.trimHistoryTo(int)")
else:
    print("KitchenOrderStoreV2.trimHistoryTo(int) already present")

verified = PATH.read_text(encoding="utf-8")
if not signature.search(verified):
    raise SystemExit("trimHistoryTo verification failed")
if "while (history.size() > target)" not in verified:
    raise SystemExit("trimHistoryTo retention logic missing")
if "history.remove(history.size() - 1)" not in verified:
    raise SystemExit("trimHistoryTo must remove oldest history entries")

print("Kitchen Pro store verification passed")
