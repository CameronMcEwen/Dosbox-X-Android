# Dosbox-X-Android

> **New session?** Read `session.txt` in this repo root first — it has full context on what was built and why.

Fork of CrownParkComputing/Dosbox-X-Android. Prebuilt `.so` files are committed — no NDK compilation needed.

## Build an APK

### 1. Trigger the build
```bash
gh workflow run build-apk.yml --repo CameronMcEwen/Dosbox-X-Android
```

### 2. Wait for it to complete
```bash
gh run watch --repo CameronMcEwen/Dosbox-X-Android
```
Takes ~5–10 minutes. Ctrl-C is safe — it won't cancel the run.

### 3. Download the APK
```bash
gh run download --repo CameronMcEwen/Dosbox-X-Android \
  --name dosbox-x-debug \
  --dir ~/storage/downloads
```
APK lands at `~/storage/downloads/app-debug.apk`. Open it in a file manager to sideload.

## Updating from upstream

```bash
git remote add upstream https://github.com/CrownParkComputing/Dosbox-X-Android.git
git fetch upstream
git merge upstream/main
git push
```

Then trigger a new build.
