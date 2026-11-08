# PhonePilot 后台手机操控技术方案

日期：2026-11-08（北京时间）  
状态：核心技术路线已选定，定向实验通过；尚未接入正式 App，不代表完整功能已交付。

本文记录技术决策、实测依据和实施边界。用户交互、功能入口、默认开关及视觉设计留待后续讨论。本文件不授权绕过 [架构契约](architecture-contract.md) 或根目录 [AGENTS.md](../AGENTS.md)。

## 1. 已确定的结论

在当前华为 NOH-AN01、Android 12 / API 31、EMUI 14.2.0 上，已经验证以下路线：

1. 使用 shell UID 2000 创建绑定 `ImageReader` 的独立 `VirtualDisplay`，不创建主屏预览小窗。
2. 启动、截图、坐标输入和后续 Activity 跳转绑定同一显示屏会话，保持来源任务与显示屏存活。
3. 等待实际窗口绘制，不能把启动命令返回成功、任务短暂出现或启动页截图当作应用可操作。
4. 正常退出时保留原 Task，将其放回主屏后台，不抢当前前台；成功后再释放虚拟屏。
5. 系统最近任务列表遵循应用自己的排除标记。用户已明确接受这个边界，不强制修改 `excludeFromRecents`。
6. 显示屏采用“移除时销毁仍附着内容”的策略，防止未交接任务在显示屏消失时被系统自动顶回主屏；**这不是正常退出时销毁应用任务的理由**。

以上是两款应用的定向实测结论，不是所有 Android 应用的兼容性保证。完整后台文字输入、按显示屏读取无障碍树、异常进程死亡后的恢复仍须补齐，才可发布正式后台模式。

### 测试身份的准确表述

本轮独立探针通过 ADB 启动 `app_process`，实际 UID 为 2000，使用与 Shizuku 默认 shell 通道相同权限级别的系统 API。它验证了权限级别和底层机制，**尚未验证正式 PhonePilot 的 Shizuku IPC 接线、App 生命周期和发布包**。后续必须通过现有 UserService 再验收一次，不能把本轮称为“正式 App 已支持”。

## 2. 目标与非目标

### 技术目标

- Agent 操作独立显示屏中的真实、已安装应用；不复制账号，不重新签名目标 App，不创建完整虚拟机。
- 物理主屏仍由用户使用。不能通过后台任务默认接管主屏。
- 模型可以读取目标界面、截图和执行操作，但不能自行选择显示屏 ID、Shell 命令或系统窗口 Token。
- 正常结束后保留应用任务与当前 Activity 栈，使普通应用能从系统最近任务继续。
- 取消、断连、超时和迟到回调具有明确清理边界；不以自动重放动作、重启任务或强行搬回窗口掩盖失败。

### 本阶段不做

- 不设计按钮、导航、小窗样式、通知样式及模式默认值。
- 不修改现有 Chat、浮窗、会话列表、配色和布局。
- 不把“可信显示屏”包装成“物理屏伪装”，不承诺绕过目标 App 的全部限制。
- 不改变包名 `com.kevinluo.autoglm`，不卸载、清数据或替换签名。
- 不另建一个并行 Agent 执行器或任务状态机。

## 3. 对前期实验的纠正

### 3.1 全量启动统计不能作为兼容率

此前全量探测最终尝试 127 个包，124 次曾观察到目标任务位于虚拟屏。这个数字只是启动落点，不是 124 个兼容应用。

该探测过早结束启动任务，且未完整观察延迟出现的子任务；用户看到的讯飞、闲鱼跳转和提示是真实现象，但早期数据不足以证明它们必然拒绝虚拟屏。后续维持完整任务/显示屏会话的定向测试没有复现这些导航链路的主屏逃逸。

不能把所有旧现象一概归因于清理，也不能继续沿用旧“成功率”。保留历史报告作为过程记录，以本文件和新的定向证据为准。

### 3.2 任务、窗口、画面是不同证据

- `am start` 成功：说明启动请求被接受。
- Task 位于目标 display：说明任务归属正确。
- 目标窗口有 Surface、可见、`HAS_DRAWN`：说明实际窗口具备绘制状态。
- 截图实际展示目标页面：说明取到的像素符合预期。
- 输入后目标页面按预期变化：才说明这条操作路径可用。

