# chidori-nagasa

<p align="center">
<img src="logo.png" alt="chidori-nagasa logo" width="120"/>
</p>

<p align="center">
<strong>On-device LLM chat + LAN companion for the <a href="https://kaustubhtripathi.com/public/lab/lclreason/">chidori</a> desktop IDE.</strong>
</p>

<p align="center">
<a href="https://github.com/xdutsuay/chidori-nagasa/releases">Download APK</a>
·
<a href="https://kaustubhtripathi.com/public/lab/lclreason/#download">Desktop downloads</a>
·
<a href="WIRE_CONTRACT.md">Wire contract</a>
·
<a href="NOTICE.md">Attribution</a>
</p>

---

**chidori-nagasa** is the Android companion to [chidori](https://kaustubhtripathi.com/public/lab/lclreason/) — an AI-powered desktop IDE. On your phone it runs GGUF models locally (offline, private). When paired with a chidori desktop on the same LAN, it can monitor Ask / Agent / Plan / Debug runs, chat through the desktop's models, and optionally offer the phone as an inference node.

Built as a substantial fork of [LM Playground](https://github.com/andriydruk/LMPlayground) by Andriy Druk (MIT). Inference is powered by [llama.cpp](https://github.com/ggml-org/llama.cpp). See [`NOTICE.md`](NOTICE.md) and [`UPSTREAM_DIVERGENCE.md`](UPSTREAM_DIVERGENCE.md) for attribution and what changed.

> **Desktop source** (`lclreason`) stays private. Public desktop binaries ship from [xdutsuay/chidori](https://github.com/xdutsuay/chidori/releases). This Android repo is the open companion client.

## Why this fork exists

LM Playground already nails on-device chat. chidori-nagasa keeps that working and adds the pieces that make a phone useful next to a desktop AI IDE:

| Area | What we added |
|------|----------------|
| **LAN pairing** | mDNS (`_chidori._tcp`) + manual `host:8027`, pairing codes, bearer auth |
| **Coordinator monitor** | Live status and Ask / Agent / Plan / Debug run activity |
| **Remote chat** | Chat through the desktop's local/remote LLMs from the phone |
| **Phone as node** | Opt-in foreground service hosting an OpenAI-compatible facade so the desktop can route work to the phone |
| **Windows native builds** | Gradle probes for `glslc.exe`, Vulkan SDK / SPIRV headers, `-PggmlVulkan=off` escape hatch |

The wire protocol, product scope, and engineering rules live in [`CHIDORI_PROTOCOL.md`](CHIDORI_PROTOCOL.md), [`WIRE_CONTRACT.md`](WIRE_CONTRACT.md), [`PRD.md`](PRD.md), and [`ROADMAP.md`](ROADMAP.md). Desktop implementers: start at [`DESKTOP_HANDOFF.md`](DESKTOP_HANDOFF.md).

## Download

Grab a debug-signed APK from [Releases](https://github.com/xdutsuay/chidori-nagasa/releases) (no Play Store account). Current app version: **2.1.2**.

Sideload notes:

1. Enable **Install unknown apps** for your browser/file manager.
2. Open the APK and install.
3. For companion features, also install [chidori desktop](https://kaustubhtripathi.com/public/lab/lclreason/#download) and pair over Wi-Fi (Settings → Companion on desktop; Chidori Desktop in the Android app).

## Features

### On-device (from LM Playground, kept working)

- Offline inference — no cloud, no API keys for local chat
- Vision / image input for capable models (Gemma, Qwen 3.5, Ministral, …)
- Rich markdown replies, reasoning-model "thinking" sections
- Optional tools (web search, fetch, JS) — off by default, per model
- Saved system prompts, chat history, custom GGUF imports
- Background downloads with resume, SAF storage picker
- ARM64 optimizations (KleidiAI / OpenMP)
- Large-screen layout for tablets, foldables, Chromebooks

### Companion (chidori-specific)

- Pair with chidori desktop over LAN (mDNS or manual host:port)
- Monitor coordinator status and runs (read-only in v1)
- Remote chat through the desktop's attached models
- Opt-in **phone as inference node** (foreground service + notification)

## Supported models

| Family | Sizes | Provider |
|--------|-------|----------|
| GPT-OSS | 20B | OpenAI |
| Qwen 3.5 | 0.8B, 2B, 4B (all vision) | Alibaba |
| Qwen 3 | 0.6B, 1.7B, 4B | Alibaba |
| Gemma 4 | E2B, E4B (vision), 12B | Google |
| Gemma 3n | E2B, E4B | Google |
| Gemma 3 | 1B, 4B (vision) | Google |
| Nemotron 3 Nano | 4B | NVIDIA |
| Granite 4.1 | 3B, 8B | IBM |
| Granite 4.0 | Micro, H-Tiny | IBM |
| DeepSeek R1 Distill | 1.5B, 7B | DeepSeek |
| Phi-4 mini | 3.8B | Microsoft |
| LFM2.5 Thinking | 1.2B | Liquid AI |
| Ministral 3 | 3B, 8B (Instruct & Reasoning, all vision) | Mistral |
| Llama 3.2 | 1B, 3B | Meta |
| Llama 3.1 | 8B | Meta |

<details>
<summary>Legacy models</summary>

| Family | Sizes | Provider |
|--------|-------|----------|
| Qwen 2.5 | 0.5B, 1.5B | Alibaba |
| Phi 3.5 mini | 3.8B | Microsoft |
| Mistral v0.3 | 7B | Mistral |
| Gemma 2 | 9B | Google |

</details>

Most models use Q4_K_M; Qwen 3.5 uses Q3_K_M; GPT-OSS ships MXFP4. Catalog: [`ModelInfoProvider.kt`](app/src/main/java/com/druk/lmplayground/models/ModelInfoProvider.kt).

## Build

**Prerequisites**

- Android Studio [2024.3.1+](https://developer.android.com/studio/releases)
- NDK 27.2.12479018
- CMake 3.31.6

```bash
git clone --recurse-submodules https://github.com/xdutsuay/chidori-nagasa.git
cd chidori-nagasa
```

Open the folder in Android Studio and Run `app`, or from the CLI:

```bash
# macOS / Linux (Vulkan host deps — see app/build.gradle.kts for paths)
brew install spirv-headers vulkan-headers   # macOS example
sdkmanager "cmake;3.31.6"
export PATH="$ANDROID_HOME/cmake/3.31.6/bin:$PATH"
./gradlew app:lintDebug app:testDebugUnitTest app:assembleDebug

# Windows without MSVC / Vulkan shader host tools:
./gradlew.bat app:assembleDebug -PggmlVulkan=off
```

Before merging changes that touch `net/coordinator`, `inference/`, or JNI: read [`CHIDORI_PROTOCOL.md`](CHIDORI_PROTOCOL.md) §3.

## Repo map

| Doc | Purpose |
|-----|---------|
| [`CHIDORI_PROTOCOL.md`](CHIDORI_PROTOCOL.md) | Governance + compatibility rules with desktop |
| [`WIRE_CONTRACT.md`](WIRE_CONTRACT.md) | HTTP / pairing / chat contract |
| [`DESKTOP_HANDOFF.md`](DESKTOP_HANDOFF.md) | Brief for the desktop server half |
| [`PRD.md`](PRD.md) / [`ROADMAP.md`](ROADMAP.md) | Product scope and phases |
| [`TEST_PLAN.md`](TEST_PLAN.md) | Regression / release gates |
| [`UPSTREAM_DIVERGENCE.md`](UPSTREAM_DIVERGENCE.md) | Diff vs LM Playground |
| [`ARCHITECTURE.md`](ARCHITECTURE.md) | High-level layout |

## Contributing

This repo follows [`CHIDORI_PROTOCOL.md`](CHIDORI_PROTOCOL.md). Please open an issue before large wire-contract or native-layer changes. PRs that touch companion transport should keep `net/coordinator` boundaries intact and include unit tests for the transport layer.

## License

[MIT](LICENSE) — derivative of LM Playground (MIT). See [`NOTICE.md`](NOTICE.md).

## Acknowledgments

- [LM Playground](https://github.com/andriydruk/LMPlayground) — Andriy Druk
- [llama.cpp](https://github.com/ggml-org/llama.cpp) / [llama.cpp-android](https://github.com/andriydruk/llama.cpp-android)
- Models: GGUF builds from [Hugging Face](https://huggingface.co/), each under their own licenses
- Companion to [chidori](https://kaustubhtripathi.com/public/lab/lclreason/) (desktop binaries: [xdutsuay/chidori](https://github.com/xdutsuay/chidori))
