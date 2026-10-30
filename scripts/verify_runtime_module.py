#!/usr/bin/env python3
"""Verify that an APK is a standalone runtime module, not a repacked host APK."""
from pathlib import Path
import argparse
import re
import sys
import zipfile

TARGET_PACKAGE = "cn.ninebot.ninebot"
MODULE_PACKAGE = "dev.ichinomiya.ninebotenhance"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    args = parser.parse_args()
    if not args.apk.is_file():
        parser.error(f"APK not found: {args.apk}")

    with zipfile.ZipFile(args.apk) as apk:
        names = set(apk.namelist())
        required = {
            "META-INF/xposed/module.prop",
            "META-INF/xposed/scope.list",
            "META-INF/xposed/java_init.list",
        }
        missing = required - names
        if missing:
            raise SystemExit("FAIL: missing runtime module metadata: " + ", ".join(sorted(missing)))

        scope = apk.read("META-INF/xposed/scope.list").decode("utf-8", "replace").split()
        if TARGET_PACKAGE not in scope:
            raise SystemExit("FAIL: Ninebot host package is not in the module scope")

        init = apk.read("META-INF/xposed/java_init.list").decode("utf-8", "replace").strip()
        if init != MODULE_PACKAGE + ".hook.MirrorModule":
            raise SystemExit("FAIL: unexpected module entry point")

        module_classes = [name for name in names if name.startswith("classes") and name.endswith(".dex")]
        if not module_classes:
            raise SystemExit("FAIL: no DEX files found")
        # A standalone module may refer to the host package as a hook target, but must not
        # contain host APK DEX/resources or an embedded host manifest.
        embedded_host = [name for name in names if name.startswith("cn/ninebot/")]
        if embedded_host:
            raise SystemExit("FAIL: host classes are embedded; this is not a standalone module")
        if "AndroidManifest.xml" not in names:
            raise SystemExit("FAIL: Android manifest is missing")

        prop = apk.read("META-INF/xposed/module.prop").decode("utf-8", "replace")
        if not re.search(r"(?m)^staticScope=true\s*$", prop):
            raise SystemExit("FAIL: staticScope=true is required")

    print(f"PASS: standalone runtime module: {args.apk}")
    print(f"PASS: scope includes {TARGET_PACKAGE}; entry={init}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
