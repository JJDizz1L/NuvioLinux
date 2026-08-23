<div align="center">

  <img src="composeApp/src/commonMain/composeResources/drawable/app_logo_wordmark.png" alt="Nuvio Linux" width="300" />
  <br />
  <br />

  [![Contributors][contributors-shield]][contributors-url]
  [![Forks][forks-shield]][forks-url]
  [![Stargazers][stars-shield]][stars-url]
  [![Issues][issues-shield]][issues-url]
  [![License][license-shield]][license-url]

  <p>
    A Linux-first desktop media app.
    <br />
    Browse, organize, and play media from sources you add.
  </p>

</div>

> ### ⚠️ Hard Fork — Linux Only · Alpha Software — Testers Only
>
> Nuvio Linux is a hard fork of [Nuvio Desktop](https://github.com/NuvioMedia/NuvioDesktop),
> focused exclusively on Linux. The macOS and Windows code paths have been removed —
> if you need those platforms, use the upstream project instead.
>
> This is alpha software intended only for testers. It is under active development and
> not suitable for daily use: expect breaking changes with every update. Features,
> settings, stored data, and compatibility may change or stop working without notice.
> Do not rely on this build as your primary media app, and please report any issues you
> encounter during testing.

## About

Nuvio Linux is a media client for browsing metadata, managing collections and watch progress, downloading media, and playing streams from user-installed extensions or user-provided sources.

It keeps the upstream client codebase — including feature ports from newer upstream releases — while replacing the desktop playback stack with a native Linux player.

## What's Different From Upstream

- Native Linux playback via MPV (libmpv). A C++/JNI bridge embeds mpv through its render API: video is rendered offscreen into an FBO with a vendor-aware GL context (GLX on NVIDIA, EGL on Mesa) and written **directly into the Compose scene's pixel memory** — no intermediate copies, UI and video both GPU-accelerated on every vendor, frame delivery driven by mpv itself (~75 timer wakeups/s per playing surface instead of ~1000).
- Hardware acceleration with zero-copy decode: VA-API on AMD/Intel and NVDEC on NVIDIA, chosen by the app's decoder setting, with automatic fallback to copy-mode or software decode when a GPU or codec doesn't cooperate. AV1 hardware decoding needs an RTX 3000+ (NVIDIA), RX 6000+/Ryzen 6000+ (AMD), or 11th-gen Core+/Arc (Intel); H.264/HEVC work on any NVDEC card (GTX 750+) and most VA-API cards. Hardware without an AV1 decoder still plays AV1 on the CPU. Tested on Radeon RX 9070 (Mesa 26.2.1) and GeForce RTX 3070 (driver 610.57), including 4K HDR10.
- HDR support through your `mpv.conf`, which the player loads wholesale — tone-mapping, `target-peak`, profiles all apply as-is. (Not available in the Flatpak; see below.)
- Discord Rich Presence under Settings → Integrations → Discord Rich Presence.
- Trakt & Simkl tracking under Settings → Integrations → Tracking: library, watch progress, watched history and scrobbling sync across devices, with manual "Sync now" and automatic refresh.
- Linux distribution done properly: a first-class Arch package plus Fedora/RPM, Debian, AppImage and Flatpak artifacts, all compiled for generic x86-64 with a bundled JRE (no system Java required).

## Installation

Packages are attached to each [release](https://github.com/JJDizz1L/NuvioLinux/releases). Everything except the Flatpak requires a system `mpv`; every package bundles its own JRE and runs on any x86-64 CPU (no AVX2/AVX-512 requirement).

### Arch Linux

The primary distribution. Install the prebuilt binary from the AUR (no compile):

```bash
yay -S nuvio-linux-bin
```

or build the VCS package from source (tracks the `dev` branch):

```bash
yay -S nuvio-linux-git
```

or build locally:

```bash
git clone -b dev https://github.com/JJDizz1L/NuvioLinux.git
cd NuvioLinux/dist/arch
makepkg -si
```

> To keep the bundled JRE portable, build with a baseline x86-64 JDK (e.g. Eclipse Temurin 25) — distro JDKs compiled with `-march=native` / `-march=x86-64-v3/v4` produce a runtime that only runs on those CPUs. Point `JAVA_HOME` at a generic JDK before running `makepkg`.

### Fedora / RHEL

```bash
sudo dnf install ./nuvio-linux-*.x86_64.rpm
```

### Debian / Ubuntu

```bash
sudo apt install ./nuvio-linux_*_amd64.deb
```

Installs to `/opt/nuvio-linux`.

### AppImage

```bash
chmod +x nuvio-linux-*-x86_64.AppImage
./nuvio-linux-*-x86_64.AppImage
```

Requires system `mpv` and `libfuse2` (or run with `--appimage-extract-and-run` on FUSE-less systems).

### Flatpak

```bash
flatpak install --user ./nuvio-linux-*.flatpak
flatpak run io.github.jjdizz1l.NuvioLinux
```

The Flatpak bundles libmpv built from source — no system `mpv` needed — and includes hardware decode on every vendor via VA-API: AMD/Intel through the runtime's VA-API drivers, NVIDIA through the bundled nvidia-vaapi-driver shim (VA-API → NVDEC translation). Your `~/.config/mpv/mpv.conf` is honored read-only. P2P/TorrServer works via the bundled binary. Updates come through the bundle (the in-app updater is disabled in the sandbox); a toast in the app points to new releases on GitHub.

> **Flatpak NVIDIA requirement:** hardware decoding on NVIDIA needs the `org.freedesktop.Platform.VAAPI.nvidia` extension (branch `25.08`). Flatpak auto-installs it when an NVIDIA GPU is detected; if it's missing:
>
> ```bash
> flatpak install flathub org.freedesktop.Platform.VAAPI.nvidia//25.08
> ```
>
> Outside the sandbox the same driver ships as `nvidia-vaapi-driver` (Arch AUR, Debian/Ubuntu) or `libva-nvidia-driver` (Fedora RPM Fusion) — but native packages don't need it, they use NVDEC directly.

> **HDR limitation:** HDR tone-mapping/passthrough is not available in the Flatpak — the sandbox can't reach the display's HDR output path. Use a native package for HDR content.

### Launching

Launch from your app menu, or:

```bash
nuvio-linux
```

## Troubleshooting

**Using a Nuvio online account? Having login/library/sync weirdness?**
Please **log out and sign back in first** — it rebuilds your credentials and re-syncs library state, and resolves the majority of account-related reports on its own (**Settings → Account → Account and Sync Status → Sign Out**).

**If playback misbehaves, give us data.** Launch from a terminal with debug logging:

```bash
# Native packages (Arch/deb/rpm/AppImage):
NUVIO_MPV_DEBUG=1 nuvio-linux

# Flatpak:
flatpak run --env=NUVIO_MPV_DEBUG=1 io.github.jjdizz1l.NuvioLinux
```

Extra knobs for specific problems:

| Flag | Use for |
|---|---|
| `NUVIO_MPV_DEBUG=1` | Everything playback: renderer init, hwdec status, frame cadence (`render cadence:`), readback health (`readback stats[...]`) |
| `NUVIO_READBACK=sync` | Stutter/judder triage — forces the deterministic path |
| `NUVIO_MPV_HWDEC=auto-copy` | Suspected hardware-decode issues |
| `NUVIO_MPV_NO_AUTOFALLBACK=1` | Keeps decode from auto-switching, so we can see the raw failure |
| `NUVIO_SW_RENDER=1` | Forces the software render path + copy-back decoding (same as Settings → Playback → Decoder → Compatibility Rendering) |
| `NUVIO_TRAILER_DEBUG=1` | Trailer extraction problems |
| `NUVIO_WINDOW_DEBUG=1` | Window sizing/fullscreen issues |
| `nuvio-linux --nvidia-diag` | Prints an NVIDIA driver/GPU diagnostic block and exits |

**Reading the debug output:** `decoder: attached:` / `decoder: changed:` lines show which hardware decoder actually opened for each file (and any mid-stream switch); the per-second `render cadence:` line carries measured fps, bitrate, dropped/mistimed/delayed frames; `PlayerTracks` lines show whether your preferred audio/subtitle language was applied and why not if it wasn't.

Note: if your own `~/.config/mpv/mpv.conf` sets `alang=…`, mpv's default selection overrides language picking at load time — Nuvio still applies your preference afterwards.

**Please report issues on GitHub — with data from the application, not just sentences describing the problem.** A report we can act on includes: the terminal output from the commands above (especially any `[mpv/...]`, `readback stats`, or `render cadence:` lines), your GPU + driver (`nvidia-smi` or `lspci`), which package format you run, your desktop environment, and exact steps to reproduce. Reports with logs get fixed; reports without them usually can't be reproduced.

### Tiling window managers (niri, sway, i3, bspwm, …)

Tiling compositors don't wrap windows the way AWT expects, which can make the app render into a small box in the corner. The app detects non-reparenting WMs automatically and enables the JDK's workaround (`_JAVA_AWT_WM_NONREPARENTING=1`) — no action needed. To override detection:

```bash
_JAVA_AWT_WM_NONREPARENTING=1 nuvio-linux
```

### NixOS (AppImage via steam-run): playback stutter / software GL

`steam-run`'s bundled graphics libraries can shadow your real GPU driver, silently falling back to software rendering. Point the loader at the system's OpenGL libraries:

```bash
LD_LIBRARY_PATH=/run/opengl-driver/lib:$LD_LIBRARY_PATH ./nuvio-linux-<version>-x86_64.AppImage
```

## Development

```bash
git clone -b dev https://github.com/JJDizz1L/NuvioLinux.git
cd NuvioLinux
./gradlew run          # run from source
```

Packaging (each writes its artifact under `composeApp/build/compose/binaries/main-release/` unless noted):

```bash
./gradlew :composeApp:packageReleaseDistributionForCurrentOS   # host default
./gradlew :composeApp:packageReleaseDeb                        # .deb (needs dpkg-deb)
./dist/rpm/build-rpm.sh                                        # .rpm inside a Fedora container (needs Docker)
./dist/appimage/build-appimage.sh                              # .AppImage (needs appimagetool)
./dist/flatpak/build-flatpak.sh                                # .flatpak (builds libmpv + deps from source)
```

Set `NUVIO_PACKAGE_RELEASE=<n>` to stamp the shared package release counter across formats.

This fork does not produce Windows or macOS builds.

## Project Structure

- `composeApp/` contains the app code.
- `composeApp/src/commonMain/` contains shared UI, features, repositories, and platform-agnostic logic.
- `composeApp/src/desktopMain/` contains desktop-specific integrations.
- `composeApp/src/desktopMain/native/` contains the C++/libmpv playback bridge.
- `dist/arch/`, `dist/rpm/`, `dist/deb/`, `dist/appimage/`, `dist/flatpak/` contain the per-distribution packaging scripts and configs.
- `composeApp/Configuration/DesktopVersion.properties` contains the desktop release version and build code.

## Versioning

Desktop versions live in `composeApp/Configuration/DesktopVersion.properties` — the single source of truth every artifact version derives from. The version is kept aligned with upstream's desktop version; when upstream bumps, bump to match:

```properties
VERSION_NAME=0.1.20-alpha
VERSION_CODE=20
```

Use the helper when changing desktop release versions:

```bash
./scripts/set-version.sh --desktop 0.1.20-alpha --desktop-code 20
./scripts/set-version.sh --show
```

Rebuilds of the same app version bump one shared release counter across all formats (Arch `pkgrel`, RPM `Release`, and the `-<release>` suffix everywhere else). For example, release 4 of `0.1.20-alpha` ships as:

```
nuvio-linux-0.1.20alpha-4-x86_64.pkg.tar.zst
nuvio-linux-0.1.20alpha-4.x86_64.rpm
nuvio-linux_0.1.20-alpha-4_amd64.deb
nuvio-linux-0.1.20-alpha-4-x86_64.AppImage
nuvio-linux-0.1.20-alpha-4.flatpak
```

Package-only releases tag as `v0.1.20-alpha-<release>`; app-version releases tag as `v<VERSION_NAME>`.

## Legal & DMCA

Nuvio Linux functions solely as a client-side interface for browsing metadata and playing media provided by user-installed extensions and/or user-provided sources. It is intended for content the user owns or is otherwise authorized to access.

Nuvio Linux is not affiliated with any third-party extensions, catalogs, sources, or content providers. It does not host, store, or distribute any media content.

For comprehensive legal information, including our full disclaimer, third-party extension policy, and DMCA/Copyright information, please visit our [Legal & Disclaimer Page](https://nuvioapp.space/legal).

## Built With

- Kotlin Multiplatform
- Compose Multiplatform
- Kotlin
- Compose Desktop packaging
- Native desktop player integrations
- libmpv (MPV render API) via a C++/JNI bridge
- Discord IPC (Rich Presence)

<!-- MARKDOWN LINKS & IMAGES -->
[contributors-shield]: https://img.shields.io/github/contributors/JJDizz1L/NuvioLinux.svg?style=for-the-badge
[contributors-url]: https://github.com/JJDizz1L/NuvioLinux/graphs/contributors
[forks-shield]: https://img.shields.io/github/forks/JJDizz1L/NuvioLinux.svg?style=for-the-badge
[forks-url]: https://github.com/JJDizz1L/NuvioLinux/network/members
[stars-shield]: https://img.shields.io/github/stars/JJDizz1L/NuvioLinux.svg?style=for-the-badge
[stars-url]: https://github.com/JJDizz1L/NuvioLinux/stargazers
[issues-shield]: https://img.shields.io/github/issues/JJDizz1L/NuvioLinux.svg?style=for-the-badge
[issues-url]: https://github.com/JJDizz1L/NuvioLinux/issues
[license-shield]: https://img.shields.io/github/license/JJDizz1L/NuvioLinux.svg?style=for-the-badge
[license-url]: https://github.com/JJDizz1L/NuvioLinux/blob/main/LICENSE
