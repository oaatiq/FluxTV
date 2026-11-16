#!/usr/bin/env python3
"""Writes the update.json a FluxTV reads to find, download and check a newer build of itself.

Every FluxTV looks at its update address (by default the newest GitHub release's update.json,
see app/build.gradle.kts) at start-up and every 6 hours. When the build number there is higher than
its own, it downloads the APK, checks it against the size and SHA-256 written here (and that it is
FluxTV, signed with the same key), and installs it.

    python3 tools/make_update_manifest.py app/build/outputs/apk/release/app-release.apk \\
        --url https://github.com/oaatiq/FluxTV/releases/download/v1.2.0/FluxTV-1.2.0.apk \\
        --out update.json

The build number and version name are read from the output-metadata.json Gradle writes next to the
APK, unless given. Without --url the APK is named relative to update.json (same folder), which suits
a TV-reachable web folder holding both files. Only the Python standard library is used.
"""

import argparse
import hashlib
import json
import os
import sys


def sha256_of(path):
    digest = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def read_metadata(path):
    """versionCode and versionName from Gradle's output-metadata.json, if it is there."""
    try:
        with open(path, encoding="utf-8") as f:
            data = json.load(f)
    except (OSError, ValueError):
        return None, None
    elements = data.get("elements") or [{}]
    return elements[0].get("versionCode"), elements[0].get("versionName")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("apk", help="the release APK")
    parser.add_argument("--url", help="where TVs download the APK (default: its file name, next to update.json)")
    parser.add_argument("--metadata", help="Gradle's output-metadata.json (default: next to the APK)")
    parser.add_argument("--version-code", type=int, help="build number (default: from the metadata)")
    parser.add_argument("--version-name", help="version name (default: from the metadata)")
    parser.add_argument("--min-sdk", type=int, default=24, help="lowest Android API level the build runs on (24)")
    parser.add_argument("--notes", default="", help="a line about the release, shown nowhere a Viewer looks")
    parser.add_argument("--out", default="update.json", help="where to write it (update.json)")
    args = parser.parse_args(argv)

    if not os.path.isfile(args.apk):
        parser.error("no such APK: %s" % args.apk)
    metadata = args.metadata or os.path.join(os.path.dirname(os.path.abspath(args.apk)), "output-metadata.json")
    meta_code, meta_name = read_metadata(metadata)
    version_code = args.version_code if args.version_code is not None else meta_code
    version_name = args.version_name if args.version_name is not None else meta_name
    if not isinstance(version_code, int) or version_code <= 0:
        parser.error("no build number: pass --version-code, or keep Gradle's output-metadata.json next to the APK")

    manifest = {
        "versionCode": version_code,
        "versionName": version_name or "",
        "apkUrl": args.url or os.path.basename(args.apk),
        "sha256": sha256_of(args.apk),
        "size": os.path.getsize(args.apk),
        "minSdk": args.min_sdk,
    }
    if args.notes:
        manifest["notes"] = args.notes

    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(manifest, f, indent=2)
        f.write("\n")
    json.dump(manifest, sys.stdout, indent=2)
    sys.stdout.write("\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
