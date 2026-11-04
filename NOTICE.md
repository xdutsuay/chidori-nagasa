# Notice

chidori-nagasa is a derivative work based on:

**LM Playground**
Copyright (c) 2024 Andriy Druk
https://github.com/andriydruk/LMPlayground
Licensed under the MIT License (see `LICENSE`).

LM Playground is itself built on:

**llama.cpp**
https://github.com/ggml-org/llama.cpp
(GGML/llama.cpp project and contributors)

LM Playground's native inference layer is vendored from:

**llama.cpp-android** (andriydruk fork)
https://github.com/andriydruk/llama.cpp-android

Models used with this app are in GGUF format, generally sourced from Hugging
Face (https://huggingface.co/), each under their own respective licenses —
this app does not relicense model weights.

chidori-nagasa is the companion app to **chidori** (desktop IDE). Product page:
https://kaustubhtripathi.com/public/lab/lclreason/ — public desktop binaries:
https://github.com/xdutsuay/chidori. The projects are governed by
`CHIDORI_PROTOCOL.md` in this repo.

## What changed from upstream

Tracked in `UPSTREAM_DIVERGENCE.md` as changes accumulate — kept separate
from this notice so attribution stays stable while the divergence log grows.

## On-device computer (COS) native components

The APK bundles prebuilt Termux builds of **PRoot 5.1.107.102** (GPL-2.0),
**talloc 2.5.0** (LGPL-3.0) and **libandroid-shmem 0.7** (BSD-3-Clause) as
`lib/<abi>/lib*.so`. Full license texts, binary/source URLs and sha256 values
ship in the APK under `assets/licenses/` (see
[`app/src/main/assets/licenses/NOTICE`](app/src/main/assets/licenses/NOTICE)).
Alpine Linux is downloaded at runtime, not bundled. Details: [`docs/COS.md`](docs/COS.md).
