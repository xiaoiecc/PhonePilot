# PhonePilot

基于 [Luokavin/AutoGLM-For-Android](https://github.com/Luokavin/AutoGLM-For-Android)
二次开发的 Android 手机助手，重点改造工具调用、无障碍操作、持续对话、历史轨迹及内置代理。
应用包名保持为 `com.kevinluo.autoglm`。功能对比见 [与原项目的区别](changes-from-upstream.md)。

## 连接

设置中只提供 GPT-6.1 Terra 和 GPT-6.1 Sol。接口固定为 OpenAI 官方
`https://api.openai.com/v1/responses`。Terra 的思考档位是 none、low、medium、
high、xhigh、max；Sol 不支持 none。档位越高通常消耗更多推理时间和 token。
密钥由 Android Keystore 支持的加密设置保存，不打包进 APK，不导出到日志。

在“内置代理 · 订阅与节点”中导入 HTTPS Clash/Mihomo YAML 订阅，选择节点后连接。
代理核心随 APK 提供，仅本应用通过带认证的 loopback HTTP CONNECT 代理访问 API，
无需电脑或外部 Clash，也不修改手机全局 VPN。连接失败不会自动绕过已启用的代理。
订阅只提供节点定义；其中的全局规则、控制端口、脚本和 TUN 设置不被采用。

## 会话与交流

任务完成不会清空 Responses 会话。主页和浮窗都可在原上下文继续，另有明确“新会话”操作。
运行中可发送补充消息；消息在当前原子动作后的安全点生效，旧计划剩余动作不会继续执行。
只有显式改向才取消在途 API 请求。输入框聚焦不暂停；首个非空草稿字符才触发本地 DRAFT 暂停，
空草稿失焦仅恢复 DRAFT 暂停，不会覆盖手动暂停。草稿操作本身不调用 API。
`android_ask_user` 可让 PhoneAgent 主动询问自由文本；`android_interact` 处理选项，
`android_take_over` 处理登录、验证等手动接管。等待回复和系统通知不包含敏感正文。
本轮保证任务间和前后台会话连续，不承诺进程被杀死后的 Responses 续接。

## 操作

启用 Shizuku、悬浮窗和 PhonePilot 键盘以支持坐标操作。启用“PhonePilot UI tools”
无障碍服务后，初始观察优先使用精简文本树：

- `android_ui`：语义树观察、节点点击、设置/追加文本、前后滚动、长按、显示到屏幕、文本选择、进度、自定义动作；`find` 在本机按文本/描述/hint/viewId/状态查找，可选有限滚动。
- `android_screenshot`：文字不足以准确理解内容、目标或状态时必须使用，使用官方多模态 function_call_output。
- `computer`：OpenAI 原生动作；使用 computer_call_output 回传执行后截图。
- `android_wait`：默认等待 2000ms，可指定 0–60000ms；浮窗“结束等待”只跳过当前 wait ID，不取消任务。
- `android_gesture`：拟人化滑动和自定义长按时长；可要求用户确认。
- `android_input_text`：替换或追加输入，覆盖旧 Type / Type_Name 语义。
- `android_launch_app` / `android_list_apps`：按名称或包名启动应用、列出可启动应用。
- `android_system_key`：返回、主页、音量加减和电源键。
- `android_take_over` / `android_interact`：在浮窗等待用户接管或选择；取消不会自动同意或选择第一项。
- `android_note`：记录任务备注。批量动作使用 Responses 输出顺序和 computer 动作批次，结束使用原生最终答复。

普通节点 ID 绑定一次快照；平台事件触发实时复核，语义或窗口改变后旧节点拒绝执行。
动态列表可以显式使用 `android_ui.click_match`：携带已观察的节点引用与完整名称字面条件，
执行时在同一可唯一识别列表中重新定位，允许行位置及消息摘要改变。
名称与摘要混合时前缀必须保留实际观察到的名称分隔符；歧义、缺失、截断或窗口改变时拒绝，
不会猜测旧坐标、自动滚动或重试。需要搜索时由模型显式调用 find 并重新观察。
密码及受保护子树不发送。点击和设置文本使用节点原生能力，不猜测父节点。
坐标基于实际发送的截图像素，按显示尺寸映射；不使用旧版 0–999 归一化命令。
悬浮窗在截图和触摸期间隐藏。暂停保持动作游标，继续不会重新执行已完成动作。
computer 不支持鼠标悬停、组合键和非直线拖动；拟人化曲线滑动只由 android_gesture 提供。
同一响应在界面变化后不能继续执行此前生成的旧坐标；新截图供下一 Responses 回合使用。

文字观察可以减少截图上传，但实际费用依赖界面树大小、模型调用和推理档位，
不能仅凭架构承诺固定节省比例。网页 DOM 和 Android 无障碍树不同；
本实现不声称复制 ChatGPT 私有运行环境。

## 官方参考与边界

- https://developers.openai.com/api/docs/guides/tools-computer-use
- https://developers.openai.com/api/docs/guides/tools-computer-use-integration
- https://developers.openai.com/api/docs/guides/function-calling
- https://developer.android.com/reference/android/accessibilityservice/AccessibilityService
- https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo
- https://cdn.openai.com/cua/CUA_eval_extra_information.pdf

最后一项是 2025 年 CUA 评测补充资料，包含 OSWorld 示例提示词，并非当前
GPT-6.1 Terra 榜单的完整私有配置。只借鉴已公开的任务完成、观察和工具约束，
不复制基准中的登录凭据或宽泛授权。

## 构建

PowerShell 7 执行 `scripts/fetch-mihomo.ps1`，脚本固定版本并校验压缩包与解压后 SHA256。
JDK 21、Android SDK 35 + Build Tools 36，随后执行
`gradlew.bat :app:assembleDebug :app:testDebugUnitTest`。
当前 APK 提供 arm64-v8a。运行代理核心不依赖下载后动态执行文件，
而使用 APK 提取的 nativeLibraryDir 可执行文件。

Mihomo v1.19.33 是独立 GPL-3.0 组件，见 `docs/proxy.md` 与应用 assets 的许可证。
对应源代码：https://github.com/MetaCubeX/mihomo/tree/v1.19.33 。
不要把 API Key、订阅地址或生成的 runtime.yaml 提交到源码。
