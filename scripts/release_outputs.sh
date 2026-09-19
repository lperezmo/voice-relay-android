#!/usr/bin/env bash
# semantic-release "success" step: hand the version it just published back to
# the workflow, so the build/promote jobs know what to build and promote.
# semantic-release runs this as a child of the workflow step, so $GITHUB_OUTPUT
# points at that step's output file.
#
# Usage: release_outputs.sh 0.4.0
set -euo pipefail

VERSION="${1:?usage: release_outputs.sh VERSION}"
: "${GITHUB_OUTPUT:?not running inside a GitHub Actions step}"

{
  echo "published=true"
  echo "version=$VERSION"
  echo "version_code=$(scripts/version_code.sh "$VERSION")"
} >> "$GITHUB_OUTPUT"

echo "release_outputs.sh: published v$VERSION"
