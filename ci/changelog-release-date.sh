#!/usr/bin/env bash
# Release-time CHANGELOG guard, run by exec-maven-plugin at `validate`.
#
# SNAPSHOT builds pass untouched. A release-version build - which is what
# `release:prepare` forks as its preparationGoals, BEFORE it commits or tags -
# requires the line `## [<version>] - <today>` in CHANGELOG.md, so a release can no
# longer be cut with its section still reading "unreleased" or carrying a stale
# date (1.4.2 was cut that way). On failure nothing has been committed or pushed:
# fix the heading, `git checkout -- pom.xml && mvn release:clean`, and cut again.
# A deliberate rebuild of an existing tag on a later day (e.g. a deferred
# `mvn clean deploy -Prelease` from the tag) passes -Dchangelog.gate.skip=true.
#
# Usage: changelog-release-date.sh <project.version> <path/to/CHANGELOG.md>
set -euo pipefail

version=$1
changelog=$2

case "$version" in
  *-SNAPSHOT) exit 0 ;;
esac

today=$(date +%F)
expected="## [${version}] - ${today}"
if ! grep -qxF "$expected" "$changelog"; then
  echo "CHANGELOG guard: releasing ${version} needs the heading line" >&2
  echo "  ${expected}" >&2
  echo "in ${changelog}; found:" >&2
  grep -nF "## [${version}]" "$changelog" >&2 || echo "  (no section for ${version})" >&2
  exit 1
fi
