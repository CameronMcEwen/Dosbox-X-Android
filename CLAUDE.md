# Dosbox-X-Android

> **New session?** Read `session.txt` in this repo root first — it has full context on what was built and why.

Fork of CrownParkComputing/Dosbox-X-Android. Prebuilt `.so` files are committed — no NDK compilation needed.

## Build an APK

Build is done via **GitHub Actions** — no local Java/NDK needed. Use `build.sh` at the repo root:

```bash
./build.sh                  # trigger → wait → download
./build.sh --download-only  # re-download last run without triggering
```

APK lands at `~/storage/downloads/app-debug.apk`. Open in a file manager to sideload.

**Do not run `./gradlew` locally** — Java is not installed in the Termux environment.

### Manual steps (equivalent to build.sh)
```bash
gh workflow run build-apk.yml --repo CameronMcEwen/Dosbox-X-Android
gh run watch --repo CameronMcEwen/Dosbox-X-Android   # Ctrl-C safe, won't cancel
gh run download --repo CameronMcEwen/Dosbox-X-Android \
  --name dosbox-x-debug --dir ~/storage/downloads
```

## Updating from upstream

```bash
git remote add upstream https://github.com/CrownParkComputing/Dosbox-X-Android.git
git fetch upstream
git merge upstream/main
git push
```

Then trigger a new build.
