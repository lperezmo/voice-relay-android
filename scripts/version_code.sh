#!/usr/bin/env bash
# Derive a versionCode from a semantic versionName.
#
# Android only installs an update in place when its versionCode is higher than
# the installed one, so versionCode must grow with every release. Deriving it
# from the version makes it a pure function of what semantic-release decided,
# identical no matter which workflow or machine built it.
#
#   MAJOR * 1000000 + MINOR * 10000 + PATCH * 100
#
#   0.4.0 -> 40000      0.4.1 -> 40100      1.0.0 -> 1000000
#
# The trailing two zeros leave room for hand-picked codes between patches, and
# every value is far above CI run-number codes and the default local build code
# (1), so a release always upgrades over a test build. Max is 2100000000.
#
# Usage: version_code.sh 0.4.0   (a leading v and a -prerelease suffix are ok)
set -euo pipefail

VERSION="${1:?usage: version_code.sh VERSION_NAME}"
VERSION="${VERSION#v}"
VERSION="${VERSION%%[-+]*}"

if [[ ! "$VERSION" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)$ ]]; then
  echo "version_code.sh: not a semantic version: $1" >&2
  exit 1
fi

MAJOR="${BASH_REMATCH[1]}"
MINOR="${BASH_REMATCH[2]}"
PATCH="${BASH_REMATCH[3]}"

if (( MINOR > 99 || PATCH > 99 )); then
  echo "version_code.sh: minor/patch above 99 would collide: $1" >&2
  exit 1
fi

echo $(( MAJOR * 1000000 + MINOR * 10000 + PATCH * 100 ))
