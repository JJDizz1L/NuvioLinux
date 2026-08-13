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

> ### ⚠️ Hard Fork — Linux Only
>
> This is a **hard fork** of [Nuvio Desktop](https://github.com/NuvioMedia/NuvioDesktop),
> maintained as a standalone repository and focused **exclusively on Linux**.
> The macOS and Windows code paths have been removed. If you need those
> platforms, use the upstream project instead.

## ⚠️ Alpha Software — Testers Only

Nuvio Linux is currently in alpha and is intended only for testers. It is under active development and is not suitable for daily use.

Expect breaking changes with every update. Features, settings, stored data, and compatibility may change or stop working without notice. Do not rely on this build as your primary media app, and report any issues you encounter during testing.

## About

Nuvio Linux is a media client for browsing metadata, managing collections and watch progress, downloading media, and playing streams from user-installed extensions or user-provided sources.

This fork is a hard fork of [NuvioMedia/NuvioDesktop](https://github.com/NuvioMedia/NuvioDesktop) (standalone repository, from the `feat/hwaccel-libmpv-linux` work). It keeps the upstream client codebase — including feature ports from newer upstream releases — while replacing the desktop playback stack with a native Linux player.

## What's Different From Upstream

- **Native Linux playback via MPV (libmpv).** The old embedded player is replaced by a C++/JNI bridge that embeds mpv using its render API, drawing video straight into the Compose scene — all overlay UI works on X11 and Wayland. The app itself runs under XWayland; the embedded player is display-agnostic (offscreen EGL) and works on both backends.
- **Hardware acceleration.** Zero-copy decode via **VA-API (Mesa/AMD/Intel)** and **NVDEC (NVIDIA)**, chosen automatically by the app's decoder setting, with a software fallback.
- **HDR support.** The embedded player loads your `mpv.conf` wholesale, so HDR/color configuration — tone-mapping, `target-peak`, inverse-tone-mapping, profiles — applies as-is.
- **Discord Rich Presence.** Show what you're watching or browsing on your Discord profile. Configurable under **Settings → Integrations → Discord Rich Presence**.
- **Trakt & Simkl tracking.** Connect your Trakt or Simkl account under **Settings → Integrations → Tracking** — library, watch progress, watched history and scrobbling sync across your devices, with a "Sync now" button and automatic refresh.
- **Arch Linux distribution.** A first-class Arch package with a bundled JRE (no system Java required), including a launcher and desktop entry.
- **Fedora/RPM, AppImage, and Flatpak packages.** Release artifacts for Fedora/RHEL (`dnf`), a portable AppImage, and a sandboxed Flatpak — all compiled for **generic x86-64**.

## Installation (Fedora / RHEL)

Install the `.rpm` from a [release](https://github.com/JJDizz1L/NuvioLinux/releases) (requires system `mpv`):

```bash
sudo dnf install ./nuvio-linux-*.x86_64.rpm
```

## Installation (Debian / Ubuntu)

Install the `.deb` from a [release](https://github.com/JJDizz1L/NuvioLinux/releases) (requires system `mpv`):

```bash
sudo apt install ./nuvio-linux_0.1.17-alpha-3_amd64.deb
```

The package is self-contained (bundled JRE, no system Java required), installs to
`/opt/nuvio-linux`, and is compiled for **generic x86-64**.

## Installation (AppImage)

Download `nuvio-linux-*-x86_64.AppImage` from a [release](https://github.com/JJDizz1L/NuvioLinux/releases), then:

```bash
chmod +x nuvio-linux-*.AppImage
./nuvio-linux-*.AppImage
```

Requires system `mpv` and `libfuse2` (or run with `--appimage-extract-and-run` on FUSE-less systems).

## Installation (Flatpak)

Download `nuvio-linux-*.flatpak` from a [release](https://github.com/JJDizz1L/NuvioLinux/releases), then:

```bash
flatpak install --user ./nuvio-linux-*.flatpak
flatpak run io.github.jjdizz1l.NuvioLinux
```

The Flatpak bundles libmpv built from source — no system `mpv` needed. Your
`~/.config/mpv/mpv.conf` is honored read-only. P2P/TorrServer works via the
bundled binary. Updates are delivered through the bundle (the in-app updater
is disabled in the sandbox); a toast in the app points to new releases on
GitHub.

## Installation (Arch Linux)

Prebuilt packages are attached to each [release](https://github.com/JJDizz1L/NuvioLinux/releases). The package is self-contained (bundled JRE, no system Java required), installs to `/opt/nuvio-linux`, and is compiled for **generic x86-64** — it runs on any x86-64 CPU (no AVX2/AVX-512 requirement).

### Install from the AUR

Install the **prebuilt binary** (no compile) with `yay` or `paru`:

```bash
yay -S nuvio-linux-bin
```

or build the **VCS package** from source (tracks the `dev` branch):

```bash
yay -S nuvio-linux-git
```

### Build from source

```bash
git clone -b dev https://github.com/JJDizz1L/NuvioLinux.git
cd NuvioLinux/dist/arch
makepkg -si
```

> To keep the bundled JRE portable, build with a **baseline x86-64 JDK** (e.g.
> Eclipse Temurin 21) — distro JDKs compiled with `-march=native` /
> `-march=x86-64-v3/v4` (e.g. CachyOS) produce a runtime that only runs on
> those CPUs. Point `JAVA_HOME` at a generic JDK before running `makepkg`.

### Launching

Launch it from your app menu, or from a terminal:

```bash
nuvio-linux
```

This fork does not produce Windows or macOS builds.

## Development

```bash
git clone -b dev https://github.com/JJDizz1L/NuvioLinux.git
cd NuvioLinux
```

Run from source:

```bash
./gradlew run
```

Build a release package for the current host:

```bash
./gradlew :composeApp:packageReleaseDistributionForCurrentOS
```

Linux packaging:

Build a Debian `.deb` package for **Debian-based** distros (Debian, Ubuntu, and derivatives):

```bash
./gradlew :composeApp:packageReleaseDeb
```

The resulting `.deb` is written to `composeApp/build/compose/binaries/main-release/deb/`. Install it with:

```bash
sudo apt install ./composeApp/build/compose/binaries/main-release/deb/*.deb
```

Build a Fedora/RHEL `.rpm` (declares `Requires: mpv`; needs `rpmbuild`):

```bash
./dist/rpm/build-rpm.sh
```

Build a portable `.AppImage` (needs `appimagetool`; uses system mpv):

```bash
./dist/appimage/build-appimage.sh
```

Build a Flatpak bundle (`io.github.jjdizz1l.NuvioLinux`; builds libmpv + deps
from source inside the sandbox for baseline x86-64 portability):

```bash
./dist/flatpak/build-flatpak.sh
```

> Note: this fork's primary Linux distribution is the [Arch Linux package](#installation-arch-linux) (self-contained, bundled JRE). All Linux packages require a native `mpv` installation at runtime — except the Flatpak, which bundles libmpv built from source. All artifacts are attached to each [release](https://github.com/JJDizz1L/NuvioLinux/releases).

## Troubleshooting

### Add-ons not loading / sync issues after an update

Release 0.1.17 restored the upstream sync identity (client-id prefix, device
name, user agent) for cross-app sync parity. If you signed in with an earlier
0.1.16.x build, your device may conflict with the sync service — add-ons can
stop loading and settings may not sync. To fix it, sign out and back in:

**Settings → Account → Account and Sync Status → Sign Out**, then sign in to
your Nuvio account again. This re-registers the device. Fresh installs are
unaffected.

### Tiling window managers (niri, sway, i3, bspwm, …)

Tiling compositors don't wrap windows in a WM-managed frame the way AWT
expects, which can make the app render into a small box in the corner of the
window. The app detects non-reparenting window managers and enables the JDK's
workaround (`_JAVA_AWT_WM_NONREPARENTING=1`) automatically — no action needed.

If a tiling WM is not detected automatically (or you want to override the
detection), set the variable yourself before launching:

```bash
_JAVA_AWT_WM_NONREPARENTING=1 nuvio-linux
```

### NixOS (AppImage via steam-run): playback stutter / software GL

On NixOS the AppImage is typically run through `steam-run`, whose bundled
graphics libraries can shadow your real GPU driver, silently falling back to
software rendering (stutter). Point the loader at the system's actual OpenGL
libraries first:

```bash
LD_LIBRARY_PATH=/run/opengl-driver/lib:$LD_LIBRARY_PATH ./nuvio-linux-<version>-x86_64.AppImage
```

(`/run/opengl-driver/lib` hosts the NixOS libGL/GLX driver environment; adjust
the path if your setup differs.) This is an environment/library-loading issue,
not an app bug — on standard distros the AppImage resolves the GPU driver
normally.

## Project Structure

- `composeApp/` contains the app code.
- `composeApp/src/commonMain/` contains shared UI, features, repositories, and platform-agnostic logic.
- `composeApp/src/desktopMain/` contains desktop-specific integrations.
- `composeApp/src/desktopMain/native/` contains the C++/libmpv playback bridge.
- `dist/arch/`, `dist/rpm/`, `dist/appimage/`, `dist/flatpak/` contain the per-distribution packaging scripts.
- `composeApp/Configuration/DesktopVersion.properties` contains the desktop release version and build code.

## Versioning

Desktop versions are set in `composeApp/Configuration/DesktopVersion.properties` — the single source of truth every artifact version derives from. The version is kept **aligned with upstream's desktop version**; when upstream bumps, bump to match.

```properties
VERSION_NAME=0.1.17-alpha
VERSION_CODE=17
```

Use the version helper when changing desktop release versions:

```bash
./scripts/set-version.sh --desktop 0.1.17-alpha --desktop-code 17
./scripts/set-version.sh --show
```

Per-format package names carry the same release counter (Arch `pkgrel` / RPM `Release` / the `-<release>` suffix in DEB, AppImage and Flatpak names), e.g. release 3 of `0.1.17-alpha`:

```
nuvio-linux-0.1.17alpha-3-x86_64.pkg.tar.zst
nuvio-linux-0.1.17alpha-3.x86_64.rpm
nuvio-linux_0.1.17-alpha-3_amd64.deb
nuvio-linux-0.1.17-alpha-3-x86_64.AppImage
nuvio-linux-0.1.17-alpha-3.flatpak
```

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

## Star History

<a href="https://www.star-history.com/#JJDizz1L/NuvioLinux&type=date&legend=top-left">
 <picture>
   <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/svg?repos=JJDizz1L/NuvioLinux&type=date&theme=dark&legend=top-left" />
   <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/svg?repos=JJDizz1L/NuvioLinux&type=date&legend=top-left" />
   <img alt="Star History Chart" src="https://api.star-history.com/svg?repos=JJDizz1L/NuvioLinux&type=date&legend=top-left" />
 </picture>
</a>

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