测试还发现并修正了窗口摘要解析问题：华为 `dumpsys window windows` 同时包含 `Window #N Window{...}` 与重复的 `#N Window{...}` 段落。只切分一种标题，会把后一窗口的状态错误归给前一窗口。正式实现不应依赖此类文本解析，优先使用类型化系统对象。

### 3.3 截图“不同步”是假对照，不是已证实的捕获缺陷

中途曾把不同实验轮次的向导截图和设置页窗口记录放在一起比较。重新逐文件核对后：

- 早期未点击时的向导截图与后续设置页截图不同。
- 独立 SurfaceControl 镜像与直接 ImageReader 保存的设置页 PNG 逐字节一致，实际都显示设置页。
- 不应据此给生产截图代码增加修复旁路，也不应归咎于 `HIDE_NON_SYSTEM_OVERLAY_WINDOWS`。

## 4. 已完成的定向实测

| 场景 | 实际观察 | 结论与范围 |
| --- | --- | --- |
| 讯飞启动页 → 向导页 | 普通无预览虚拟屏中持续观察，后续页面留在同一显示屏 | 该启动导航链路通过 |
| 讯飞向导 → 系统“可用虚拟键盘”页 | 注入目标显示屏点击；系统设置 Activity 进入同一任务、同一显示屏，最长一轮观察约 30 秒 | 跨包同任务跳转通过；未切换任何输入法开关 |
| 闲鱼启动 → 真实首页 | 无预览显示屏中观察约 31 秒，首页、商品图和文字实际绘出 | 通过；早期仅启动页不计为通过 |
| 闲鱼首页 → 商品详情 → 返回首页 | 等首页真实绘制再点按，约 51 秒链路包含 `Init → Main → Detail → Main` | 所有跟踪样本在同一显示屏；没有发消息或购买 |
| 正常退出保留闲鱼 | 原任务从虚拟屏迁至 display 0 后台，原 Task ID 和 MainActivity 保留；迁移前后主屏顶部任务相同，返回任务不可见；普通最近任务查询可见 | 保留任务并静默退出的核心通路通过 |
| 正常退出保留讯飞设置流程 | 原 Task ID 和系统设置页组件迁至 display 0 后台，主屏顶部任务不变 | 迁移通过；此流程自身排除最近任务，不保证继续留在最近列表或被系统长期保留 |
| 显示屏销毁兜底 | 对两 App 分别在任务仍运行时释放带销毁标志的屏；后续 14 次采样均无主屏回迁、无剩余目标任务 | 验证了销毁语义；不能替代正常保留退出 |
| 系统开发者模拟屏 | 手机实际出现半透明左上角预览；讯飞设置、闲鱼主页都可显示 | 是可信屏对照，不是最终无预览方案 |
| 截图行填充 | 去掉 ImageReader RowStride 填充后，设备端 PNG 尺寸核对为 720×1480 | 坐标画布与输出图片尺寸一致 |

本轮没有启动 OpenAI 模型任务，没有调用收费 API，没有发送消息或执行购买。测试结束后，模拟显示屏设置、默认输入法、亮度值和亮度模式均核对为测试前值；专用端口转发已移除，虚拟屏已释放。

正常保留退出与“详情页返回”分别验证；**尚未完整验收退出后从系统最近任务再次进入任意详情页、原滚动位置和编辑草稿的视觉状态**。Task ID 和组件相同不能代替这项验收。

## 5. 参考 DSH Go 的结论

本轮审阅参考仓库 `ccch1mneyyy/dsh-Go`，固定提交：

`e1d5a115158a14889ba28299e1ded1c1fdbc8a29`

它已有完整的后台后端结构，值得参考，不能只读功能宣传或方法注释。

### 可借鉴的部分

