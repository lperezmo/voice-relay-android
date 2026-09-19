"""Create the release signing keystore and a local signing.properties.

Run once per machine that does not have the keystore yet. Generates a random
password, never prints it, and writes:

  .signing/release.jks          the keystore (gitignored)
  signing.properties            store/key passwords + alias for local builds (gitignored)

CI gets the same values as GitHub secrets (UPLOAD_KEYSTORE_BASE64,
KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD). Losing this keystore means every
installed copy must be uninstalled before the next update, so back it up.
"""

import secrets
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
KEYSTORE = ROOT / ".signing" / "release.jks"
PROPS = ROOT / "signing.properties"
ALIAS = "phonewhisper"


def main() -> int:
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
            "-dname", "CN=Phone Whisper Android, O=lperezmo",
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
