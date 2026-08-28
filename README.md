<div align="center">

  <img src="composeApp/src/commonMain/composeResources/drawable/app_logo_wordmark.png" alt="Nuvio" width="300" />
  <br />
  <br />

  **Arch Linux packaging for [Nuvio](https://github.com/NuvioMedia/NuvioDesktop)** —
  the Nuvio desktop media player, built from the official upstream source.

</div>

## What this repository is

This repository contains **no application code**. It is a packaging-only
repository that builds [NuvioDesktop](https://github.com/NuvioMedia/NuvioDesktop)
— the official upstream Nuvio app — into a signed Arch Linux package.

- The package tracks the upstream source release **byte-for-byte**; the only
  difference from upstream is the Arch `pkgrel` counter
- AUR packages: [`nuvio-linux-bin`](https://aur.archlinux.org/packages/nuvio-linux-bin)
  (prebuilt, from these releases) and
  [`nuvio-linux-git`](https://aur.archlinux.org/packages/nuvio-linux-git)
  (built from upstream's `Dev` branch)
- Signed releases with `SHA256SUMS.txt` and the `PKGBUILD` used to build them

## Why this fork was retired

NuvioLinux started as a fork of NuvioDesktop carrying Linux-specific playback
work (a custom native mpv bridge), a rebrand, and extra package formats.
Upstream has since implemented proper **libmpv** and **GTK3** integration
natively — smoother, hardware-accelerated playback that passed all of our
testing — which made the fork's divergent code redundant.

The fork has been retired: this repository now follows upstream exactly, and
every release is built from unmodified upstream source. Application
development, issues, and the other platform packages (Windows, macOS, DEB,
RPM, AppImage, Flatpak) all live
[upstream](https://github.com/NuvioMedia/NuvioDesktop).

## Installation

### From the AUR (recommended)

```bash
paru -S nuvio-linux-bin      # prebuilt package from these releases
# or
paru -S nuvio-linux-git      # built from the upstream Dev branch
```

### From a release (no AUR helper)

```bash
curl -LO https://github.com/JJDizz1L/NuvioLinux-unofficial/releases/download/v0.1.21-alpha-1/nuvio-linux-gpg-key.asc
sudo pacman-key --add nuvio-linux-gpg-key.asc
sudo pacman-key --lsign-key 9201A54A09675CBEBAD08647EDDA55C8236D6C88
sudo pacman -U https://github.com/JJDizz1L/NuvioLinux-unofficial/releases/download/v0.1.21-alpha-1/nuvio-linux-0.1.21alpha-1-x86_64.pkg.tar.zst
```

Verify the download first with `SHA256SUMS.txt` and
`gpg --verify *.pkg.tar.zst.sig *.pkg.tar.zst`.

## Building the package

The PKGBUILD fetches the pinned upstream source tarball itself, so building
from this repository is self-contained:

```bash
git clone https://github.com/JJDizz1L/NuvioLinux-unofficial.git
cd NuvioLinux-unofficial/dist/arch
makepkg -si
```

Build requirements:

- `base-devel`
- `jdk21-temurin` (AUR) — baseline x86-64 Temurin 21, used to build the
  bundled runtime. Do **not** substitute an `-march=v3/v4` JDK build; the
  bundled runtime would not run on older CPUs.
- `mpv`, `webkit2gtk-4.1`, `gtk3`, `libxcomposite`, `libxext` — headers for
  the native player bridge, which is compiled at build time and links the
  system libmpv

Runtime dependencies (`mpv`, `webkit2gtk-4.1`, `gtk3`, X11 libraries) are
resolved by pacman automatically.

## Package details

- Self-contained app image in `/opt/Nuvio` with a bundled Temurin 21 runtime;
  launcher at `/usr/bin/nuvio`
- Playback via **system libmpv** — hardware acceleration follows your mpv /
  VA-API / VDPAU setup
- P2P streaming works out of the box (bundled TorrServer)
- Signed with packaging key
  `9201A54A09675CBEBAD08647EDDA55C8236D6C88`

## Releases

Tagged `v<upstream-version>-<pkgrel>` (e.g. `v0.1.21-alpha-1`). Each release
ships the built package, its detached GPG signature, `SHA256SUMS.txt`, the
signing public key, and the `PKGBUILD` used to build it.

## Upstream

Nuvio is developed upstream at
[NuvioMedia/NuvioDesktop](https://github.com/NuvioMedia/NuvioDesktop) and is
currently **alpha software** — expect breaking changes between releases.
All application development, issue tracking, and non-Arch packages live
there; this repository only adds Arch Linux packaging and follows upstream
releases.

## Legal

Nuvio functions solely as a client-side interface for browsing metadata and
playing media provided by user-installed extensions and/or user-provided
sources. It does not host, store, or distribute any media content. For the
full disclaimer and DMCA information, see
[Nuvio's legal page](https://nuvioapp.space/legal).

## License

GPL-3.0, as upstream. See [LICENSE](LICENSE).

