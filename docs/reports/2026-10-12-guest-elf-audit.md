# Guest rootfs ELF page-alignment spot audit — 2026-10-12

**Static evidence, not Android 16 KB runtime acceptance.** Read-only scan of the
locally generated ARM64 `rootfs.tar.gz` (SHA-256
`e343c38b3a9f001d65930d7e7b3fa746cd57c09c53eb63286df7d960a67ff491`).
The scan read ELF headers and PT_LOAD program headers from regular TAR members,
without extracting or changing the rootfs. Local JSON log:
`/tmp/seed-guest-elf-audit.json` (not a committed artifact).

| Finding | Count |
| --- | ---: |
| ELF regular files found | 158 |
| ARM64 ELF64 little-endian files with all PT_LOAD segments >= 16 KiB aligned and congruent | 156 |
| ELF files for another architecture (not candidates for ARM64 guest loading) | 2 |
| ARM64 ELF files failing the scoped PT_LOAD check | 0 |

The two foreign-architecture binaries are vendored Pi clipboard Node modules:
`clipboard-linux-riscv64-gnu/clipboard.linux-riscv64-gnu.node` and
`clipboard-linux-x64-gnu/clipboard.linux-x64-gnu.node`. Their presence is not proof
they run on ARM64, and their distribution/licensing belongs in the guest inventory.
The script accepted only ELF64 little-endian ARM64 executable/shared-object program
headers with a normal table. It checked segment `p_align >= 16384`, power-of-two
alignment and file-offset/virtual-address congruence modulo alignment and 16 KiB.
It did **not** validate guest dynamic linker or system page-size assumptions,
PE/32-bit or lazily downloaded binaries, symlink targets, runtime-created code,
all native Android JNI separately, or actual launch on a 16 KB device. Source
rootfs contents can change when regenerated; repeat the audit on the exact final
signed-release image and retain its hashes.

This narrows the missing **guest ELF alignment** evidence from the [readiness
review](../release/google-play-readiness.md). It does **not** close functional
16 KB acceptance. Test PRoot, Python/Node/native extensions, terminal PTY,
agent flow and generated app on a device or emulator reporting `getconf PAGE_SIZE`
= `16384`; use the [device acceptance sheet](../release/google-play-device-acceptance.md).
No build constant, target API, runtime asset or native dependency changed.
