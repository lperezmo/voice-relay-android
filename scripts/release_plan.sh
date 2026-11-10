#!/usr/bin/env bash
# semantic-release "verifyRelease" step, which also runs in --dry-run. It runs
# twice per release in release.yml:
#
#   RELEASE_PLAN=1      the dry run before the build: hand the version
#                       semantic-release would release to the workflow, so the
#                       APK can be tested and built as that version first.
#   EXPECTED_VERSION=X  the real run after the build: refuse to tag anything
#                       but the version that was built and tested.
#
# With neither set (a local run), it does nothing.
#
# Usage: release_plan.sh 0.4.0
set -euo pipefail

VERSION="${1:?usage: release_plan.sh VERSION}"

if [ -n "${EXPECTED_VERSION:-}" ] && [ "$VERSION" != "$EXPECTED_VERSION" ]; then
  echo "release_plan.sh: built and tested v$EXPECTED_VERSION, but this run would release v$VERSION; not releasing" >&2
  exit 1
fi

if [ "${RELEASE_PLAN:-}" = "1" ]; then
  : "${GITHUB_OUTPUT:?not running inside a GitHub Actions step}"
  {
    echo "release=true"
    echo "version=$VERSION"
    echo "version_code=$(scripts/version_code.sh "$VERSION")"
  } >> "$GITHUB_OUTPUT"
  echo "release_plan.sh: the next release is v$VERSION"
fi
