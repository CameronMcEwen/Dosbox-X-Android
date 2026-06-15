# Native core (DOSBox-X) — track upstream, patch on top, rebuild

The Android app loads a native shared library, `libmain.so`, which is **DOSBox-X**
cross-compiled for Android. This directory tracks that core as an upstream
dependency so it can be kept current and rebuilt, with our changes layered on top
as patches.

## Layout

```
native/
  dosbox-x/         git submodule → joncampbell123/dosbox-x (kept at a release tag)
  patches/          our source changes, as *.patch (applied on top of upstream)
  build-android.sh  pull latest stable tag → apply patches → cross-build → libmain.so
  regen-patch.sh    capture submodule edits back into patches/
  build/            scratch build trees + toolchain shims (git-ignored)
```

## The pipeline

`build-android.sh` is the whole "always build the latest, with our patches" flow:

1. **Track upstream.** Fetches tags and checks the submodule out to the latest
   stable release tag (`dosbox-x-vYYYY.MM.DD`). Override with `DBX_REF=<tag>` to
   pin, or `DBX_NO_UPDATE=1` to build the current checkout untouched.
2. **Patch on top.** Cleans the tree and applies `patches/*.patch` in order. If a
   patch no longer applies (upstream moved under it) the build **stops** and
   prints how to refresh it — that is the "flag the issue" step.
3. **Cross-build, hermetically.** Configures + compiles for each ABI with the NDK,
   hiding host pkg-config/libraries so autotools can't false-positive on the dev
   box. Builds against the `libSDL2.so` / `libpng16.so` already in `jniLibs`.
4. **Link `libmain.so`.** Reuses DOSBox-X's own executable link command but emits
   a shared library that exports `SDL_main` (the app's `SDLActivity` `dlopen`s
   `libmain.so` and calls `SDL_main`).
5. **Verify + install.** Checks the artifact exports `SDL_main` and pulls in no
   host-only libraries, then drops it into `app/src/main/jniLibs/<abi>/libmain.so`.

```sh
export ANDROID_NDK=$HOME/Android/Sdk/ndk/<version>   # 28.x tested
./native/build-android.sh                  # latest stable tag, arm64-v8a + x86_64
DBX_ABIS="arm64-v8a" ./native/build-android.sh        # one ABI
DBX_REF=dosbox-x-v2026.06.02 ./native/build-android.sh  # pin a release
```

Host tools needed: `autoconf`, `automake`, `nasm`, plus the NDK.

## Bumping upstream

Just run the script — it moves the submodule to the newest release tag for you.
After a successful build, commit the three things that define the build:

```sh
git add native/dosbox-x                    # new submodule ref (the tag)
git add native/patches                     # any patches you refreshed/added
git add app/src/main/jniLibs/*/libmain.so  # the rebuilt binaries
git commit -m "Bump DOSBox-X to <tag>; rebuild libmain.so"
```

If a build surfaces a new Android incompatibility, fix the source under
`native/dosbox-x/`, capture it with `regen-patch.sh`, and re-run — the next
upstream bump carries the fix forward automatically.

## Why patches vs flags

Disabling a feature upstream already gates (GameLink, OpenGL desktop, ALSA, X11,
SDL_net, libslirp, fluidsynth) is done with a `--disable-*` **configure flag** in
`build-android.sh` — flags never need refreshing. Only genuine source changes
(the Android configure target; the SDL1 CD-ROM include) live in `patches/`. See
`patches/README.md` for the current set.

## Licensing

DOSBox-X is GPL-2.0; the submodule and any patches inherit that. See `LICENSE`.
