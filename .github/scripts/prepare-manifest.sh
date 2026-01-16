#!/bin/sh

# Example usage:
# ./.github/scripts/prepare-manifest.sh 1.0.0 123 release-notes.txt

set -e

echo "Preparing manifest..."

cp manifest-template.json manifest.json

# replace __VERSION_NUMBER_PLACEHOLDER__ with the actual version from input
VERSION=${1}
sed -i '' "s/__VERSION_NUMBER_PLACEHOLDER__/${VERSION}/g" manifest.json

# __VERSION_CODE_PLACEHOLDER__
sed -i '' "s/__VERSION_CODE_PLACEHOLDER__/${2}/g" manifest.json

# __RELEASE_NOTES_PLACEHOLDER__
RELEASE_NOTES=$(cat "${3}" | sed 's/$/\\n/' | tr -d '\n' | sed 's/$/\\n/')
sed -i '' "s|__RELEASE_NOTES_PLACEHOLDER__|${RELEASE_NOTES%\\n}|g" manifest.json

echo "Manifest prepared."