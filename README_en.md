# PhonePilot

**A substantially improved Android assistant derived directly from [Luokavin/AutoGLM-For-Android](https://github.com/Luokavin/AutoGLM-For-Android), currently built for the official OpenAI API.**

PhonePilot retains its upstream Android, Shizuku, keyboard and overlay foundation while improving model actions, accessibility observations, continuous conversations, history and in-app proxy support. Its earlier technical lineage is [zai-org/Open-AutoGLM](https://github.com/zai-org/Open-AutoGLM). Luokavin's MIT copyright notice remains intact in [LICENSE](LICENSE).

[中文](README.md) | English · [Changes from upstream](docs/changes-from-upstream.md)

## What improved

- **Native tool calls and batches:** replaces legacy text-action parsing with official Responses `computer` and Android function tools, ordered action batches, tool results and execution preflight.
- **Accessibility text and precise actions:** compact semantic observations, local search, node clicks, input and scrolling. Reliable text reduces screenshot uploads; visual observations supplement it when needed. Ordinary clicks validate observation identity and reject stale targets.
- **Named targeting in dynamic lists:** explicit `android_ui.click_match` relocates an observed target in its original, uniquely identified scroll container using literal conditions. Exact matching or explicit prefixes including the name delimiter require uniqueness in both observations; missing or ambiguous targets cause no click.
- **Continuous chat and session lists:** continue from the main screen or overlay after a task finishes, or explicitly start a new conversation. Instructions added during execution are handled at safe checkpoints. Draft pauses and manual pauses are managed separately.
- **Visible feedback and human interaction:** optional model-provided commentary, input/output token counts, and unified text questions, choices, confirmations and manual takeover.
- **Detailed history and exports:** multi-run conversation timelines show messages, tool arguments, results, errors, durations and states. Before/after images are distinguished; completed actions annotate their actual before-image. Paginated export is supported; missing historical information is not fabricated.
- **Screenshots on demand:** configurable image dimensions, coordinate mapping and lossless encoding. Resizing changes resolution; “lossless” describes encoding. No fixed token or cost reduction is promised.
- **Built-in subscriptions and node selection:** import supported Clash/Mihomo YAML and choose a node. A separate Mihomo process supplies a local proxy for this app without taking over the device VPN.

## Official OpenAI API support

The endpoint is fixed to `https://api.openai.com/v1/responses`. Supply your own official OpenAI API key and an account entitled to the selected model and tools.

| Setting | Current implementation |
| --- | --- |
| Models | `gpt-6.1-terra`, `gpt-6.1-sol` |
| Terra reasoning effort | `none`, `low`, `medium`, `high`, `xhigh`, `max` |
| Sol reasoning effort | `low`, `medium`, `high`, `xhigh`, `max` |
| Default effort | `medium` |

These are client allowlist and effort checks, not a guarantee of account access. See the official [model documentation](https://developers.openai.com/api/docs/models) and [computer use guide](https://developers.openai.com/api/docs/guides/tools-computer-use).

This version does not support arbitrary OpenAI-compatible providers, third-party relays or self-hosted GLM. It does not use ChatGPT login or private protocols. PhonePilot is a community derivative, not an official OpenAI, QQ or Tencent product.

## Get started

Requirements: an **arm64-v8a** Android 7.0 / API 24+ device, a usable OpenAI API account and network connection, and [Shizuku](https://shizuku.rikka.app/).

1. Install the APK, start Shizuku following its official instructions, and grant PhonePilot access. Initial setup may require wireless debugging or a computer, depending on the device and Android version.
2. Grant overlay permission and enable the PhonePilot keyboard as prompted for text input.
3. Enable “PhonePilot UI tools” accessibility service for semantic tools. Incomplete application trees may still require screenshots and coordinate actions.
4. Enter your own API key in model settings, choose a model and effort, and test the connection.
5. If needed, import an HTTPS YAML subscription in the in-app proxy settings, select a node and connect before starting a task. See [proxy support and behavior](docs/proxy.md).
6. Describe a task and follow progress in the main screen or overlay. Pause, resume, stop, or answer questions and takeover requests. Continue the conversation afterward or start a new one.

Proxy reachability does not validate API keys, model access or billing. An enabled proxy that fails does not silently fall back to a direct connection.

## Data and operation boundaries

API keys and proxy subscriptions use Android encrypted settings and are not included in source or APKs. Relevant screen text, instructions and requested screenshots are sent to OpenAI. History, tool traces and images may remain on the phone. Use devices and content you are authorized to access; review exports for sensitive information.

Models can misunderstand tasks or screens. Manual takeover supports login and verification flows; explicitly scope authorization for actions such as payments or sending messages. Responses continuation after process death is not guaranteed. See [implementation notes](docs/phonepilot.md) for tool boundaries.

## Build from source

Use JDK 21, Android SDK Platform 35, Build Tools 36.0.0 and PowerShell 7. The Gradle compilation target is JVM 11. Use the included Gradle Wrapper. From the repository root, prepare the pinned proxy core:

```powershell
pwsh -NoLogo -NoProfile -File ./scripts/fetch-mihomo.ps1
```

The script downloads Mihomo v1.19.33 for Android arm64 and checks SHA-256 for both the archive and extracted executable. Then build and validate:

```powershell
# Windows / PowerShell 7
./gradlew.bat :automation-core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

```sh
# macOS / Linux
sh ./gradlew :automation-core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

The debug APK is under `app/build/outputs/apk/debug/`. The display name is PhonePilot. The package remains `com.kevinluo.autoglm`, with version `1.0.2` / `102`; some artifact filenames retain AutoGLM. In-place updates also require matching signatures; a self-build does not automatically obtain an existing installation's signature. Configure production signing before distribution and keep signing keys private.

Developer constraints are in [AGENTS.md](AGENTS.md) and the [architecture contract](docs/architecture-contract.md). `:automation-core` is pure Kotlin/JVM; Android capabilities remain in the application layer.

## Verification scope

Recorded local validation on 2026-11-07 passed **509 automated tests** (19 core and 490 Android JVM), the debug build and lint. Lint still reports warnings. This describes a specific source snapshot, not universal device or app coverage.

Observed QQ checks covered reading visible conversation names, opening a named target, and finding and clicking it after a person scrolled it to a different position in the same list. The agent used text observations without screenshot tool calls on these successful paths and sent no chat messages. A choice-panel theme crash was fixed; the same-device choice, overlay minimize/expand and continuation path then passed.

These checks do not cover every new-message reorder, duplicate name, offscreen target, keyboard, Android version, service reconnect or process recreation. Automated rejection tests do not establish physical-device coverage. See [validation scope](docs/validation.md). Private chat trees and phone evidence images are not public examples.

## Attribution and licensing

- **Direct upstream:** [Luokavin/AutoGLM-For-Android](https://github.com/Luokavin/AutoGLM-For-Android), MIT; Luokavin's copyright notice is preserved.
- **Earlier technical lineage:** [zai-org/Open-AutoGLM](https://github.com/zai-org/Open-AutoGLM).
- **Separate proxy component:** [MetaCubeX/mihomo](https://github.com/MetaCubeX/mihomo), v1.19.33, GPL-3.0. Its license and corresponding-source obligations are separate from the MIT app source. Distributions containing the core must include GPL license and exact corresponding-source information; see [NOTICE](NOTICE) and [proxy documentation](docs/proxy.md).

Thanks to the upstream authors and dependency maintainers. This fork does not claim upstream work as independently original; see [changes from upstream](docs/changes-from-upstream.md).
