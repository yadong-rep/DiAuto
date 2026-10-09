#!/usr/bin/env python3
"""Check every home layout supplies the views used by HomeFragment."""
import re
import xml.etree.ElementTree as ET
from pathlib import Path

root = Path(__file__).resolve().parents[1]
fragment = root / "app/src/main/java/com/andrerinas/openheadunit/main/HomeFragment.kt"
required = set(re.findall(r"view\.findViewById<[^>]+>\(R\.id\.(\w+)\)", fragment.read_text()))
layouts = sorted((root / "app/src/main/res").glob("layout*/fragment_home.xml"))
assert layouts, "No home layouts found"
for layout in layouts:
    tree = ET.parse(layout)
    ids = {node.get("{http://schemas.android.com/apk/res/android}id", "").split("/")[-1]
           for node in tree.iter()}
    missing = required - ids
    assert not missing, f"{layout.relative_to(root)} missing views: {sorted(missing)}"
    print(f"PASS {layout.relative_to(root)}: {len(required)} required views")