- 一个特权核心通过 Shizuku、自连 ADB 等不同传输承载，避免多套设备执行实现。
- 每个任务至多拥有一个后台显示屏，工具调用串行，操作有截止时间。
- 不接受上层任意命令、路径或显示屏 ID。
- 使用显示屏绑定的观察、输入和目标包校验；敏感窗口交回用户。
- 能力检查失败时返回稳定原因，不把“请求已发送”说成“操作已完成”。

### 不能直接照搬的部分

1. App 自身可运行在较老 Android，但后台模式硬门槛是 API 34。`BackgroundPolicy.MIN_SDK=34`；核心依赖 `TRUSTED`、`OWN_FOCUS`、`STEAL_TOP_FOCUS_DISABLED`，树读取也依赖 API 34 的 UiAutomation 路径。这不是删掉版本判断就能安全移植到 API 31。
2. 它当前后台结束链路最终直接 `display.release()`，创建标志含 `DESTROY_CONTENT_ON_REMOVAL`；没有完成本项目需要的“迁回主屏后台并保留任务”操作。不能拿 handoff 注释代替实际实现。
3. 参考仓库主体许可为 AGPL-3.0，手机核心还注明来自 Phase 的作者授权。本轮没有把它的实现复制进 PhonePilot，也没有改变本项目许可；今后直接复用代码前须核对对应文件许可与归属。

结论：参考成熟实现的模块边界和新系统能力，只补当前手机与已接受需求的实际缺口，不重造其全部上层系统，也不把它当作 API 31 的即插即用库。

## 6. 技术路线选择

### 主路线：无预览 VirtualDisplay

Android 特权后端持有：

- shell 身份匹配的 Context；
- 一个 VirtualDisplay 与其生命周期；
- 一个持续消费的 ImageReader；
- 显示屏会话代次、所属任务集合及有效的容器 Token。

本机实验基本参数：720×1480、320dpi，长宽比应由真实设备尺寸计算；生产值不硬编码成某款手机。

实验组合 `459` 对应：

```text
PUBLIC
PRESENTATION
OWN_CONTENT_ONLY
SUPPORTS_TOUCH
ROTATES_WITH_CONTENT
DESTROY_CONTENT_ON_REMOVAL
```

这组标志在本机可用，不包含 `TRUSTED`。生产环境应探测实际能力，不盲目照抄整数位；`PRESENTATION` 是否保留需考虑目标应用自动投屏行为。

`OWN_CONTENT_ONLY` 防止空屏自动镜像主屏。移除内容销毁标志是异常/遗漏任务的系统级兜底；正常结束先迁走保留的任务，再释放屏，此时它不会销毁已经迁回主屏的任务。

### 不采用系统开发者模拟屏作为默认后端

模拟屏由 system_server 创建，可信，但其 TextureView 预览窗属于显示屏承载链路。AOSP 默认缩放约 0.5、不透明度约 0.8、左上角；关闭预览会销毁关联 SurfaceTexture。改名、改 DPI、拖到角落不等于无窗口后台。

本轮只用它做对照，相关全局设置已恢复。正式后台模式不能偷偷写此设置并留下系统小窗。

### 新系统能力采用显式能力档案

API 34 及以上可进一步评估独立焦点、禁止抢全局焦点和显示屏专用 UI 连接。当前 API 31 不能声称已有这些保证。能力选择由宿主决定，不由模型工具参数决定；未知 ROM 或隐藏接口不匹配时明确拒绝该能力。

## 7. 与现有架构的集成位置

```text
Chat / 浮窗 / 历史
    命令、只读投影
          ↓
TaskExecutionManager → RunCoordinator (:automation-core)
          ↓ AgentRunner / RunControl
PhoneAgent → AndroidToolExecutor（唯一预检、检查点、执行与记录入口）
          ↓ 类型化设备能力
Android 设备后端 / 既有 Shizuku UserService
    ├─ DisplaySession：显示屏、ImageReader、代次与资源租约
    ├─ 绑定显示屏的应用启动、截图、坐标/按键
    ├─ 绑定窗口的无障碍观察、节点动作
    ├─ 既有文字输入网关
    └─ TaskPlacementBackend：正常保留退出的任务迁移
```

上述名称描述职责，不要求为了目录整齐逐项新建模块或接口。先扩展实际需要的边界，避免空包装。

