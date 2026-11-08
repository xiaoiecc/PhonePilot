# PhonePilot：来源与相对上游的改动

## 直接来源

PhonePilot 是 [Luokavin/AutoGLM-For-Android](https://github.com/Luokavin/AutoGLM-For-Android) 的直接二次开发。其 Android 上游将 [zai-org/Open-AutoGLM](https://github.com/zai-org/Open-AutoGLM) 的手机代理方案发展为原生应用；PhonePilot 继续在这一基础上改进。

本分支保留上游 Git 历史，来源提交为 `a32425edbff18ac26d98a6a3784fc80def6cee4e`。继承范围包含 Android 工程、Shizuku、键盘、浮窗、原生界面、设置、历史与设备操作基础；[MIT 许可](../LICENSE) 中完整保留 `Copyright (c) 2025 Luokavin`。本清单不意味着这些模块均为本分支原创，也不承诺上游每项功能原封不动保留。

## 功能上的主要变化

| 领域 | 上游基础 | PhonePilot 的变化 |
| --- | --- | --- |
| 模型操作 | OpenAI 格式兼容视觉模型、文本动作解析 | 官方 Responses function tools（`android_computer` 等），有序批次、工具结果回传、执行前检查；固定 Terra/Sol 白名单及思考档位 |
| 屏幕理解 | 截图驱动的视觉操作 | 优先提供无障碍精简文本树，支持本地 find、节点点击、输入和滚动；文字可靠时减少截图，需要视觉信息时补充截图 |
| 动态列表 | 观察目标后执行点击 | 显式 `android_ui.click_match` 在同一唯一容器内按已观察字面名称重新定位；原观察与当前匹配均唯一，歧义、缺失或窗口变化拒绝 |
| 对话与会话 | 任务输入、执行和历史界面 | 完成后保留上下文；主页/浮窗续聊、会话列表、新会话；运行中补充要求在安全点处理，草稿和手动暂停分别管理 |
| 交流反馈 | 执行进度与任务反馈 | 每次本地工具调用提供中途说明（批次共用一句），持续显示最新说明、输入/输出 token 计数；统一文字提问、选项、确认与人工接管 |
| 历史与导出 | 历史记录和截图展示 | 按会话聚合多轮执行；消息与工具完整时间线、参数/结果/错误/耗时；区分操作前后图，以实际完成动作标注操作前图，支持分页导出 |
| 截图使用 | 设备截屏 | 按需获取、可调整发送尺寸、无损编码与坐标映射；纯文本操作不为日志额外截图，不编造旧记录缺失的标注 |
| 代理 | 应用 API 网络请求 | 内置独立 Mihomo 进程、HTTPS YAML 订阅与节点选择、加密配置、带认证的 loopback 代理；仅本应用使用，不接管全局 VPN |

这些改进涉及实际用户流程与工具能力，不只是更换 API 地址。具体工具说明见 [PhonePilot 实现说明](phonepilot.md)。文字树并不总是充分，截图缩放也会改变分辨率；无损编码不等于缩放无损，不承诺固定 token 或费用节省比例。

新增悬浮胶囊及动画交互、自适应输入、同任务前后台迁移与后台无障碍操作。后台能力目前仅启用 API 31 档案；本版 588 项 JVM 测试及实机结果见 [1.0.3 验证记录](capsule-background-validation-20261108.md)。下方 2026-11-07 数据为旧版本记录。

## 当前连接范围

当前版本**专用于 OpenAI 官方 API**，不是可配置任意服务商的通用适配器。固定地址为 `https://api.openai.com/v1/responses`；用户自行提供官方 API Key，账户必须具备相应模型和工具权限。

- `gpt-6.1-terra`：`none`、`low`、`medium`、`high`、`xhigh`、`max`。
- `gpt-6.1-sol`：`low`、`medium`、`high`、`xhigh`、`max`。
- 两者默认档位均为 `medium`。以上为本项目客户端校验，不是账户权限保证。

不支持第三方 OpenAI-compatible 网关、自托管 GLM、ChatGPT 登录或私有协议。本项目不是 OpenAI、QQ 或腾讯的官方产品。公开参考：[模型文档](https://developers.openai.com/api/docs/models)、[computer use](https://developers.openai.com/api/docs/guides/tools-computer-use)。

## 动态列表改进的准确含义

普通 `click` 仍要求严格快照身份。`click_match` 是另一项显式能力：保留已观察的 snapshot/node 引用，将该节点的稳定字面标签作为条件，在原滚动容器内重新找唯一目标。精确匹配为默认；显式前缀必须包含从观察中复制的名称分隔符。

它不是模糊搜索、QQ 账号 ID 识别、跨窗口搜索或自动重试，不自动滚动。需要继续寻找时由模型显式 find/observe。目标消失、重名、容器不唯一、观察截断、保护节点或窗口切换时均不猜测点击。名称只代表屏幕可见语义，不等于 QQ 账户身份。

## 支撑这些功能的执行约束

`:automation-core` 是纯 Kotlin/JVM 模块；RunCoordinator 统一持有运行、暂停、交互、等待和收尾状态。AndroidToolExecutor 统一类型化批次预检与执行检查点；Shizuku IPC 读取类型化退出码、超时和截断，键盘使用同进程网关与输入回执，浮窗显示遵守截图/触摸策略。历史和标注仅记录事实，没有重放权限。开发边界见 [架构契约](architecture-contract.md)。

## 验证证据与限制

2026-11-07 记录的验证为核心 19 项、Android JVM 490 项，共 **509 项测试通过**，零失败、错误或跳过；Debug 构建和 lint 通过，lint 仍有警告。该结果属于当时代码快照，不代表未来提交或所有设备。

QQ 实机成功路径仅证明读取可见会话名称、打开指定目标、人工滚动改变目标位置后在同一列表中重新定位并点击。Agent 在这些路径中使用文字观察，没有请求截图，也没有发送消息。选择面板曾发生主题崩溃，修复后同路径选择、浮窗最小化/展开和任务继续已观察通过。

人工滚动不等同于所有新消息触发的实时重排。重名、离屏目标等拒绝路径的自动化覆盖不代表实机覆盖。其他设备、输入法、系统版本、旋转/进程重建和服务重连仍需分别验证。详见 [验证范围](validation.md)。不公开私人聊天树或手机取证图片，也不把上游旧 UI 图当作当前构建预览。

## 身份、许可与分发

- 产品名 PhonePilot；包名保留 `com.kevinluo.autoglm`，当前版本 `1.0.3` / `103`，arm64-v8a，最低 API 24。包名相同不等于任何签名均可覆盖安装；更新仍须签名一致，私钥不随仓库提供。
- 应用源码沿用 MIT，保留 Luokavin 版权声明和 Open-AutoGLM 来源链接。
- Mihomo v1.19.33 是独立启动的 **GPL-3.0** 可执行组件，不因与 MIT 应用打包而变为 MIT。固定获取与双重哈希校验见 [下载脚本](../scripts/fetch-mihomo.ps1)，精确源代码和许可信息见 [NOTICE](../NOTICE) 与 [代理说明](proxy.md)。包含核心的 APK 分发须提供 GPL 许可和准确对应源代码，包括本地修改（如有）。
- 源码和 APK 不应包含 API Key、私人订阅地址、认证头、签名私钥或私人手机记录。

## English summary

PhonePilot is a substantial direct derivative of **Luokavin/AutoGLM-For-Android**, whose earlier lineage is **zai-org/Open-AutoGLM**. It preserves upstream MIT credit and improves native tool calling and batches, accessibility text/search/actions and named targeting, chat/session continuity, human interactions, visible feedback and token counts, history/annotations/exports, screenshots, and an in-app proxy with subscription/node selection.

The current client only supports the official OpenAI Responses API with user-supplied keys and the GPT-6.1 Terra/Sol allowlist. It is not an arbitrary provider adapter or an official OpenAI/QQ product. The recorded 509 passing tests and narrow QQ device checks do not establish universal reliability. Mihomo remains a separate GPL-3.0 component with corresponding-source obligations.
