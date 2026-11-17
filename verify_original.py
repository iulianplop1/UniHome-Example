"""Compare original SEP3 files against the prior read-only SHA-256 inventory."""
import argparse
import hashlib
import json
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", type=Path, help="original SEP3 directory (read-only)")
    parser.add_argument("inventory", type=Path, help="prior inventory.json file")
    options = parser.parse_args()
    if not options.root.is_dir():
        parser.error("original SEP3 directory does not exist")
    expected = {
        row["path"]: row["sha"].lower()
        for row in json.loads(options.inventory.read_text(encoding="utf-8-sig"))
    }
    actual = {}
    for path in options.root.rglob("*"):
        if path.is_file():
            with path.open("rb") as f:
                actual[path.relative_to(options.root).as_posix()] = hashlib.file_digest(
                    f, "sha256"
                ).hexdigest()
    added = sorted(actual.keys() - expected.keys())
    removed = sorted(expected.keys() - actual.keys())
    changed = sorted(k for k in actual.keys() & expected.keys() if actual[k] != expected[k])
    print(
        "Scanned", len(actual), "original files:",
        len(added), "added;", len(removed), "removed;", len(changed), "modified."
    )
    if added or removed or changed:
        print("Added:", added)
        print("Removed:", removed)
        print("Modified:", changed)
        raise SystemExit(1)


if __name__ == "__main__":
    main()