### 所有权

| 事实/资源 | 所有者 | 其他模块权限 |
| --- | --- | --- |
| 运行 Job、暂停、交互、取消、收尾、终态 | RunCoordinator | 命令、读取 |
| Responses cursor、工具批次和消息上下文 | PhoneAgent | 显式恢复、快照 |
| 命令预检、执行检查点和历史轨迹 | AndroidToolExecutor | 消费统一结果 |
| 显示屏、图像生产者、任务容器 Token | 特权设备后端的 DisplaySession | 受限能力请求 |
| App/Activity 真实落点与迁移回执 | TaskPlacementBackend / 系统事实 | 校验、投影 |
| 显示模式选择与当前 Run 的目标租约 | 协调器接纳的运行配置 | UI 不能自行改写 |
| 文字编辑事务 | 既有文字网关 | 不得绕过回执和目标检查 |

核心模块仍不依赖 Android、UI、Shizuku 或 HTTP。资源租约不是第二个任务协调器。不得新增 UI 恢复闭包、另一个运行 Job 或每 500ms 注册监听器的循环。

## 8. 显示屏与任务身份契约

至少需要区分：

```text
conversationId：对话
runId：一次模型执行
displaySessionId / generation：设备资源代次
displayId：Android 当前分配的显示屏编号
taskId：应用任务；不是进程 ID，也不是会话 ID
windowId / windowToken：当前窗口及其观察身份
frameId：本次捕获的图像身份、尺寸和时间
```

- `displayId` 可被回收或重新分配，不能单独作为权限凭证。
- 所有迟到事件携带所属 Run/显示屏代次；断连或重建后旧回调不得修改新任务。
- Task ID 也不能单独授权删除、迁移或操作。须重读归属、Token、包名、用户与会话登记记录。
- 不能按包名把用户在主屏自行打开的同包任务当作测试/Agent 任务。
- 目标 App 的 `singleTask/singleInstance` 可能复用用户原任务。启动后必须核对，不能暗中把用户正在使用的实例搬走。
- 正式实现优先使用 TaskStackListener 和类型化系统事实；本轮脚本轮询仅用于实验，不可搬成 UI 轮询状态管理。

## 9. 执行、观察与画面准备

### 启动与后续跳转

应用仍经系统可启动目录解析。启动时使用受控的 ActivityOptions/目标显示屏，不能将模型字符串拼入 Shell。

保持来源任务与显示屏会话，跟踪同一 Task 内的 topActivity 变化及新 Task。不能只看第一个 Launcher，也不能在一秒后把来源任务删除再统计后续跳转。

目标显示屏上的同任务导航已经实测可用；这不等于可以拦截任意 App 显式指定主屏或任意跨包新任务。发现目标逃到主屏时停止输入、报告落点错误，不追到主屏继续点击，也不反复搬回去冒充隔离成功。

### 绘制准备

动作必须基于正确显示屏和足够新的观察。等待真实页面而不是固定睡眠：

- 核对目标任务/窗口归属；
- 核对可见性、Surface 与绘制状态；
- 识别仍遮挡主页面的 StartingWindow/Splash；
- 使用正确尺寸的截图或充分的语义观察再决定动作。

单色/多色数、采样 hash、命令成功都不能单独作为页面就绪条件。本轮一次详情点击落在尚未消失的启动页上，不能计为成功；补上真实绘制判定后才实际进入详情。

### 截图

- ImageReader 必须持续取帧并关闭 Image，避免队列堵塞。
- 去掉 RowStride 填充，输出宽高等于目标显示屏的逻辑画布；不能把 720 宽屏保存成 736 宽再交给模型。
- 捕获结果包含画布尺寸、显示屏代次和单调时间。缓存帧不能冒充操作后新观察。
- 编码、无损策略、单飞、客户端/远端截止沿用现有截图边界；实验用 PNG 不代表要改 App 的 WebP 策略。
- 不为纯语义日志额外截图，不把 Base64 写进历史文本。

## 10. 无障碍与文字输入：尚未验收完成

### 无障碍

