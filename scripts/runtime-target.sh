#!/usr/bin/env bash
# Runtime build target configuration. Sourcing this file only declares
# configure_runtime_target; callers explicitly choose and configure a target.

configure_runtime_target() {
    local target="${1:-}"

    case "$target" in
        arm64)
            RUNTIME_ARCH="arm64"
            PROOT_URL="https://github.com/proot-me/proot/releases/download/v5.3.0/proot-v5.3.0-aarch64-static"
            PROOT_SHA="fa10b1a7818c2f5b1dcb5834450570c368c9ecf66d31521509621b95c4538a45"
            PROOT_LOADER_OFFSET="223400"
            PROOT_LOADER_SIZE="66832"
            PROOT_LOADER_SHA="51c3427b112edc70d1979b48209c41f332616758138de3be659cc79e50436450"
            ALPINE_URL="https://dl-cdn.alpinelinux.org/alpine/v3.20/releases/aarch64/alpine-minirootfs-3.20.3-aarch64.tar.gz"
            ALPINE_SHA="041fa34a81788242df9e78fa69b97ab45b8ec47ddbf88864755610414a7bf3de"
            DOCKER_PLATFORM="linux/arm64"
            DOCKER_IMAGE_ARCH="arm64"
            ALPINE_BASE_IMAGE="alpine:3.20.3"
            PROOT_FILE_MARKER="ARM aarch64"
            ANDROID_ABI="arm64-v8a"
            PROOT_JNI_RELATIVE_PATH="$ANDROID_ABI/libproot.so"
            PROOT_LOADER_JNI_RELATIVE_PATH="$ANDROID_ABI/libproot-loader.so"
            ;;
        x86_64)
            RUNTIME_ARCH="x86_64"
            PROOT_URL="https://github.com/proot-me/proot/releases/download/v5.3.0/proot-v5.3.0-x86_64-static"
            PROOT_SHA="d1eb20cb201e6df08d707023efb000623ff7c10d6574839d7bb42d0adba6b4da"
            PROOT_LOADER_OFFSET="1067536"
            PROOT_LOADER_SIZE="8872"
            PROOT_LOADER_SHA="ca5279447ed4693b5e66e6eb1228da65a7c9c3b2fe23953c143216b55b7b9839"
            ALPINE_URL="https://dl-cdn.alpinelinux.org/alpine/v3.20/releases/x86_64/alpine-minirootfs-3.20.3-x86_64.tar.gz"
            ALPINE_SHA="d4e6fd67dcf75e40c451560ac7265166c2b72a0f38ddc9aae756a7de3d1efa0c"
            DOCKER_PLATFORM="linux/amd64"
            DOCKER_IMAGE_ARCH="amd64"
            ALPINE_BASE_IMAGE="alpine:3.20.3"
            PROOT_FILE_MARKER="x86-64"
            ANDROID_ABI="x86_64"
            PROOT_JNI_RELATIVE_PATH="$ANDROID_ABI/libproot.so"
            PROOT_LOADER_JNI_RELATIVE_PATH="$ANDROID_ABI/libproot-loader.so"
            ;;
        *)
            echo "unsupported runtime architecture: ${target:-<empty>} (supported: arm64, x86_64)" >&2
            return 1
            ;;
    esac

    export RUNTIME_ARCH PROOT_URL PROOT_SHA PROOT_LOADER_OFFSET PROOT_LOADER_SIZE
    export PROOT_LOADER_SHA ALPINE_URL ALPINE_SHA DOCKER_PLATFORM
    export DOCKER_IMAGE_ARCH ALPINE_BASE_IMAGE PROOT_FILE_MARKER ANDROID_ABI
    export PROOT_JNI_RELATIVE_PATH PROOT_LOADER_JNI_RELATIVE_PATH
}
