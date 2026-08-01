<div align="center">

  <img src="composeApp/src/commonMain/composeResources/drawable/app_logo_wordmark.png" alt="Nuvio" width="300" />
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
> maintained separately and focused **exclusively on Linux development**.
> The macOS and Windows code paths are being removed. If you need those
> platforms, use the upstream project instead.

## ⚠️ Alpha Software — Testers Only

Nuvio is currently in alpha and is intended only for testers. It is under active development and is not suitable for daily use.

Expect breaking changes with every update. Features, settings, stored data, and compatibility may change or stop working without notice. Do not rely on this build as your primary media app, and report any issues you encounter during testing.

## About

Nuvio is a media client for browsing metadata, managing collections and watch progress, downloading media, and playing streams from user-installed extensions or user-provided sources.

This fork is a hard fork of [NuvioMedia/NuvioDesktop](https://github.com/NuvioMedia/NuvioDesktop), diverged from the `feat/hwaccel-libmpv-linux` branch. It keeps the upstream client codebase while replacing the desktop playback stack with a native Linux player.

## What's Different From Upstream

- **Native Linux playback via MPV (libmpv).** The old embedded player is replaced by a C++/JNI bridge that embeds mpv using its render API, drawing video straight into the Compose scene — all overlay UI works on X11 and Wayland. The app itself runs under XWayland; the embedded player is display-agnostic (offscreen EGL) and works on both backends.
- **Hardware acceleration.** Zero-copy decode via **VA-API (Mesa/AMD/Intel)** and **NVDEC (NVIDIA)**, chosen automatically by the app's decoder setting, with a software fallback.
- **HDR support.** The embedded player loads your `mpv.conf` wholesale, so HDR/color configuration — tone-mapping, `target-peak`, inverse-tone-mapping, profiles — applies as-is.
- **Discord Rich Presence.** Show what you're watching or browsing on your Discord profile. Configurable under **Settings → Integrations → Discord Rich Presence**.
- **Arch Linux distribution.** A first-class Arch package with a bundled JRE (no system Java required), including a launcher and desktop entry.

## Installation (Arch Linux)

Prebuilt packages are attached to each [release](https://github.com/JJDizz1L/NuvioLinux/releases). The package is self-contained (bundled JRE, no system Java required) and installs to `/opt/nuvio-linux`.

### Install directly from the release URL

```bash
sudo pacman -U https://github.com/JJDizz1L/NuvioLinux/releases/download/v0.1.15.1/nuvio-linux-0.1.0-2-x86_64.pkg.tar.zst
```

### Install a manually downloaded package

Download `nuvio-linux-0.1.0-2-x86_64.pkg.tar.zst` from the latest
[release](https://github.com/JJDizz1L/NuvioLinux/releases), then install it:

```bash
sudo pacman -U ./nuvio-linux-0.1.0-1-x86_64.pkg.tar.zst
```

### Build from source

```bash
git clone https://github.com/JJDizz1L/NuvioLinux.git
cd NuvioLinux/dist/arch
makepkg -si
```

### Launching

Launch it from your app menu, or from a terminal:

```bash
nuvio-linux
```

This fork does not produce Windows or macOS builds.

## Development

```bash
git clone https://github.com/JJDizz1L/NuvioLinux.git
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

The resulting `.deb` is written to `composeApp/build/compose/binaries/main/deb/`. Install it with:

```bash
sudo apt install ./composeApp/build/compose/binaries/main/deb/*.deb
```

> Note: this fork's primary Linux distribution is the [Arch Linux package](#installation-arch-linux) (self-contained, bundled JRE). The `.deb` target is provided for Debian-based systems but is the less-tested path. Both require a native `mpv` installation at runtime.

## Project Structure

- `composeApp/` contains the app code.
- `composeApp/src/commonMain/` contains shared UI, features, repositories, and platform-agnostic logic.
- `composeApp/src/desktopMain/` contains desktop-specific integrations.
- `composeApp/src/desktopMain/native/` contains the C++/libmpv playback bridge.
- `composeApp/Configuration/DesktopVersion.properties` contains the desktop release version and build code.

## Versioning

Desktop versions are set in `composeApp/Configuration/DesktopVersion.properties`.

```properties
VERSION_NAME=0.1.1-alpha
VERSION_CODE=1
```

Use the version helper when changing desktop release versions:

```bash
./scripts/set-version.sh --desktop 0.1.2-alpha --desktop-code 2
./scripts/set-version.sh --show
```

## Legal & DMCA

Nuvio functions solely as a client-side interface for browsing metadata and playing media provided by user-installed extensions and/or user-provided sources. It is intended for content the user owns or is otherwise authorized to access.

Nuvio is not affiliated with any third-party extensions, catalogs, sources, or content providers. It does not host, store, or distribute any media content.

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
