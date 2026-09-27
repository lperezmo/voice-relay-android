"""Create the release signing keystore and a local signing.properties.

Run once per machine that does not have the keystore yet. Generates a random
password, never prints it, and writes:

  .signing/release.jks          the keystore (gitignored)
  signing.properties            store/key passwords + alias for local builds (gitignored)

CI gets the same values as GitHub secrets (UPLOAD_KEYSTORE_BASE64,
KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD). Losing this keystore means every
installed copy must be uninstalled before the next update, so back it up.

The certificate's distinguished name is public (anyone can read it from the
APK), so pick it yourself: --dname, or the KEYSTORE_DNAME environment variable.
It defaults to a neutral "CN=Android Release".

Usage: python scripts/make_keystore.py [--dname "CN=Your Name, O=Your Org"]
"""

import argparse
import os
import secrets
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
KEYSTORE = ROOT / ".signing" / "release.jks"
PROPS = ROOT / "signing.properties"
ALIAS = "phonewhisper"
DEFAULT_DNAME = "CN=Android Release"


def main() -> int:
    parser = argparse.ArgumentParser(description="Create the release signing keystore.")
    parser.add_argument(
        "--dname",
        default=os.environ.get("KEYSTORE_DNAME", DEFAULT_DNAME),
        help='certificate distinguished name (default: $KEYSTORE_DNAME or "%(default)s")',
    )
    args = parser.parse_args()
    if KEYSTORE.exists() or PROPS.exists():
        print("make_keystore.py: keystore or signing.properties already exists; not overwriting")
        return 1
    KEYSTORE.parent.mkdir(exist_ok=True)
    password = secrets.token_urlsafe(24)
    subprocess.run(
        [
            "keytool", "-genkeypair", "-v",
            "-keystore", str(KEYSTORE),
            "-storetype", "PKCS12",
            "-alias", ALIAS,
            "-keyalg", "RSA", "-keysize", "4096",
            "-validity", "10000",
            "-storepass", password,
            "-keypass", password,
            "-dname", args.dname,
        ],
        check=True,
        capture_output=True,
    )
    PROPS.write_text(
        f"storeFile=.signing/release.jks\nstorePassword={password}\nkeyAlias={ALIAS}\nkeyPassword={password}\n",
        encoding="utf-8",
    )
    print(f"make_keystore.py: wrote {KEYSTORE} and {PROPS.name}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
