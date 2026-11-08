# PhonePilot

**A substantially improved Android assistant derived directly from [Luokavin/AutoGLM-For-Android](https://github.com/Luokavin/AutoGLM-For-Android), currently built for the official OpenAI API.**

PhonePilot retains its upstream Android, Shizuku, keyboard and overlay foundation while improving model actions, accessibility observations, continuous conversations, history and in-app proxy support. Its earlier technical lineage is [zai-org/Open-AutoGLM](https://github.com/zai-org/Open-AutoGLM). Luokavin's MIT copyright notice remains intact in [LICENSE](LICENSE).

[中文](README.md) | English · [Changes from upstream](docs/changes-from-upstream.md)

## Download

[Download the locally built 1.0.3 APK](https://github.com/xiaoiecc/PhonePilot/releases/download/v1.0.3/PhonePilot-1.0.3-capsule-background-candidate.apk) · [Release notes](https://github.com/xiaoiecc/PhonePilot/releases/tag/v1.0.3)

This is a locally built and device-tested debug-signed candidate, without API keys or subscriptions. Updates require matching signatures. Background execution is currently enabled only for the tested Android 12 / API 31 profile; other versions retain foreground operation.

## What improved

- **New floating capsule:** persistent latest progress, expandable two-line summary, animated interaction controls and an input field that grows up to six lines. Sending while paused continues the same conversation. Background runs keep updating the capsule.
- **Foreground/background task migration:** Shizuku virtual displays move the existing app task, with background screenshots, input and accessibility operations. Tap the background capsule to return to the foreground. API 31 only; app redirects and background IME compatibility remain limited.

- **Native tool calls and batches:** replaces legacy text-action parsing with the computer-style `android_computer` function and other Android function tools, ordered action batches, tool results and execution preflight.
- **Chat with multiple image attachments:** normal chat and hosted search/code stay in Chat until a phone tool is requested. Native gallery multi-selection preserves image order and compresses attachments without an additional four-image cap.
- **Accessibility text and precise actions:** compact semantic observations, local search, node clicks, input and scrolling. Reliable text reduces screenshot uploads; visual observations supplement it when needed. Ordinary clicks validate observation identity and reject stale targets.
- **Named targeting in dynamic lists:** explicit `android_ui.click_match` relocates an observed target in its original, uniquely identified scroll container using literal conditions. Exact matching or explicit prefixes including the name delimiter require uniqueness in both observations; missing or ambiguous targets cause no click.
- **Continuous chat and session lists:** continue from the main screen or overlay after a task finishes, or explicitly start a new conversation. Instructions added during execution are handled at safe checkpoints. Draft pauses and manual pauses are managed separately.
- **Visible feedback and human interaction:** required commentary for each local tool call (one sentence per batch), persistent latest progress, input/output token counts, and unified text questions, choices, confirmations and manual takeover.
- **Detailed history and exports:** multi-run conversation timelines show messages, tool arguments, results, errors, durations and states. Before/after images are distinguished; completed actions annotate their actual before-image. Paginated export is supported; missing historical information is not fabricated.
- **Screenshots on demand:** configurable dimensions and coordinate mapping with a single WebP encoding. Android 10 and newer use lossless encoding; older platforms use quality 100 WebP. Resizing changes resolution. No fixed token or cost reduction is promised.
- **Built-in subscriptions and node selection:** import supported Clash/Mihomo YAML and choose a node. A separate Mihomo process supplies a local proxy for this app without taking over the device VPN.

## Official OpenAI API support

Models can also use official `web_search` and `code_interpreter` tools with an automatic hosted Python container. Search citations have clickable source URLs and hosted calls appear in history; they require no local tool-output reply. Hosted Python cannot directly access or control the phone, and generated files are not automatically downloaded to it.

Transient connection, timeout and recoverable service errors use backoff with up to three retries after the initial attempt. Requests retain their original input without replaying phone actions; stop/steering interrupts both calls and backoff. Authentication, quota and certificate failures are not blindly retried. See [network recovery](docs/network-retries.md).

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

The debug APK is under `app/build/outputs/apk/debug/`. The display name is PhonePilot. The package remains `com.kevinluo.autoglm`, with version `1.0.3` / `103`; some artifact filenames retain AutoGLM. In-place updates also require matching signatures; a self-build does not automatically obtain an existing installation's signature. Configure production signing before distribution and keep signing keys private.

Developer constraints are in [AGENTS.md](AGENTS.md) and the [architecture contract](docs/architecture-contract.md). `:automation-core` is pure Kotlin/JVM; Android capabilities remain in the application layer.

## Verification scope

The 2026-11-08 candidate passed **588 JVM tests** (29 core and 559 Android JVM), four native capsule layout tests, the debug build and lint (zero errors, 366 warnings). Huawei Android 12 device checks covered task migration, background capture and accessibility clicks, paused instructions and a complete calculator task. See the [validation report and limitations](docs/capsule-background-validation-20261108.md).

Observed QQ checks covered reading visible conversation names, opening a named target, and finding and clicking it after a person scrolled it to a different position in the same list. The agent used text observations without screenshot tool calls on these successful paths and sent no chat messages. A choice-panel theme crash was fixed; the same-device choice, overlay minimize/expand and continuation path then passed.

These checks do not cover every new-message reorder, duplicate name, offscreen target, keyboard, Android version, service reconnect or process recreation. Automated rejection tests do not establish physical-device coverage. See [validation scope](docs/validation.md). Private chat trees and phone evidence images are not public examples.

## Attribution and licensing

- **Direct upstream:** [Luokavin/AutoGLM-For-Android](https://github.com/Luokavin/AutoGLM-For-Android), MIT; Luokavin's copyright notice is preserved.
- **Earlier technical lineage:** [zai-org/Open-AutoGLM](https://github.com/zai-org/Open-AutoGLM).
- **Separate proxy component:** [MetaCubeX/mihomo](https://github.com/MetaCubeX/mihomo), v1.19.33, GPL-3.0. Its license and corresponding-source obligations are separate from the MIT app source. Distributions containing the core must include GPL license and exact corresponding-source information; see [NOTICE](NOTICE) and [proxy documentation](docs/proxy.md).

Thanks to the upstream authors and dependency maintainers. This fork does not claim upstream work as independently original; see [changes from upstream](docs/changes-from-upstream.md).