现有观察和租约机制需要增加显示屏上下文，不能把默认 `getWindows()` 或主屏节点用于后台。API 30+ 的多显示屏窗口能力是候选，需要在实际 PhonePilot 服务和权限配置中验证。

节点身份仍校验窗口、代次、包名、语义和边界；动态列表继续使用既有显式 `click_match`，不能因后台模式放宽为旧坐标或模糊匹配。

### 文字与 IME

本机普通虚拟屏的 `getDisplayImePolicy()` 实测为 1，即 fallback 策略。它提示主屏 IME/焦点影响的风险；本轮没有完成后台中文输入、用户同时在主屏打字等验收。

- 所有文字输入仍经既有同进程网关、目标代次与明确回执。
- 后台输入须证明 InputConnection 属于目标包、目标窗口和目标显示屏。
- 不得把无障碍直接写字、全局剪贴板或临时 Shell 输入当成绕过既有边界的补丁。
- 用户主屏编辑器与后台输入冲突时应明确暂停/拒绝，不抢主屏键盘或覆盖用户内容。
- API 34 的独立焦点/IME 能力与 API 31 应作为不同能力档案处理。

这部分是正式接入前的阻断项，而不是“后面优化一下”的小问题。

## 11. 正常退出：保留 Task 并迁回主屏后台

这是用户已确认的正常退出语义：停止 Agent 操作，应用保留在后台，普通应用能从系统最近任务接着使用。不是销毁任务，也不是先把应用顶到前台再按 HOME。

### 11.1 不直接使用 moveRootTaskToDisplay

AOSP 12 的该调用会以 onTop 方式迁移，存在抢前台风险；顺序执行“移过去，再压后台”有可见中间状态，不作为正常方案。

### 11.2 已实测的两阶段迁移

利用系统现有 WindowOrganizer/TaskOrganizer 能力，**不注册新的全局 organizer，不替换 SystemUI 的 organizer**：

1. 验证待归还任务属于当前显示屏租约，保存原 Task ID、顶部 Activity、主屏顶部任务及必要页面证据。
2. 在主屏创建一个空的 organizer root，使用独有 launchCookie 识别它。该临时容器没有业务 Activity。
3. 用一个 WindowContainerTransaction 把临时 root 置底，并把源 Task 迁入，`toTop=false`。
4. 此时源 Task 已属于 display 0。再以 `reparent(sourceToken, null, false)` 迁出到**当前主屏**默认 TaskDisplayArea 底部。
5. 重新读取事实：源 Task 作为独立 root 位于主屏，ID/顶部组件保留，未成为前台；确认临时容器没有子任务后才删除它。
6. 核对普通最近任务可见性与应用自己的排除标记，返回分项回执。
7. 当前虚拟屏已无待保留任务后，才释放虚拟屏、图像资源和租约。

`null` 父容器指当前 display 的默认 TaskDisplayArea，因此第 3 步不能省略。不能在源 Task 仍属于虚拟屏时直接 `reparent(..., null, false)` 并以为它已经到了主屏。

### 11.3 失败与部分完成

- 迁入临时容器前失败：保留原显示屏和源任务，不继续破坏性清理。
- 已迁入但迁出失败：保留含有源 Task 的临时容器，返回可恢复的部分完成结果；绝不删除仍有子任务的容器。
- Token 失效、归属变化或恢复目标不唯一：拒绝执行，不按旧 Task ID 猜测。
- 页面组件/Task ID 保留不等于任意 App 的滚动位置、草稿或进程永远保留。跨显示屏尺寸/DPI 改变可能导致 Activity 重建，必须单独验收状态恢复。

### 11.4 最近任务排除规则

用户已确认：遵循应用自身标记，不处理设置向导的例外。

讯飞设置流程实测带 `excludeFromRecents`；迁移回后台后普通最近查询不展示，之后系统日志出现 `recent-task-trimmed`。这不是迁移删除任务，也不是虚拟屏自动给所有 App 加排除标记。

正式回执区分：

```text
taskRetained：任务是否成功迁回并保留
foregroundUnchanged：迁移是否未接管前台
visibleInSystemRecents：系统普通最近任务查询是否展示
excludedByApp：应用自身是否设置排除标记
pageVerification：组件验证 / 视觉验证 / 未验证
```

