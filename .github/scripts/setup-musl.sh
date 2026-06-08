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

# Download a file, trying each mirror in turn with retries. musl.cc is chronic-
# ally flaky (the first release run died on "connect to musl.cc timed out"), so
# a GitHub-hosted mirror is tried first — GitHub is reliably reachable from CI.
# `--retry-all-errors` is avoided on purpose: the manylinux_2_28 container ships
# curl 7.61, which predates that flag. Plain `--retry` covers transient timeouts.
fetch() {
  dest="$1"; shift
  for url in "$@"; do
    echo "setup-musl: fetching $url"
    if curl -fL --retry 5 --retry-delay 3 --connect-timeout 20 --max-time 600 \
            -o "$dest" "$url"; then
      return 0
    fi
    echo "setup-musl: mirror failed, trying next" >&2
  done
  echo "setup-musl: all mirrors failed for ${dest##*/}" >&2
  return 1
}

toolchain_tgz="$(mktemp --suffix=.tgz)"
fetch "$toolchain_tgz" \
  "https://github.com/musl-cc/musl.cc/releases/download/v0.0.1/${MUSL_TRIPLE}-native.tgz" \
  "https://musl.cc/${MUSL_TRIPLE}-native.tgz"
tar -xzf "$toolchain_tgz" -C "$PREFIX" --strip-components=1

export PATH="$PREFIX/bin:$PATH"
echo "$PREFIX/bin" >> "$GITHUB_PATH"

# native-image links libz statically; build it against the musl toolchain and
# install into the toolchain prefix so the musl gcc finds libz.a / zlib.h.
ZLIB_VERSION=1.3.1
workdir="$(mktemp -d)"
echo "setup-musl: building static zlib ${ZLIB_VERSION} with ${MUSL_TRIPLE}-gcc"
zlib_tgz="$(mktemp --suffix=.tar.gz)"
fetch "$zlib_tgz" \
  "https://github.com/madler/zlib/releases/download/v${ZLIB_VERSION}/zlib-${ZLIB_VERSION}.tar.gz" \
  "https://zlib.net/zlib-${ZLIB_VERSION}.tar.gz" \
  "https://zlib.net/fossils/zlib-${ZLIB_VERSION}.tar.gz"
tar -xzf "$zlib_tgz" -C "$workdir" --strip-components=1
(
  cd "$workdir"
  CC="${MUSL_TRIPLE}-gcc" ./configure --static --prefix="$PREFIX"
  make -j"$(nproc)"
  make install
)

echo "CC=${MUSL_TRIPLE}-gcc" >> "$GITHUB_ENV"
echo "setup-musl: installed musl toolchain + static zlib under $PREFIX"
