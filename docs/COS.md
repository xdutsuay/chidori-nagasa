# On-device computer (COS)

COS gives the local model (and the Computer screen terminal) a real Linux
userland on the phone: **Alpine Linux 3.24.3** run through **proot** with no
root, no Termux install and no extra APK. It is opt-in: nothing is downloaded
or executed until the user enables **Computer**.

> Status: the code path is covered by JVM/Robolectric tests (fake proot). It
> has **not** yet been proven on a device or emulator; `$ORIGIN` RUNPATH
> resolution and the seccomp fallback are unverified on real kernels.

## Pieces

| Piece | Where |
|---|---|
| proot 5.1.107.102 (Termux), loader, libtalloc, libandroid-shmem | `app/src/main/jniLibs/{arm64-v8a,x86_64}/lib*.so` → installed into `nativeLibraryDir` (the only exec-able location under Android W^X; `useLegacyPackaging`/`extractNativeLibs` keep them on disk) |
| argv/env builder (pure) | `computer/ProotCommand.kt` |
| download + sha256 verify + atomic install | `computer/CosRootfsBootstrap.kt`, `computer/CosProvisioning.kt` |
| runtime (provision, exec, seccomp retry) | `computer/LocalComputerRuntime.kt` (logcat tag `COS`) |
| agent tool `cos_exec` | `tools/CosExecTool.kt` → `computer/CosAgentBridge.kt` |
| default-on rule | `tools/CosToolDefaults.kt`, `storage/StoragePreferences.kt` |
| system-prompt note | `computer/CosPromptNote.kt`, injected in `ModelRuntime.createSessionWithParams` |
| licenses | `app/src/main/assets/licenses/` (+ `NOTICE`) |

## Provisioning

1. Arch comes from the installed ABI dir (`lib/arm64` → `aarch64`, `lib/x86_64` → `x86_64`).
2. Download over HTTPS from
   `https://dl-cdn.alpinelinux.org/alpine/v3.24/releases/<arch>/alpine-minirootfs-3.24.3-<arch>.tar.gz`
   into `files/cos/cache/*.part` (32 MB cap).
3. Verify sha256 **before** extracting:

   | arch | sha256 |
   |---|---|
   | aarch64 | `4d3f80afaebd447c5591a63a73bde380a149fe8b2d85488cafabb2ff97551f50` |
   | x86_64 | `f8e1cafba9b1b283b5d30d06f9885c8d2bc161ffe9083ade8e2e157813a53407` |

   Mismatch → the download, any partial dir and any rootfs are deleted and the
   Computer screen shows `Alpine 3.24.3 (<arch>) checksum mismatch: expected …, got …`.
4. Extract into `files/cos/rootfs.partial`, write `/etc/resolv.conf`
   (`nameserver 1.1.1.1`, `8.8.8.8`), `/tmp`, `/root`, and the ready marker
   (`version=3.24.3`, `arch`, `sha256`), then rename to `files/cos/rootfs`.
5. A marker with another version/arch (e.g. the old 3.20 or offline-stub
   installs) makes the rootfs "not present"; the next setup wipes it and
   reprovisions. There is no offline stub fallback.

Deletes never follow symlinks (the rootfs is full of absolute links).

## Exec

Every command (terminal, `cos_exec`, lexer) runs as guest `/bin/sh -c <line>`:

```
<nativeLibDir>/libproot.so --kill-on-exit --link2symlink -0 -L
  -r <files>/cos/rootfs
  -b /dev -b /proc -b /sys
  -b /dev/urandom:/dev/random -b /proc/self/fd:/dev/fd -b <rootfs>/tmp:/dev/shm
  -w <guest cwd>
  /usr/bin/env -i HOME=/root USER=root PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
     TERM=xterm-256color LANG=C.UTF-8 TMPDIR=/tmp
  /bin/sh -c <line>
```

Host environment for proot:

| Variable | Value | Why |
|---|---|---|
| `PROOT_LOADER` | `<nativeLibDir>/libproot-loader.so` | Termux build has no bundled loader; mandatory |
| `PROOT_TMP_DIR` | `<cacheDir>/proot-tmp` | writable, app-private |
| `PROOT_L2S_DIR` | `<files>/cos/l2s` | `--link2symlink` metadata |
| `LD_LIBRARY_PATH` | `<nativeLibDir>` | belt and braces for `$ORIGIN` RUNPATH |
| `PROOT_NO_SECCOMP` | `1` only after fallback | see below |
| `LD_PRELOAD` | removed | never leak host preloads |

stdin is closed, stdout/stderr are capped at 256 KiB each, timeout is 120 s
(exit 124). The agent's working directory is guest `/root`.

**Seccomp fallback.** If proot exits non-zero and its own stderr (`proot …`
lines) reports `execve … EPERM / Operation not permitted` or suggests
`PROOT_NO_SECCOMP`, the command is rerun once with `PROOT_NO_SECCOMP=1`,
logged under `COS`, and, if that run works, the choice is persisted
(`computer.proot_no_seccomp`) so later commands go straight to it.

If any of the four libraries is missing from `nativeLibraryDir`, exec fails
with exit 127 `proot runtime missing from APK` — it never falls back to the
Android host shell.

## Chat integration

- `cos_exec` is **on by default** whenever Computer is enabled, for new and
  existing chats. An explicit off — the per-chat/model toggle or the global
  switch in Settings → Tools — is respected. With Computer disabled the tool is
  never offered.
- Every local chat session gets a `## On-device computer (COS)` note appended
  to its system prompt (once, in the stable prefix) while Computer is enabled:
  what COS is, `apk add`, `/root`, limits (no systemd/services, no GUI apps
  yet). If Alpine is not installed the note tells the model to send the user
  to the Computer screen. Node-mode one-shots don't get it. Cloud/desktop
  routes are not covered yet.

## Troubleshooting

| Symptom | Likely cause / fix |
|---|---|
| `checksum mismatch` | corrupted or tampered download / captive portal; retry on another network |
| `proot runtime missing from APK` | APK built without jniLibs or for another ABI |
| `[cos] retrying with PROOT_NO_SECCOMP=1` | kernel seccomp filter blocks proot's ptrace path; expected on some devices |
| `CANNOT LINK EXECUTABLE … libtalloc.so` | `$ORIGIN` RUNPATH not honoured; `LD_LIBRARY_PATH` should cover it — report with `adb logcat -s COS` |
| `apk add` DNS errors | check `/etc/resolv.conf` in the guest; it is rewritten if missing |
| exit 124 | command exceeded 120 s; run long jobs in smaller steps |

Logs: `adb logcat -s COS`.
