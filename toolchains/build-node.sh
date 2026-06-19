#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"
BUILD_DIR="$SCRIPT_DIR/build/node"
OUTPUT_DIR="$ROOT_DIR/android/app/src/main/jniLibs/arm64-v8a"

NODE_VERSION="20.18.1"
NODE_URL="https://nodejs.org/dist/v${NODE_VERSION}/node-v${NODE_VERSION}.tar.xz"
VSCODROID_PREFIX="/data/data/com.vscodroid/files/usr"

echo "=== Building Node.js $NODE_VERSION for ARM64 Android ==="

# ── Docker detection ──────────────────────────────────────────────────────────
# We MUST run inside the Docker image built from toolchains/Dockerfile — it has
# a known-good Android NDK r27c at /opt/android-ndk plus the exact host build
# tools this cross-compile needs.
#
# Checking "is ANDROID_NDK_HOME set?" to decide this is UNRELIABLE and was the
# actual root cause of every "clang++: No such file or directory" failure seen
# so far: GitHub-hosted Actions runners (ubuntu-latest) come with their OWN
# Android SDK/NDK pre-installed and ANDROID_NDK_HOME already pointing at it
# (e.g. /usr/local/lib/android/sdk/ndk/<version>) as part of the default runner
# image — nothing to do with this script or Docker at all. With the old check,
# that pre-set variable made the script believe it was "already configured"
# and skip Docker entirely, silently compiling against the runner's own
# possibly-incomplete/incompatible NDK installation instead.
#
# VSCODROID_DOCKER_BUILD=1 is set ONLY inside our own Dockerfile (see
# toolchains/Dockerfile) and cannot be true on a bare host runner, so it is
# the one unambiguous signal that we are actually inside the right container.
if [ "${VSCODROID_DOCKER_BUILD:-0}" != "1" ]; then
    echo "Not running inside the VSCodroid build container — launching Docker..."
    if [ -n "${ANDROID_NDK_HOME:-}" ]; then
        echo "(Note: ANDROID_NDK_HOME=$ANDROID_NDK_HOME is set on this host, but it is"
        echo " ignored — Docker provides its own known-good NDK r27c at /opt/android-ndk.)"
    fi
    docker build --platform linux/amd64 -t vscodroid/build-env "$SCRIPT_DIR"
    docker run --rm --platform linux/amd64 \
        -v "$ROOT_DIR:/workspace" \
        vscodroid/build-env \
        /workspace/toolchains/build-node.sh
    exit $?
fi

echo "Confirmed running inside the VSCodroid Docker build container."

NDK_HOST_TAG="$(uname -s | tr '[:upper:]' '[:lower:]')-$(uname -m)"
NDK_TOOLCHAIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$NDK_HOST_TAG"

# Download source
mkdir -p "$BUILD_DIR"
cd "$BUILD_DIR"

if [ ! -f "node-v${NODE_VERSION}.tar.xz" ]; then
    echo "Downloading Node.js source..."
    wget -q --show-progress "$NODE_URL"
fi

if [ ! -d "node-v${NODE_VERSION}" ]; then
    echo "Extracting..."
    tar xf "node-v${NODE_VERSION}.tar.xz"
fi

cd "node-v${NODE_VERSION}"