不通过伪造代理 Activity、清除应用标记或重新启动 Launcher 冒充原页面恢复。

## 12. 暂停、正常收尾与异常销毁分开

- 暂停、等待模型、人工交互不销毁显示屏，不丢弃当前任务上下文。
- 正常释放走上述保留迁移；协调器在收尾实际完成后发布终态。
- `DESTROY_CONTENT_ON_REMOVAL` 防止漏在虚拟屏上的任务自动迁回主屏，但硬崩溃时也可能结束页面。它不保证异常情况下保留 UI 状态。
- 不可恢复的进程死亡、系统强杀与正常退出是不同能力。正式异常恢复策略需单独验收，不能把“不会抢屏”说成“页面绝不会丢”。
- 不在此文决定交互入口或用户按钮，只要求后端有明确的保留、丢弃和部分失败语义；不能用一个无条件 `releaseAll()` 代表所有情况。

## 13. 权限、IPC 与隔离

- 继续使用应用级既有 Shizuku 连接管理，不为后台模式重复绑定一套 UserService。
- 新增能力使用类型化 IPC，包含显示屏租约、代次、任务身份和明确结果；不开放任意脚本或 `app_process` 给模型。
- 模型侧工具不接受任意 displayId、系统 Token、进程 ID 或删除他人任务参数。
- 反射/隐藏 API 留在私有 Android 后端，启动时探测确切签名和能力；不能静默换到主屏执行。
- 旧连接、旧 Token、旧 frame 和旧节点在服务重连后失效；新实现需升级 IPC 代次并保证旧 UserService 重载。
- 锁屏、受保护窗口、支付/登录等边界沿用既有授权与人工交互机制，不以可信屏或 Shell 身份自动绕过。

## 14. 不选择的“通用强开”方案

| 方案 | 结论 |
| --- | --- |
| 反复检测后把逃逸窗口搬回 | 已有抢屏中间状态，不是可靠隔离 |
| 改屏幕名称、DPI伪装物理屏 | 不改变系统报告的虚拟屏类型或信任边界 |
| 在 API 31 强加 TRUSTED | 本机明确因缺少 ADD_TRUSTED_DISPLAY 拒绝 |
| IActivityController 拦截并 Shell 重放 | 回调 Intent 被过滤，缺少完整 extras/flags/ClipData 和结果关系，不能可靠复刻任意跳转 |
| 注册新的默认 DisplayArea/Task organizer | 可能影响 SystemUI，不用于取得 Token 的捷径 |
| 全局强制所有 App 可缩放 | 不保证跳转、焦点和生命周期，也影响用户正常使用 |
| 忽略取树/截图失败继续按旧坐标点 | 违反现有观察与节点租约边界 |

## 15. 正式接入的实施顺序

1. **能力与资源后端**：在既有 UserService 内实现显示屏/图像租约及代次；完成真实 Shizuku IPC 验证。
2. **统一目标上下文**：现有执行入口注入受控 DisplayContext，主屏模式仍走现有路径；不新建平行执行器。
3. **观察与输入**：接入按显示屏截图、无障碍窗口/节点，解决 IME 与文字目标归属；未通过的能力保持明确不可用。
4. **正常保留收尾**：把已测两阶段迁移收进私有后端，逐任务回执、失败保留资源，不破坏最近任务规则。
5. **生命周期验收**：取消、服务重复连接、旋转、前后台切换、迟到事件和进程恢复；验证终结一次、无重放、无主屏误操作。
6. **再讨论产品交互**：根据真实能力和失败结果设计交互；不先画一个宣称“任意 App 完全不打扰”的入口。

所有正式改动继续运行核心测试、Android JVM 测试、架构检查、Debug 构建和相关 lint；实机仅测受影响的定向路径，不再无意义全量启动 App。禁止使用会自动卸载用户应用的 connectedAndroidTest 流程。

## 16. 接入前仍需通过的验收

