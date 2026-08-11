#!/usr/bin/env bash
set -euo pipefail

REPO="CameronMcEwen/Dosbox-X-Android"
WORKFLOW="build-apk.yml"
ARTIFACT="dosbox-x-debug"
OUT_DIR="$HOME/storage/downloads"
DOWNLOAD_ONLY=false

for arg in "$@"; do
    case "$arg" in
        -d|--download-only) DOWNLOAD_ONLY=true ;;
        *) echo "Unknown flag: $arg"; exit 1 ;;
    esac
done

if [ "$DOWNLOAD_ONLY" = false ]; then
    echo "Pushing to remote..."
    git push
    echo "Triggering build..."
    gh workflow run "$WORKFLOW" --repo "$REPO"

    # Give GitHub a moment to register the run before we try to watch it
    sleep 3
fi

RUN_ID=$(gh run list --repo "$REPO" --workflow "$WORKFLOW" --limit 1 --json databaseId --jq '.[0].databaseId')
echo "Run ID: $RUN_ID"

if [ "$DOWNLOAD_ONLY" = false ]; then
    echo "Waiting for build to complete..."
    gh run watch "$RUN_ID" --repo "$REPO"
fi

echo "Downloading APK to $OUT_DIR..."
rm -f "$OUT_DIR/app-debug.apk"
gh run download "$RUN_ID" --repo "$REPO" --name "$ARTIFACT" --dir "$OUT_DIR"

echo "Done: $OUT_DIR/app-debug.apk"
