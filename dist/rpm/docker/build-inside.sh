#!/usr/bin/env bash
#
# Runs INSIDE the Fedora build container (see ../Dockerfile). Mounts:
#   /rpmbuild            the rpmbuild topdir (SOURCES/ with the payload tar)
#   /spec                the nuvio-linux.spec file
#   APPVERSION/APPRELEASE environment variables
#
# Builds the RPM with Fedora's own rpmbuild — Arch's rpm-tools writes empty
# header digests that Fedora's rpm rejects at install time ("Header SHA3-256
# digest: BAD") — then gates on `rpm -K` so a broken package can never ship.
set -euo pipefail

rpmbuild -bb \
  --define "_topdir /rpmbuild" \
  --define "_sourcedir /rpmbuild/SOURCES" \
  --define "_specdir /rpmbuild/SPECS" \
  --define "_builddir /rpmbuild/BUILD" \
  --define "_buildrootdir /rpmbuild/BUILDROOT" \
  --define "_rpmdir /rpmbuild/RPMS" \
  --define "_srcrpmdir /rpmbuild/SRPMS" \
  --define "appversion ${APPVERSION}" \
  --define "apprelease ${APPRELEASE}" \
  --define "dist %{nil}" \
  /spec

echo "[fedora-rpmbuild] verifying package digests..."
out="$(rpm -K /rpmbuild/RPMS/x86_64/*.rpm)"
echo "[fedora-rpmbuild] rpm -K: ${out}"
case "${out}" in
  *"NOT OK"*|*BAD*)
    echo "[fedora-rpmbuild] ERROR: digest verification failed" >&2
    exit 1
    ;;
esac
rpm -qp --qf "[fedora-rpmbuild] built: %{NAME} %{VERSION}-%{RELEASE} %{ARCH}\n" /rpmbuild/RPMS/x86_64/*.rpm
