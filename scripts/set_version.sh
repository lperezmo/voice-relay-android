#!/usr/bin/env bash
# semantic-release "prepare" step: stamp the version it decided on into the
# files that carry it. semantic-release owns the number; nothing in this repo
# should ever be edited by hand to bump a version.
#
# Usage: set_version.sh 0.4.0
set -euo pipefail

VERSION="${1:?usage: set_version.sh VERSION}"
if [[ ! "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+([-+][0-9A-Za-z.-]+)?$ ]]; then
  echo "set_version.sh: not a semantic version: $VERSION" >&2
  exit 1
fi

GRADLE=app/build.gradle.kts
README=README.md

# The single source of truth the AAB is built from.
perl -pi -e "s/^val appVersionName = \".*\"\$/val appVersionName = \"$VERSION\"/" "$GRADLE"
grep -q "^val appVersionName = \"$VERSION\"\$" "$GRADLE" || {
  echo "set_version.sh: failed to set appVersionName in $GRADLE" >&2
  exit 1
}

# Keep the prose in sync so the README never advertises a stale version.
perl -pi -e "s/\`versionName\` is \`[0-9][^\`]*\`/\`versionName\` is \`$VERSION\`/" "$README"

echo "set_version.sh: version is now $VERSION"
