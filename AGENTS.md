# AGENTS.md

## Cursor Cloud specific instructions

NekoBox for Android (NB4A) is a native Android proxy app (sing-box based) with a Go
native core (`libcore`). There is **no automated test suite** in this repo (no
`app/src/test` or `app/src/androidTest`); the main automated check is Android lint.

### What the environment already has (baked into the VM snapshot)

The startup update script only regenerates `local.properties`. Everything below is
installed once during environment setup and persists in the snapshot, so you do NOT
need to reinstall it:

- Go 1.25 at `/usr/local/go` (symlinked into `/usr/local/bin`; overrides the system Go 1.22).
- Android SDK at `~/Android/Sdk`: platform `android-35`, `build-tools;35.0.1`,
  `platform-tools`, NDK `25.0.8775105`, `emulator`, and system images.
- gomobile tools `gomobile-matsuri` / `gobind-matsuri` in `~/go/bin` (symlinked into `/usr/local/bin`).
- Prebuilt native core `app/libs/libcore.aar` (gitignored) and geo assets under
  `app/src/main/assets/sing-box/` (gitignored).
- `sing-box` / `libneko` sources cloned at `/sing-box` and `/libneko` (required by
  `libcore/go.mod` replace directives that point at `../../sing-box` and `../../libneko`;
  because this repo is checked out at `/workspace`, those resolve to the filesystem root,
  which is only writable via sudo — the clones were created during setup).

### Build / run commands (standard flow)

- Build debug APK: `./gradlew app:assembleOssDebug` → APKs land in
  `app/build/outputs/apk/oss/debug/` (one per ABI: arm64-v8a, armeabi-v7a, x86, x86_64).
- Lint: `./gradlew app:lintOssDebug`. Note lint is configured extremely strictly in
  `buildSrc/src/main/kotlin/Helpers.kt` (`checkAllWarnings=true`, `warningsAsErrors=true`),
  so the full `lint` task currently reports pre-existing errors (e.g. `MissingSuperCall`)
  and exits non-zero. This is expected repo state — CI (`.github/workflows/*.yml`) never
  runs the full `lint` task, only `assemble*Release`/`bundlePlayRelease`. Reports are
  written to `app/build/lint.txt` / `app/build/lint.html`.
- `oss` is the simplest dev flavor. `play` needs private submodules; `fdroid`/`preview`
  are for packaging.

### Rebuilding the native core (only if libcore/ or build scripts change)

`libcore.aar` is heavy to build and is treated like a cached artifact (same as CI, which
caches it). Rebuild only when needed:

- `./run lib core` — checks out the pinned `sing-box`/`libneko` commits (from
  `buildScript/lib/core/get_source_env.sh`) into `/sing-box` and `/libneko`, then runs
  `gomobile-matsuri bind`. This requires `~/go/bin` (or `/usr/local/bin`) on PATH so the
  `gomobile-matsuri init` step in `libcore/init.sh` resolves.
- Refresh geo assets (network, hits GitHub releases): `./run init action gradle`.

### Running the app on an emulator (no KVM caveat — important)

This VM has **no `/dev/kvm`**, so the emulator runs under slow software CPU emulation
(QEMU TCG). Practical guidance learned during setup:

- Use the **AOSP `default` system image**, not `google_apis`. The Google image runs GMS /
  launcher / search services that thrash under TCG (load avg 20+ on 4 cores), causing
  `system_server` to die with `DeadSystemException` and services (`activity`, `input`,
  `package`) to flap. The AOSP image is much lighter and stays usable.
- Launch headless: `emulator -avd <name> -no-window -no-audio -no-snapshot -no-boot-anim
  -gpu swiftshader_indirect -accel off -qemu -smp 4`. There is a ready AVD named `nb4a_aosp`.
- Boot is slow (several minutes). After `sys.boot_completed=1`, still wait until the
  `package`/`activity` services actually respond (`adb shell pm list packages`) before
  installing/launching.
- Install: `adb install -r app/build/outputs/apk/oss/debug/NekoBox-1.4.2-x86_64-debug.apk`
  (package id `moe.nb4a.debug`, launcher `io.nekohasekai.sagernet.ui.MainActivity`).
- To create a proxy profile without fragile UI taps, use the import intent
  (MainActivity registers a VIEW filter for `ss://`, `socks://`, `vmess://`, `trojan://`, …).
  Quote the URI for the on-device shell so `#`/`@`/`=` survive:
  `adb shell "am start -a android.intent.action.VIEW -d 'ss://<base64(method:pass)>@host:port#Name' moe.nb4a.debug"`.
- Give the emulator generous idle time between UI actions; blind coordinate taps are
  unreliable while `system_server` is under load.

### Core version (sing-box / libcore)

- The core is pinned in `buildScript/lib/core/get_source_env.sh` to MatsuriDayo's
  `sing-box` `1.12.19-neko-1` and `libneko`; both are already the **tip** of the fork's
  maintenance branches (`/sing-box` `1.12.x`, `/libneko` default), so there is no newer
  in-lineage version to bump to. Moving to vanilla SagerNet `sing-box` 1.13.x is a
  fork migration (libcore's `box.go`/`box_include.go` depend on neko-specific APIs) and
  is not a drop-in bump.
- libcore now enables the **Tailscale** endpoint + MagicDNS transport (`box_include.go`).
  This pulls `github.com/sagernet/tailscale` into `libcore/go.mod`/`go.sum` and grows
  `libcore.aar` (~38MB → ~51MB). If you change `libcore/`, rebuild with `./run lib core`.
  Kotlin side emits a `tailscale` endpoint (`SingBoxOptions.Endpoint_TailscaleOptions`)
  whose `detour` is the main proxy tag (`TAG_PROXY`) so tailnet traffic first goes
  through the selected subscription node; settings live under global settings
  (`tailscale*` keys). Live tailnet connectivity needs a real Tailscale auth key.
