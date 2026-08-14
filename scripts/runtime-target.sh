#!/usr/bin/env bash
# Runtime build target configuration. Sourcing this file only declares
# configure_runtime_target; callers explicitly choose and configure a target.

configure_runtime_target() {
    local target="${1:-}"
    local termux_base="https://packages.termux.dev/apt/termux-main"

    case "$target" in
        arm64)
            RUNTIME_ARCH="arm64"
            PROOT_PACKAGE_URL="$termux_base/pool/main/p/proot/proot_5.1.107.89_aarch64.deb"
            PROOT_PACKAGE_SHA="ec9fe38c50cfd49dd31fe360ffbcc3124a945dc1ea16293a8a769303dd724f46"
            TALLOC_PACKAGE_URL="$termux_base/pool/main/libt/libtalloc/libtalloc_2.4.3_aarch64.deb"
            TALLOC_PACKAGE_SHA="ac81ad623d74c209718b9f3acb2dd702cc8a88c431e820d212229910b4db29da"
            ANDROID_SHMEM_PACKAGE_URL="$termux_base/pool/main/liba/libandroid-shmem/libandroid-shmem_0.7_aarch64.deb"
            ANDROID_SHMEM_PACKAGE_SHA="0da3a24d558b93c92bcf8d611e0826a99ff96e396b148e6cdf33b47c47c57ff6"
            PROOT_SHA="7da118895e971ea9fba4bb250b28af0f8db2edcbfdbaa8075cc645a0d7cf16fe"
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
            ;;
        x86_64)
            RUNTIME_ARCH="x86_64"
            PROOT_PACKAGE_URL="$termux_base/pool/main/p/proot/proot_5.1.107.89_x86_64.deb"
            PROOT_PACKAGE_SHA="0d76da0515f38dfb2217f647b0d79fcd61b38f80e25cbf2d39237697b02dd016"
            TALLOC_PACKAGE_URL="$termux_base/pool/main/libt/libtalloc/libtalloc_2.4.3_x86_64.deb"
            TALLOC_PACKAGE_SHA="7ca2eaae2e53b28228a01301bc410b62845403d6317c25b8e0a7f40681de0628"
            ANDROID_SHMEM_PACKAGE_URL="$termux_base/pool/main/liba/libandroid-shmem/libandroid-shmem_0.7_x86_64.deb"
            ANDROID_SHMEM_PACKAGE_SHA="ffa9e4c87467b158b148d0ff92dda796aa038276c2075af3269cdcdb06f25797"
            PROOT_SHA="d87c0bd62dfbd456826e8c3f968d4e9b264e6a912417e40a883900142d867051"
            PROOT_LOADER_SHA="914564ea1c66f50b38f18cac857fcf814c6b1ab027789178880fca1d530599b3"
            TALLOC_SHA="77be445f4ec245fff9c19e9874ebcf99618244cf48737f5fca938316daaa70da"
            ANDROID_SHMEM_SHA="092926060298acd3778e6239033d7aef1280dcb59aebe021a3719612e6a3465f"
            ALPINE_URL="https://dl-cdn.alpinelinux.org/alpine/v3.22/releases/x86_64/alpine-minirootfs-3.22.5-x86_64.tar.gz"
            ALPINE_SHA="4b4daa9fe2fc696c4919c4412a4c3d3e770d8fb70292a004a2c72f5096175282"
            DOCKER_PLATFORM="linux/amd64"
            DOCKER_IMAGE_ARCH="amd64"
            ALPINE_BASE_IMAGE="alpine:3.22.5"
            PROOT_FILE_MARKER="x86-64"
            ANDROID_ABI="x86_64"
            ;;
        *)
            echo "unsupported runtime architecture: ${target:-<empty>} (supported: arm64, x86_64)" >&2
            return 1
            ;;
    esac

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
