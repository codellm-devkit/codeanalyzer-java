#!/usr/bin/env bash
# Best-effort musl cross toolchain + static zlib so GraalVM native-image can
# produce a fully-static `--libc=musl` binary on a glibc (manylinux) host.
# GraalVM can't run on Alpine, so the static binary is the only way to ship a
# musllinux wheel. This leg is marked experimental in the workflow; failures
# here should not be treated as a hard release blocker.
#
# Usage: setup-musl.sh <arch>   where arch is x64|x86_64 or aarch64|arm64
set -euo pipefail

ARCH="${1:-x64}"
case "$ARCH" in
  x64 | x86_64) MUSL_TRIPLE=x86_64-linux-musl ;;
  aarch64 | arm64) MUSL_TRIPLE=aarch64-linux-musl ;;
  *)
    echo "setup-musl: unsupported arch '$ARCH'" >&2
    exit 1
    ;;
esac

PREFIX=/opt/musl
mkdir -p "$PREFIX"

toolchain_url="https://musl.cc/${MUSL_TRIPLE}-native.tgz"
echo "setup-musl: downloading toolchain $toolchain_url"
curl -fsSL "$toolchain_url" | tar -xz -C "$PREFIX" --strip-components=1

export PATH="$PREFIX/bin:$PATH"
echo "$PREFIX/bin" >> "$GITHUB_PATH"

# native-image links libz statically; build it against the musl toolchain and
# install into the toolchain prefix so the musl gcc finds libz.a / zlib.h.
ZLIB_VERSION=1.3.1
workdir="$(mktemp -d)"
echo "setup-musl: building static zlib ${ZLIB_VERSION} with ${MUSL_TRIPLE}-gcc"
curl -fsSL "https://zlib.net/zlib-${ZLIB_VERSION}.tar.gz" | tar -xz -C "$workdir" --strip-components=1
(
  cd "$workdir"
  CC="${MUSL_TRIPLE}-gcc" ./configure --static --prefix="$PREFIX"
  make -j"$(nproc)"
  make install
)

echo "CC=${MUSL_TRIPLE}-gcc" >> "$GITHUB_ENV"
echo "setup-musl: installed musl toolchain + static zlib under $PREFIX"
