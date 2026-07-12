#!/bin/bash
# build-easytier-jni.sh
# Build the EasyTier Android JNI library (.so) for v2rayNG integration.
#
# Prerequisites:
#   - Rust toolchain (rustup)
#   - Android NDK r21+
#   - cargo-ndk:  cargo install cargo-ndk
#   - Rust targets:  rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android i686-linux-android
#
# Usage:
#   ./build-easytier-jni.sh          # build all targets
#   ./build-easytier-jni.sh arm64-v8a   # build arm64-v8a only

set -e

# REPO_ROOT is the v2rayNG git repo root (this script lives at the repo root).
# EasyTier is checked out as a git submodule at $REPO_ROOT/EasyTier.
REPO_ROOT="$(cd "$(dirname "$0")" && pwd)"
EASYTIER_CONTRIB="$REPO_ROOT/EasyTier/easytier-contrib"
JNI_DIR="$EASYTIER_CONTRIB/easytier-android-jni"
V2RAYNG_JNI_LIBS="$REPO_ROOT/easytier-plugin/src/main/jniLibs"

# Architectures
# - ABI: Android ABI name (used for cargo-ndk -t and jniLibs directory layout)
# - RUST_TARGET: Rust target triple (used to find cargo output directory)
ALL_ARCHS=("arm64-v8a" "armeabi-v7a" "x86" "x86_64")
declare -A RUST_TARGET_MAP
RUST_TARGET_MAP["arm64-v8a"]="aarch64-linux-android"
RUST_TARGET_MAP["armeabi-v7a"]="armv7-linux-androideabi"
RUST_TARGET_MAP["x86"]="i686-linux-android"
RUST_TARGET_MAP["x86_64"]="x86_64-linux-android"

# Determine which archs to build
if [[ "$1" != "" ]]; then
    ARCHS=("$1")
else
    ARCHS=("${ALL_ARCHS[@]}")
fi

echo "=========================================="
echo "EasyTier Android JNI Build"
echo "=========================================="
echo "JNI source: $JNI_DIR"
echo "Output:     $V2RAYNG_JNI_LIBS"
echo "Archs:      ${ARCHS[*]}"
echo ""

# Check prerequisites
if ! command -v cargo-ndk &> /dev/null; then
    echo "ERROR: cargo-ndk not found. Install with: cargo install cargo-ndk"
    exit 1
fi

if [[ -z "$ANDROID_NDK_HOME" && -z "$ANDROID_NDK_ROOT" && -z "$NDK_HOME" ]]; then
    echo "ERROR: None of ANDROID_NDK_HOME, ANDROID_NDK_ROOT, or NDK_HOME is set."
    echo "Set one to your NDK path, e.g.:"
    echo "  export ANDROID_NDK_HOME=\$HOME/Android/Sdk/ndk/29.0.14206865"
    exit 1
fi

NDK_PATH="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-${NDK_HOME}}}"
echo "NDK path: $NDK_PATH"
echo "cargo-ndk: $(cargo ndk --version 2>&1)"
echo ""

# Build each architecture
for ARCH in "${ARCHS[@]}"; do
    RUST_TARGET="${RUST_TARGET_MAP[$ARCH]}"
    if [[ -z "$RUST_TARGET" ]]; then
        echo "ERROR: Unknown architecture '$ARCH'"
        exit 1
    fi

    echo "------------------------------------------"
    echo "Building $ARCH ($RUST_TARGET)..."
    echo "------------------------------------------"

    # Ensure Rust target is installed
    if ! rustup target list --installed | grep -q "$RUST_TARGET"; then
        echo "Installing Rust target: $RUST_TARGET"
        rustup target add "$RUST_TARGET"
    fi

    pushd "$JNI_DIR" > /dev/null

    # cargo-ndk accepts Android ABI names (arm64-v8a, armeabi-v7a, x86, x86_64)
    cargo ndk -t "$ARCH" build --release

    popd > /dev/null

    # Copy .so to easytier-plugin jniLibs
    OUTPUT_DIR="$V2RAYNG_JNI_LIBS/$ARCH"
    mkdir -p "$OUTPUT_DIR"

    # cargo outputs to target/<rust-target>/release/
    SO_FILE="$JNI_DIR/target/$RUST_TARGET/release/libeasytier_android_jni.so"
    if [[ ! -f "$SO_FILE" ]]; then
        echo "ERROR: Output .so not found at $SO_FILE"
        exit 1
    fi

    cp "$SO_FILE" "$OUTPUT_DIR/"
    echo "✓ Copied to $OUTPUT_DIR/libeasytier_android_jni.so"
    echo ""
done

echo "=========================================="
echo "Build complete!"
echo "=========================================="
echo "Native libraries installed to:"
for ARCH in "${ARCHS[@]}"; do
    echo "  $V2RAYNG_JNI_LIBS/$ARCH/libeasytier_android_jni.so"
done
