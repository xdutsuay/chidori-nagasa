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

## Native binaries: provenance and modifications

The four `.so` files come from the Termux main apt repository (sha256 of each
`.deb`, the source archives and the shipped files are in
`app/src/main/assets/licenses/NOTICE`):

| Shipped | From `.deb` path | Package / source |
|---|---|---|
| `libproot.so` | `usr/bin/proot` | `proot_5.1.107.102_{aarch64,x86_64}.deb` — source https://github.com/termux/proot/archive/v5.1.107.102.zip, recipe https://github.com/termux/termux-packages/blob/master/packages/proot/build.sh |
| `libproot-loader.so` | `usr/libexec/proot/loader` | same package (`loader32` is not shipped) |
| `libtalloc.so` | `usr/lib/libtalloc.so.2.5.0` | `libtalloc_2.5.0_{aarch64,x86_64}.deb` — source https://www.samba.org/ftp/talloc/talloc-2.5.0.tar.gz |
| `libandroid-shmem.so` | `usr/lib/libandroid-shmem.so` | `libandroid-shmem_0.7_{aarch64,x86_64}.deb` — source https://github.com/termux/libandroid-shmem (v0.7) |

Debs live at `https://packages.termux.dev/apt/termux-main/pool/main/{p/proot,libt/libtalloc,liba/libandroid-shmem}/`.

Only the ELF dynamic section was edited (patchelf), on both ABIs:

| File | Change |
|---|---|
| `libproot.so` | `RUNPATH` `/data/data/com.termux/files/usr/lib` → `$ORIGIN`; `DT_NEEDED` `libtalloc.so.2` → `libtalloc.so` |
| `libtalloc.so` | `RUNPATH` → `$ORIGIN`; `DT_SONAME` `libtalloc.so.2` → `libtalloc.so` |
| `libandroid-shmem.so` | `RUNPATH` removed (it only needs `liblog.so`, `libc.so`) |
| `libproot-loader.so` | none (byte-identical to the `.deb` file) |

Equivalent commands: `patchelf --set-rpath '$ORIGIN' --replace-needed libtalloc.so.2 libtalloc.so libproot.so`,
`patchelf --set-rpath '$ORIGIN' --set-soname libtalloc.so libtalloc.so`, `patchelf --remove-rpath libandroid-shmem.so`.

The `lib*.so` names are needed because Android only extracts `lib*.so` into
`nativeLibraryDir`. `app/build.gradle.kts` lists all four in
`packaging.jniLibs.keepDebugSymbols`, so AGP does not strip them and the
packaged files keep the sha256 values published in NOTICE. Check an APK with
`unzip -p app.apk lib/arm64-v8a/libproot.so | sha256sum`.

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

**Timeout / Stop** (`computer/ProotTerminator.kt`). Termux proot's
`--kill-on-exit` only kills the remaining tracees when the *initial* tracee
exits. It does nothing if proot itself is killed: proot sets no
`PTRACE_O_EXITKILL` and ignores SIGTERM. SIGKILLing proot first would orphan
the guest tree outside path translation. So proot gets **SIGQUIT** first (its
handler kills every tracee and exits), then a 2 s grace period. Only after that
is it `destroyForcibly()`'d. Any guest pid from a `/proc` snapshot taken before
signalling (same pid *and* starttime, not a zombie) that is still alive is then
SIGKILLed. The snapshot covers proot's ppid-descendants **and** every process
whose `/proc/<pid>/status` `TracerPid` is proot (catches guests that
double-forked and were reparented), and is only taken while proot is alive.

proot's pid is **captured at launch**, right after `ProcessBuilder.start()`,
and stored with the running command: reflection first (`Process.pid()` on a
JVM, the private `pid` field on Android), else a diff of this app's `/proc`
children before/after the start (retried once after 100 ms if it finds no proot
child yet, since the fork may not have exec'd), under a process-wide launch lock. Logged as
`proot started pid=N (launch: reflection|/proc diff)` and, at termination,
`pid via launch`. A `/proc` scan for the sole `libproot.so` child is only the
last resort (`pid via /proc scan`); if that is ambiguous the process is just
`destroyForcibly()`'d. Stop runs this off the UI thread. Covered by
`ProotTerminationTest` (fake proot): SIGQUIT honoured, SIGQUIT ignored → force
kill + child SIGKILL, launch-time capture via both paths, TracerPid backstop,
/proc-scan last resort.

**Chat Stop during `cos_exec`.** Stop cancels the generating coroutine;
`ToolCallRunner` (used by `GenerationCoordinator`) then calls
`ToolRegistry.cancelInFlight()` — re-issued every 200 ms until the batch returns —
and skips any not-yet-started calls in the batch. `CosExecTool.cancelInFlight()`
cancels the coroutine running the command; `LocalComputerRuntime` waits on proot
with `runInterruptible`, so the cancellation unblocks it and proot is terminated
through the same `ProotTerminator` path as the Computer screen (SIGQUIT, 2 s grace,
force kill, descendant/TracerPid sweep), logged `terminate reason=chat-stop` /
`terminate[chat-stop]: …`. The tool card shows
`{"exit_code":130,"cancelled":true,"error":"cancelled: the user pressed Stop; …"}`.
Covered by `ProotTerminationTest` (cancel → terminator, returns in < 5 s with a
120 s timeout; SIGQUIT ignored → force kill).

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
- The toggle is also **enforced at execution time**: `ToolRegistry.executeToolCalls`
  (the only tool dispatch path) returns `{"error":"cos_exec is disabled in this
  chat; …"}` for a call to any disabled tool and never runs it, so a model that
  still emits `cos_exec` after the user switched it off (or Computer is off,
  which resolves the toggle to off) cannot reach the runtime.
- Every local chat session gets a `## On-device computer (COS)` note appended
  to its system prompt (once, in the stable prefix) while Computer is enabled:
  what COS is, `apk add`, `/root`, limits (no systemd/services, no GUI apps
  yet). If Alpine is not installed the note tells the model to send the user
  to the Computer screen. Node-mode one-shots don't get it. Cloud/desktop
  routes are not covered yet.
- The note's state is re-evaluated **every turn** in `GenerationCoordinator`
  (Computer flag, rootfs installed, effective `cos_exec` toggle and tool-calling
  support). If it changed since the session was last told, e.g. `cos_exec`
  turned off mid-chat or Alpine finished installing, a one-off
  `[System update: on-device computer (COS) changed]` block is prepended to that
  turn's model input (not to the saved message). No chat restart is needed. With
  `cos_exec` unavailable the note says not to offer or call it. A replayed
  session gets the current state in its system prompt.

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
