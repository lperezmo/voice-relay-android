"""Copy the local release signing config into GitHub Actions secrets.

Reads .signing/release.jks and signing.properties (both gitignored) and sets
UPLOAD_KEYSTORE_BASE64, KEYSTORE_PASSWORD, KEY_ALIAS and KEY_PASSWORD on the
repo with `gh secret set`, passing each value on stdin. Nothing is printed.

Usage: python scripts/push_signing_secrets.py [owner/repo]
"""

import base64
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def main() -> int:
    repo = sys.argv[1] if len(sys.argv) > 1 else "lperezmo/phone-whisper-android"
    props = {}
    for line in (ROOT / "signing.properties").read_text(encoding="utf-8").splitlines():
        if "=" in line:
            key, value = line.split("=", 1)
            props[key.strip()] = value.strip()
    keystore = ROOT / props["storeFile"]
    secrets = {
        "UPLOAD_KEYSTORE_BASE64": base64.b64encode(keystore.read_bytes()).decode("ascii"),
        "KEYSTORE_PASSWORD": props["storePassword"],
        "KEY_ALIAS": props["keyAlias"],
        "KEY_PASSWORD": props["keyPassword"],
    }
    for name, value in secrets.items():
        subprocess.run(["gh", "secret", "set", name, "-R", repo], input=value, text=True, check=True, capture_output=True)
        print(f"push_signing_secrets.py: set {name}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