# Apply Termux/VSCodroid patches
PATCHES_DIR="$SCRIPT_DIR/patches/node"
if [ -d "$PATCHES_DIR" ] && ls "$PATCHES_DIR"/*.patch 1>/dev/null 2>&1; then
    echo "Applying patches..."
    TEMP_PATCHES="$BUILD_DIR/patches_processed"
    rm -rf "$TEMP_PATCHES"
    mkdir -p "$TEMP_PATCHES"

    # Process patches: replace @TERMUX_PREFIX@ with VSCodroid prefix
    for patch in "$PATCHES_DIR"/*.patch; do
        [ -f "$patch" ] || continue
        processed="$TEMP_PATCHES/$(basename "$patch")"
        sed "s|@TERMUX_PREFIX@|$VSCODROID_PREFIX|g" "$patch" > "$processed"
    done

    for patch in "$TEMP_PATCHES"/*.patch; do
        [ -f "$patch" ] || continue
        echo "  Applying $(basename "$patch")..."
        patch -p1 --forward --batch < "$patch" || {
            echo "  WARNING: $(basename "$patch") did not apply cleanly (may already be applied)"
        }
    done
fi

# Post-patch fixups for Node 20.18.1
# These changes come from Termux but the original patches target 20.17.0 line numbers.
# We apply them directly for robustness.
echo "Applying post-patch fixups..."

# 1. Remove android_ndk_path reference from common.gypi
#    (GYP variable is undefined when using ./configure directly instead of android_configure.py)
sed -i.bak '/\[.OS == "android"., {/,/}],/d' common.gypi && rm -f common.gypi.bak
echo "  Fixed common.gypi: removed android_ndk_path reference"

# 2. Fix node.gyp: add getaddrinfo.c, remove test/fuzz targets and node_mksnapshot
#    (Test targets are host-only and fail during cross-compilation)
python3 - <<'PYEOF'
import re

with open('node.gyp', 'r') as f:
    content = f.read()

# Add src/getaddrinfo.c to sources (custom DNS resolver for Android bionic)
content = content.replace(
    "'src/node_main.cc'\n",
    "'src/node_main.cc',\n        'src/getaddrinfo.c'\n",
    1  # only first occurrence
)

# Remove test targets: fuzz_env, fuzz_ClientHelloParser, fuzz_strings, cctest, embedtest, overlapped-checker
# These are between "# node_lib_target_name" and "node_js2c"
content = re.sub(
    r"(    },\s*# node_lib_target_name\n)(.*?)(    \{\s*\n\s*'target_name': 'node_js2c',)",
    r"\1\3",
    content,
    flags=re.DOTALL
)

# Remove node_mksnapshot target
content = re.sub(
    r"    \{\s*\n\s*'target_name': 'node_mksnapshot',.*?\}, # node_mksnapshot\n",
    "",
    content,
    flags=re.DOTALL
)

with open('node.gyp', 'w') as f:
    f.write(content)
PYEOF
echo "  Fixed node.gyp: added getaddrinfo.c, removed test targets and node_mksnapshot"

# 3. Disable V8 trap handler (not safe on Android)
python3 - <<'PYEOF'
with open('deps/v8/src/trap-handler/trap-handler.h', 'r') as f:
    content = f.read()

import re
# Replace the entire #if ... #endif block that defines V8_TRAP_HANDLER_SUPPORTED
# with a simple unconditional false
content = re.sub(
    r'// X64 on Linux.*?#define V8_TRAP_HANDLER_SUPPORTED false\n#endif',
    '#define V8_TRAP_HANDLER_SUPPORTED false',
    content,
    flags=re.DOTALL
)

with open('deps/v8/src/trap-handler/trap-handler.h', 'w') as f:
    f.write(content)
PYEOF
echo "  Fixed trap-handler.h: disabled V8 trap handler"

# 4. Change /tmp fallback to VSCodroid prefix in os.js
sed -i.bak "s|'/tmp';|'$VSCODROID_PREFIX/tmp';|" lib/os.js && rm -f lib/os.js.bak
echo "  Fixed os.js: updated /tmp fallback to $VSCODROID_PREFIX/tmp"

# Configure
echo "Configuring..."

# NDK per-API clang wrapper scripts (aarch64-linux-android<NN>-clang) are not
# guaranteed to exist for every API number in every NDK release — the exact
# set varies between NDK versions. Hardcoding "28" broke when this stopped
# being true for this NDK build. Probe for a working pair instead of assuming
# one specific number, and fall back to the NDK's underlying unified clang
# binary with an explicit --target= flag (the same mechanism the wrapper
# scripts use internally, and guaranteed present in every NDK release since r23).
ANDROID_API=""
for api in 28 29 30 31 26 27 24 21; do
    if [ -x "$NDK_TOOLCHAIN/bin/aarch64-linux-android${api}-clang" ] && \
       [ -x "$NDK_TOOLCHAIN/bin/aarch64-linux-android${api}-clang++" ]; then
        ANDROID_API="$api"
        break
    fi
done

if [ -n "$ANDROID_API" ]; then
    echo "Using NDK wrapper scripts for API level $ANDROID_API"
    export CC="$NDK_TOOLCHAIN/bin/aarch64-linux-android${ANDROID_API}-clang"
    export CXX="$NDK_TOOLCHAIN/bin/aarch64-linux-android${ANDROID_API}-clang++"
else
    echo "No per-API wrapper script found for any candidate API level."
    echo "Falling back to the unified clang binary with --target= (NDK r23+ convention)."
    ANDROID_API=28
    export CC="$NDK_TOOLCHAIN/bin/clang --target=aarch64-linux-android${ANDROID_API}"
    export CXX="$NDK_TOOLCHAIN/bin/clang++ --target=aarch64-linux-android${ANDROID_API}"
fi

# Some GYP-generated Makefiles (notably newer deps like deps/base64 and
# deps/ada) reference the dotted "CC.target" / "CXX.target" Make variables
# directly instead of inheriting the plain CC/CXX environment variables.
# Passing both forms as explicit `make` arguments (below) covers whichever
# convention a given target's generated .target.mk actually uses.
export CC_target="$CC"
export CXX_target="$CXX"
export AR_target="$NDK_TOOLCHAIN/bin/llvm-ar"
export LINK_target="$CXX"

echo "CC:  $CC"
echo "CXX: $CXX"

export CC_host="gcc"
export CXX_host="g++"
export CFLAGS="-D__TERMUX__ -D__VSCODROID__"
export CXXFLAGS="-D__TERMUX__ -D__VSCODROID__"
export LDFLAGS="-Wl,-z,max-page-size=16384"

# GYP defines needed for V8 cross-compilation (normally set by android_configure.py)
export GYP_DEFINES="target_arch=arm64 v8_target_arch=arm64 android_target_arch=arm64 host_os=linux OS=android"

./configure \
    --dest-cpu=arm64 \
    --dest-os=android \
    --cross-compiling \
    --partly-static \
    --with-intl=small-icu \
    --openssl-no-asm \
    --without-inspector \
    --without-node-snapshot \
    --shared-zlib \
    --prefix="$VSCODROID_PREFIX"

# Build
echo "Building (this takes 20-60 minutes)..."
echo "Disk space before build:"
df -h . 2>/dev/null || true

# GYP-generated Makefiles for some targets (e.g. deps/base64, deps/ada) use
# the dotted CC.target / CXX.target / AR.target variable names. Pass them as
# explicit `make` command-line overrides — these always win over a Makefile's
# own internal defaults, regardless of which convention any given target uses.
MAKE_ARGS=(
    "CC.target=$CC"
    "CXX.target=$CXX"
    "AR.target=$NDK_TOOLCHAIN/bin/llvm-ar"
    "LINK.target=$CXX"
)

set +e
make -j"$(nproc)" "${MAKE_ARGS[@]}"
BUILD_STATUS=$?
set -e

if [ "$BUILD_STATUS" -ne 0 ]; then
    echo ""
    echo "Parallel build failed (exit $BUILD_STATUS). Retrying single-threaded"
    echo "to rule out a parallel-build race condition before giving up..."
    echo "Disk space at retry:"
    df -h . 2>/dev/null || true
    make -j1 "${MAKE_ARGS[@]}"
fi

# Strip
echo "Stripping binary..."
"$NDK_TOOLCHAIN/bin/llvm-strip" --strip-unneeded out/Release/node

# Copy to output
mkdir -p "$OUTPUT_DIR"
cp out/Release/node "$OUTPUT_DIR/libnode.so"

echo ""
echo "=== Node.js build complete ==="
echo "Output: $OUTPUT_DIR/libnode.so ($(du -sh "$OUTPUT_DIR/libnode.so" | cut -f1))"
file "$OUTPUT_DIR/libnode.so" 2>/dev/null || true
