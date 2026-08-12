# Spec for Nuvio Linux (Fedora/RHEL RPM).
# Built from the jpackage app image by :composeApp:packageReleaseRpm.
# Version/Release are injected with rpmbuild --define.

%global _enable_debug_package 0
%global debug_package %{nil}

Name:           nuvio-linux
Version:        %{appversion}
Release:        %{apprelease}%{?dist}
Summary:        Nuvio Linux desktop media player
License:        Commercial
URL:            https://github.com/JJDizz1L/NuvioLinux
Source0:        %{name}-app-image.tar.gz
Requires:       mpv
BuildArch:      x86_64

%description
Nuvio Linux is a desktop media client for browsing metadata, managing
collections and watch progress, downloading media, and playing streams from
user-installed extensions or user-provided sources. It is a hard fork of Nuvio
Desktop with a native Linux playback stack powered by mpv (hardware-accelerated
decoding via VAAPI/NVDEC).

%prep
rm -rf %{_builddir}/payload
mkdir -p %{_builddir}/payload
tar -xzf %{_sourcedir}/%{name}-app-image.tar.gz -C %{_builddir}/payload

%install
cp -a %{_builddir}/payload/opt %{buildroot}/
cp -a %{_builddir}/payload/usr %{buildroot}/

%post
update-desktop-database %{_datadir}/applications >/dev/null 2>&1 || :
gtk-update-icon-cache -f -t %{_datadir}/icons/hicolor >/dev/null 2>&1 || :

%postun
update-desktop-database %{_datadir}/applications >/dev/null 2>&1 || :
gtk-update-icon-cache -f -t %{_datadir}/icons/hicolor >/dev/null 2>&1 || :

%files
%dir /opt/nuvio-linux
%dir /opt/nuvio-linux/bin
/opt/nuvio-linux/bin/*
%dir /opt/nuvio-linux/lib
/opt/nuvio-linux/lib/*
%{_datadir}/applications/nuvio-linux.desktop
%{_datadir}/metainfo/io.github.jjdizz1l.NuvioLinux.metainfo.xml
%{_datadir}/icons/hicolor/*/apps/nuvio-linux.png

%changelog
* Fri Aug 07 2026 JJDizz1L - 0.1.17alpha-1
- Version bump to 0.1.17-alpha.
* Sat Aug 01 2026 JJDizz1L - 0.1.15alpha-1
- Initial RPM packaging of Nuvio Linux with desktop integration.