- [ ] 在实际 PhonePilot Shizuku UserService 中复现，不只是在 ADB 探针中复现。
- [ ] 按显示屏的无障碍取树、节点身份、动态匹配与过期观察处理。
- [ ] 后台中文输入、清空失败、回执失败、主屏同时编辑时的冲突处理。
- [ ] 主屏用户操作和后台输入的焦点隔离；不能直接套用 API 34 的保证。
- [ ] 锁屏/熄屏、旋转、窗口尺寸变化、后台系统弹窗和目标 App 自己指定主屏。
- [ ] 正常退出后从系统最近任务重新进入详情页，验证实际页面/滚动/草稿恢复。
- [ ] 多任务保留迁移、部分失败、不删除非所属任务、无空容器泄漏。
- [ ] 特权进程崩溃、Shizuku 断连、重连与显示屏被系统回收。
- [ ] 亮度、声音、通知和震动等副作用单独核对；虚拟屏不自动隔离这些能力。
- [ ] API 34+ 能力档案与其他 ROM，不把本机结果扩大成全机型承诺。

## 17. 证据与复现实验位置

仓库忽略目录 `work/vdisplay-probe/` 保存独立实验源码和原始资料，不属于生产执行链，也不包含在 APK 中：

- `DisplayProbe.java`：独立显示屏、ImageReader、受控截图与帧时间；
- `DisplayMetadata.java`：Activity 标志与显示屏 IME 策略读取；
- `targeted.ps1`：只测讯飞/闲鱼的窗口、跳转与操作；
- `ReturnToBackground.java`：正常保留迁移的两阶段原型；
- `destroy-on-removal.ps1`：销毁策略验证，**不是正常退出策略**；
- `headless-observed-results.json`、`headless-ready-navigation-results.json`：无预览导航证据；
- `headless-dual-results.json`：两个捕获路径的对照；
- `retained-iflytek-...-return.json`、`retained-idlefish-...-return.json`：保留退出回执；
- `targeted-settings-before.json`、相关恢复核对：测试设置基线。

这些脚本使用当次显示屏/任务/端口/代次，不能把示例数字当作固定设备配置，也不能直接作为生产工具开放。原始日志和截图包含私人设备信息，不加入公开仓库或公开测试报告。

DSH Go 参考仓库仅作只读审阅，未复制其实现到本项目。

## 18. 一手参考

- [AOSP 多显示屏启动策略](https://source.android.com/docs/core/display/multi_display/activity-launch)
- [AOSP 系统模拟屏与可信关系](https://source.android.com/docs/core/display/multi_display/testing-dev-environment)
- [Android 12 DisplayManager：显示屏标志与移除语义](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-12.0.0_r1/core/java/android/hardware/display/DisplayManager.java)
- [Android 12 WindowOrganizerController：reparent 与 null parent](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-12.0.0_r1/services/core/java/com/android/server/wm/WindowOrganizerController.java)
- [Android 12 TaskOrganizerController：创建、查询和删除 root](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-12.0.0_r1/services/core/java/com/android/server/wm/TaskOrganizerController.java)
- [Android 12 ActivityInfo：excludeFromRecents](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-12.0.0_r1/core/java/android/content/pm/ActivityInfo.java)
- [DSH Go 后台能力门槛（固定版本）](https://github.com/ccch1mneyyy/dsh-Go/blob/e1d5a115158a14889ba28299e1ded1c1fdbc8a29/app/android/app/src/main/kotlin/io/github/ccch1mneyyy/dsh_app/phone/background/BackgroundPolicy.kt)
- [DSH Go 特权显示屏核心（固定版本）](https://github.com/ccch1mneyyy/dsh-Go/blob/e1d5a115158a14889ba28299e1ded1c1fdbc8a29/app/android/app/src/main/kotlin/io/github/ccch1mneyyy/dsh_app/phone/background/PrivilegedDisplayCore.kt)
- [DSH Go 第三方声明（固定版本）](https://github.com/ccch1mneyyy/dsh-Go/blob/e1d5a115158a14889ba28299e1ded1c1fdbc8a29/THIRD_PARTY_NOTICES.md)

本文应随实际能力和证据更新。尚未勾选的验收项不能因为文档写完就被标记完成。
