#!/usr/bin/env bash
# Native ARM64 runtime build target configuration.

configure_runtime_target() {
    local target="${1:-arm64}"
    local termux_base="https://packages.termux.dev/apt/termux-main"
    if [[ "$target" != "arm64" ]]; then
        echo "unsupported runtime architecture: ${target} (only native arm64 is supported)" >&2
        return 1
    fi
    RUNTIME_ARCH="arm64"
    PROOT_PACKAGE_URL="$termux_base/pool/main/p/proot/proot_5.1.107.92_aarch64.deb"
    PROOT_PACKAGE_SHA="1f1c983509701f6826f568482c70673ee453a9ba38c9f5fa445a472d6b7524e9"
    TALLOC_PACKAGE_URL="$termux_base/pool/main/libt/libtalloc/libtalloc_2.4.3_aarch64.deb"
    TALLOC_PACKAGE_SHA="ac81ad623d74c209718b9f3acb2dd702cc8a88c431e820d212229910b4db29da"
    ANDROID_SHMEM_PACKAGE_URL="$termux_base/pool/main/liba/libandroid-shmem/libandroid-shmem_0.7_aarch64.deb"
    ANDROID_SHMEM_PACKAGE_SHA="0da3a24d558b93c92bcf8d611e0826a99ff96e396b148e6cdf33b47c47c57ff6"
    PROOT_SHA="41b4ae7f8aa2eac38678c97b5bdb6a0503903aafa92eb8b377d377d4103d57cb"
    PROOT_LOADER_SHA="44ef39c1e1a18c09f6e4c4b5d6f8bba82d30596598bd155ec162d05c5122ff04"
    TALLOC_SHA="3c9b207c0a6ea2896b7523e03f55d9ab0d9e88baa115d4c32b84058ff4246fbb"
    ANDROID_SHMEM_SHA="84475798e07c8174dbbfaec70a827fdb02f19ffa69a589380c13e7507fd0e731"
    ALPINE_URL="https://dl-cdn.alpinelinux.org/alpine/v3.22/releases/aarch64/alpine-minirootfs-3.22.5-aarch64.tar.gz"
    ALPINE_SHA="3fbc6285032ed46821b511292633d7b2a6306a2e254f590e92bdafff56cf2f70"
    DOCKER_PLATFORM="linux/arm64"
    DOCKER_IMAGE_ARCH="arm64"
    ALPINE_BASE_IMAGE="alpine:3.22.5"
    PROOT_FILE_MARKER="ARM aarch64"
    ANDROID_ABI="arm64-v8a"
    PROOT_JNI_RELATIVE_PATH="$ANDROID_ABI/libproot.so"
    PROOT_LOADER_JNI_RELATIVE_PATH="$ANDROID_ABI/libproot-loader.so"
    TALLOC_JNI_RELATIVE_PATH="$ANDROID_ABI/libtalloc.so"
    ANDROID_SHMEM_JNI_RELATIVE_PATH="$ANDROID_ABI/libandroid-shmem.so"
    export RUNTIME_ARCH PROOT_PACKAGE_URL PROOT_PACKAGE_SHA TALLOC_PACKAGE_URL
    export TALLOC_PACKAGE_SHA ANDROID_SHMEM_PACKAGE_URL ANDROID_SHMEM_PACKAGE_SHA
    export PROOT_SHA PROOT_LOADER_SHA TALLOC_SHA ANDROID_SHMEM_SHA
    export ALPINE_URL ALPINE_SHA DOCKER_PLATFORM DOCKER_IMAGE_ARCH
    export ALPINE_BASE_IMAGE PROOT_FILE_MARKER ANDROID_ABI
    export PROOT_JNI_RELATIVE_PATH PROOT_LOADER_JNI_RELATIVE_PATH
    export TALLOC_JNI_RELATIVE_PATH ANDROID_SHMEM_JNI_RELATIVE_PATH
}
