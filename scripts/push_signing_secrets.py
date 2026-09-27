"""Copy the local release signing config into GitHub Actions secrets.

Reads .signing/release.jks and signing.properties (both gitignored) and sets
UPLOAD_KEYSTORE_BASE64, KEYSTORE_PASSWORD, KEY_ALIAS and KEY_PASSWORD on the
repo with `gh secret set`, passing each value on stdin. Nothing is printed.

Usage: python scripts/push_signing_secrets.py owner/repo

The repository is required, so the secrets never land on a repo by accident
(a fork's checkout can resolve to its upstream).
"""

import base64
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def main() -> int:
    if len(sys.argv) != 2 or "/" not in sys.argv[1]:
        print("usage: python scripts/push_signing_secrets.py owner/repo", file=sys.stderr)
        return 2
    repo = sys.argv[1]
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
