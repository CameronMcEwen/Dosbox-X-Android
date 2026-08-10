#!/usr/bin/env bash
set -euo pipefail

REPO="CameronMcEwen/Dosbox-X-Android"
WORKFLOW="build-apk.yml"
ARTIFACT="dosbox-x-debug"
OUT_DIR="$HOME/storage/downloads"

echo "Triggering build..."
gh workflow run "$WORKFLOW" --repo "$REPO"

# Give GitHub a moment to register the run before we try to watch it
sleep 3

RUN_ID=$(gh run list --repo "$REPO" --workflow "$WORKFLOW" --limit 1 --json databaseId --jq '.[0].databaseId')
echo "Run ID: $RUN_ID"

echo "Waiting for build to complete..."
gh run watch "$RUN_ID" --repo "$REPO"

echo "Downloading APK to $OUT_DIR..."
gh run download "$RUN_ID" --repo "$REPO" --name "$ARTIFACT" --dir "$OUT_DIR"

echo "Done: $OUT_DIR/app-debug.apk"
