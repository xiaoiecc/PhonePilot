# PhonePilot

**基于 [Luokavin/AutoGLM-For-Android](https://github.com/Luokavin/AutoGLM-For-Android) 大幅改进的 Android 手机智能助手，当前专用于 OpenAI 官方 API。**

PhonePilot 是该项目的直接二次开发版本，继承其 Android 原生应用、Shizuku、键盘和悬浮窗基础，重点改进模型操作、无障碍观察、连续会话、历史记录和应用内代理。更早的技术来源是 [zai-org/Open-AutoGLM](https://github.com/zai-org/Open-AutoGLM)。原作者 Luokavin 的 MIT 版权声明完整保留在 [LICENSE](LICENSE)。

中文 | [English](README_en.md) · [与上游的具体差异](docs/changes-from-upstream.md)

## 下载当前版本

[下载 1.0.3 自编译 APK](https://github.com/xiaoiecc/PhonePilot/releases/download/v1.0.3/PhonePilot-1.0.3-capsule-background-candidate.apk) · [发布说明](https://github.com/xiaoiecc/PhonePilot/releases/tag/v1.0.3)

此包为本地编译并实测的 Debug 签名候选版，不含 API Key 或代理订阅。覆盖安装要求签名一致。后台能力当前仅启用实测的 Android 12 / API 31 档案，其他版本可继续使用前台操控。

## 主要改进

- **全新悬浮胶囊**：持续显示最新进度，两行摘要可展开全文；暂停、提问和人工协作时动画展开输入区，输入随文字增高至六行，发送后继续同一会话。后台运行仍同步更新胶囊。
- **同任务前后台切换**：通过 Shizuku 虚拟显示屏迁移当前应用任务，支持后台截图、坐标与无障碍操作，点击后台胶囊可回前台。仅 API 31 能力档案已启用；不承诺所有应用跳转或后台中文输入均兼容。

- **原生工具调用与批量操作**：从旧版文本动作解析转为 OpenAI Responses function tools，以 `android_computer` 提供电脑操作动作接口，支持有序批次、结果回传与执行前检查。
- **聊天与多图附件**：普通问答和云端搜索/代码执行留在 Chat；手机工具调用时才进入浮窗。原生图库支持多选，按顺序压缩发送和保存，不额外限制为四张。
- **无障碍文字观察与精确操作**：读取精简语义树、本地查找、节点点击、文字输入和滚动；文字可靠时减少截图上传，需要视觉信息时补充截图。普通点击验证观察身份，不盲用失效节点。
- **动态列表按名称重新定位**：显式 `android_ui.click_match` 在同一、可唯一识别的滚动容器内，按已观察的字面条件重新定位目标。支持精确匹配或带分隔符的显式前缀匹配；原观察和当前列表均须唯一，缺失或歧义时不点击。
- **连续对话与会话列表**：任务完成后保留上下文，主页和浮窗均可继续交流，也可明确开启新会话。运行中可补充要求，在安全检查点处理；草稿暂停与手动暂停分别管理。
- **可见反馈与人工协作**：每次本地工具调用提供中途说明（批次共用一句），持续显示最新说明，显示输入/输出 token 计数；统一支持文字提问、选项回复、确认和人工接管。
- **更完整的历史和导出**：按会话聚合多轮执行，展示消息、工具参数、结果、错误、耗时和状态；区分操作前后图片，并用真实完成的动作标注操作前图，支持分页导出。旧记录缺失的信息不伪造。
- **截图按需获取与尺寸控制**：按实际发送的图片尺寸映射坐标，单次 WebP 编码；Android 10 起使用无损模式，旧平台使用质量 100 的 WebP。缩放会改变分辨率；不承诺固定 token 或费用节省比例。
- **应用内代理订阅与节点选择**：导入受支持的 Clash/Mihomo YAML 订阅并选择节点；独立 Mihomo 进程只为本应用提供本地代理，不接管手机全局 VPN。

## OpenAI 官方 API 支持

模型也可按需使用官方 `web_search` 和 `code_interpreter`（自动云端 Python 容器）。搜索引用显示为可点击来源链接，服务端调用写入历史轨迹；它们不需要本地工具结果回传。云端代码不能直接操作手机或读取手机文件，生成文件暂不自动下载到手机。

临时连接故障、超时及可恢复的服务错误会退避重试，首次失败后最多重试 3 次；保留原请求，不重放手机动作，停止或改向会中断请求与退避。鉴权、额度和证书等错误不会盲目重试。见 [网络恢复说明](docs/network-retries.md)。

固定连接 `https://api.openai.com/v1/responses`，用户自行提供 OpenAI 官方 API Key，账户需具备相应模型和工具权限。

| 设置项 | 当前实现 |
| --- | --- |
| 模型 | `gpt-6.1-terra`、`gpt-6.1-sol` |
| Terra 思考档位 | `none`、`low`、`medium`、`high`、`xhigh`、`max` |
| Sol 思考档位 | `low`、`medium`、`high`、`xhigh`、`max` |
| 默认思考档位 | `medium` |

这些是当前客户端的白名单和档位校验，不保证所有账户均有权限。参考 [OpenAI 模型文档](https://developers.openai.com/api/docs/models)及 [computer use 文档](https://developers.openai.com/api/docs/guides/tools-computer-use)。

当前不支持通用 OpenAI-compatible 服务、第三方中转或自托管 GLM，也不通过 ChatGPT 登录或私有协议调用模型。PhonePilot 是社区二开项目，不是 OpenAI、QQ 或腾讯的官方产品。

## 安装与开始使用

要求 Android 7.0 / API 24 以上的 **arm64-v8a** 设备、可用的 OpenAI API 账户与网络，以及 [Shizuku](https://shizuku.rikka.app/)。

1. 安装 APK，按 Shizuku 官方说明启动服务，并向 PhonePilot 授权。首次设置可能需要无线调试或电脑辅助，取决于系统和设备。
2. 授予悬浮窗权限；按应用引导启用 PhonePilot 键盘，以支持文字输入。
3. 如需语义操作，启用“PhonePilot UI tools”无障碍服务。部分应用暴露的树不完整，仍需要截图和坐标操作。
4. 在模型设置中填写自己的 API Key，选择模型和思考档位并测试连接。
5. 如需代理，在“内置代理 · 订阅与节点”中导入 HTTPS YAML 订阅，选择节点后连接，再开始任务。支持范围见 [代理说明](docs/proxy.md)。
6. 输入任务，在主页或浮窗查看进度；按需暂停、继续、停止，或回复 Agent 的提问和接管请求。结束后可继续同一会话，或开启新会话。

代理连通性检测不等于 API Key、模型权限或计费验证。已启用的代理连接失败时不会静默绕过代理。

## 数据与使用边界

API Key 和代理订阅配置使用 Android 加密设置保存，不随源码或 APK 提供。执行时会向 OpenAI 发送所需的屏幕文字、任务内容和按需获取的截图；历史、工具轨迹和图片可能在手机本地保留。请仅在有权访问的设备和内容上运行，导出前检查敏感信息。

模型可能误解界面和任务。登录、验证码等流程支持人工接管；发送消息、付款等真实操作应明确限定授权范围。当前不承诺系统杀死进程后的 Responses 会话恢复。具体工具范围见 [实现说明](docs/phonepilot.md)。

## 从源码构建

准备 JDK 21、Android SDK Platform 35、Build Tools 36.0.0，以及 PowerShell 7。Gradle 编译目标为 JVM 11；使用仓库自带 Wrapper。在仓库根目录准备固定版本代理核心：

```powershell
pwsh -NoLogo -NoProfile -File ./scripts/fetch-mihomo.ps1
```

脚本下载 Mihomo v1.19.33 Android arm64 构建，并校验压缩包与解压结果的 SHA-256。随后构建和验证：

```powershell
# Windows / PowerShell 7
./gradlew.bat :automation-core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

```sh
# macOS / Linux
sh ./gradlew :automation-core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

Debug APK 位于 `app/build/outputs/apk/debug/`。应用名为 PhonePilot；包名仍是 `com.kevinluo.autoglm`，当前版本 `1.0.3` / `103`，部分产物文件名沿用 AutoGLM。覆盖安装还要求签名一致；自行构建不自动获得已有安装的签名。正式发布需配置自己的签名，切勿提交签名私钥。

开发约束见 [AGENTS.md](AGENTS.md) 和 [架构契约](docs/architecture-contract.md)。`:automation-core` 为纯 Kotlin/JVM 模块，Android 能力留在应用层。

## 已验证范围

2026-11-08 的当前候选版通过 **588 项 JVM 测试**（核心 29 项、Android JVM 559 项）、4 项原生胶囊布局测试、Debug 构建和 lint（0 错误、366 警告）。Huawei Android 12 已实测同任务双向迁移、后台截图及无障碍点击、暂停补充指令和完整计算器任务。详见 [本版验证记录与限制](docs/capsule-background-validation-20261108.md)。

QQ 实机验证覆盖读取可见会话名称、按名称打开目标，以及人工滚动改变位置后在同一列表中定位并点击。Agent 在这些成功路径中使用文字观察，没有调用截图工具，也没有发送聊天消息。选择面板的主题崩溃已修复，同路径选择、浮窗最小化/展开和任务继续已观察通过。

这些结果不覆盖所有新消息重排、重名、离屏目标、输入法、系统版本、服务重连和进程重建情形；自动化拒绝测试不能替代实机验证。参见 [验证范围](docs/validation.md)。不公开私人聊天树或手机取证图片。

## 开源来源与许可

- **直接上游**：[Luokavin/AutoGLM-For-Android](https://github.com/Luokavin/AutoGLM-For-Android)，MIT；完整保留 Luokavin 版权声明。
- **更早的技术来源**：[zai-org/Open-AutoGLM](https://github.com/zai-org/Open-AutoGLM)。
- **独立代理组件**：[MetaCubeX/mihomo](https://github.com/MetaCubeX/mihomo)，v1.19.33，GPL-3.0。其许可和对应源代码义务独立于 MIT 应用源码；包含核心的 APK 须附 GPL 许可及准确对应源代码信息，见 [NOTICE](NOTICE) 和 [代理说明](docs/proxy.md)。

感谢上游作者与依赖项目。PhonePilot 不将上游成果宣称为独立原创；具体继承关系和变化见 [改动清单](docs/changes-from-upstream.md)。
