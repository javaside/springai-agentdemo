# springai-tamboui-demo —— 学习路线新第⑤步：TamboUI 教学模块（设计稿）

- 日期：2026-11-09
- 状态：已批准（2026-11-09，含五项本人裁定小项一并放行）
- 决策记录：学习路线位置（新第⑤步，code-tui 顺延为第⑥步）、模块形态（多示例类 + 菜单 main）、
  上游坑姿态（B：主动展示坑当教学素材）、单测进 CI——四项均已由用户在对话中拍板。

## 1. 意图与动机

学习路线第④步（jline-demo）教了 Terminal 的地基（原始模式、按键读取），第⑤步（code-tui）
直接是一份近三万行的生产工程——中间断层明显：code-tui 的 UI 域（`CodeTuiView`、
`ScrollbackPrinter`、`StatusBar`……）建立在 TamboUI 之上，读者此前从未见过这个库，
只能硬啃。jline-demo 的 README 结尾也预留了衔接语（「完整 TUI 框架如 TamboUI 的 jline3 后端」）。

本设计新增教学模块 **`springai-tamboui-demo`**，插入为新第⑤步：用可运行的小示例把
TamboUI 教一遍，**覆盖面严格对齐 code-tui 实际用到的 API 子集**（下文§3 已逐类核实），
让读者到第⑥步时见到的是「放大版」，不是黑盒。

## 2. 已核实的事实依据

- TamboUI 为公开库（github.com/tamboui/tamboui，仿 ratatui），本仓库经根 pom
  `tamboui-bom` 锁 **0.4.0**；本地仓库已有 core / tui / toolkit / widgets / jline3-backend 五个 jar。
- code-tui 主源码 `import dev.tamboui.*` 的完整去重清单（grep 实测，23 个符号）分六层：
  - 文本/样式：`text.Span/Line/Text`、`style.Style/Color`（Span 8 处、Style 7 处，最高频）
  - 宽度口径：`text.CharWidth/TerminalWidth`（CJK/组合序列按终端实际渲染宽度计）
  - 立即模式：`buffer.Buffer/Cell`、`layout.Rect`、`terminal.Frame/Backend`
  - 行内运行器：`tui.InlineTuiRunner/InlineTuiConfig`；事件 `tui.event.KeyEvent/KeyCode/PasteEvent/ResizeEvent`；
    按键绑定 `tui.bindings.KeyTrigger/Actions`
  - Toolkit DSL：`toolkit.app.InlineApp/InlineToolkitRunner`、`toolkit.element.Element/RenderContext/Size`、
    `toolkit.event.EventResult`，以及 static import 的 `Toolkit.text/richText/column/textArea` 与
    `InlineToolkit.scope`
  - 组件：`widgets.block.Block/Borders/BorderType`、`widgets.input.TextAreaState`
- `CodeTuiView extends InlineApp` 只覆盖四个钩子：`height()`、`render()`、`configure(int)`、
  `onStart()`（implementation-map.md §4 与源码行号核实）。
- **上游 jar 与 patch 的分叉（builder-check 用 javap/unzip 实测）**：code-tui classpath 上有
  4 个符号实际解析到 patch 版——`TerminalWidth`（patch **自建类，上游根本没有**）、`CharWidth`、
  `Buffer`、`InlineTuiRunner`（`requestUiUpdate`/`submitPtyControlSequence` 只存在于 patch 版）。
  上游 0.4.0 `CharWidth` 的口径在多类序列上与终端真实渲染不符：keycap（9️⃣ 逐码点算 1 列、
  实际 2 列）、ZWJ 家族（跳宽分支算 2 列、实际 5 列）、Cf 格式字符如 ZWNJ（算 0 列、实际 1 列）。
  上游 0.4.0 粘贴链路两个坑：大段粘贴「50ms 静默即掐断、剩余字节回按键解析」、CR 泄漏
  （见 `docs/superpowers/specs/2026-10-09-cr-paste-leak-and-vs16-width-design.md`）。
- core-demo 的入口形态：示例类实现 `Demo` 接口装进 `List`，`ConsoleMenu` 交互选择（模块内自足，
  不跨模块复用——新模块照此自写简版菜单）。
- jline-demo 的打包形态：jar-plugin `addClasspath` + dependency-plugin 拷 `target/lib`，
  `java -jar` 直接跑（新模块沿用）。
- 改编号的文档触点（grep 实测）：根 `README.md` 与 `AGENTS.md`（「五个模块」「第 5 步/第⑤步」字样）；
  CHANGELOG 按发版流程另计。

## 3. 目标 / 非目标 / 反目标

**目标**
1. 新模块 `springai-tamboui-demo`：4 个示例类 + 1 个菜单入口，纯 Java、不依赖 Spring Boot、
   不需要任何 API key（零成本可跑）。
2. 每个示例「读源码能懂、跑起来有反馈」，覆盖§2 列出的 code-tui 全部六层 API。
3. 示例④做出「迷你版 CodeTuiView」：Toolkit DSL + live 区/scrollback 双通道分离——
   code-tui UI 域的核心架构先以 100 行级形态见过一遍。
4. 学习路线改造：根 README、AGENTS.md 的模块表与编号更新（五步→六步）。
5. 纯逻辑部分有少量先行单测（宽度口径对照、Buffer cell 断言、TextAreaState 编辑行为）。

**非目标**
- 不覆盖 code-tui 未使用的 TamboUI 能力（chart/canvas/table/css/export 等一律不讲，
  README 指向官方文档）。
- 不接任何模型/Agent：示例④的「流式输出」是定时器假流式。
- 不修改 code-tui 与 inline-patch 的任何代码。

**反目标（做了即失败）**
- 把示例写成 API 罗列——每个示例必须有一个「跑完记得住的一句话认知」（对齐前四步的
  「本步核心认知」体例）。
- 依赖 inline-patch 的 shadow 类——教学必须用上游原版 jar，否则读者到 code-tui/上游仓库
  对不上号。

## 4. 方案取舍（对话已定 + 本人裁定项）

**学习路线位置**（用户拍板）：新第⑤步，code-tui 顺延第⑥步。语义链最顺：
JLine 地基 → TUI 框架 → 组装成真工程。

**模块形态**（用户拍板）：多示例类 + 菜单 main（core-demo 式）。四种运行模型各自独立进程，
符合「一个 TUI 应用 = 一个事件循环」的真实心智模型。被淘汰：
- 单文件分块（jline-demo 式）——TamboUI 面远大于 Terminal 接口，四种运行模型塞一个进程别扭；
- 迷你项目式——直接做完整 UI 骨架教学层次感弱，其精华吸收为示例④。

**依赖口径**（用户拍板）：demo 只依赖上游原版（bom 管理的五个 tamboui jar），
不依赖 `springai-tamboui-inline-patch`。理由：教学目标是库本身；patch 是 code-tui 的
工程兜底（AsyncPtyWriter 防写阻塞等），其存在与角色在模块 README 用一节讲清并给出链接，
但不进示例代码。

**上游坑姿态**（用户拍板，B 案）：上游 0.4.0 的宽度口径错误与粘贴链路坑**主动展示为
教学素材**，三段式叙事「带病演示 → 病因 → 药方（patch 为何存在）」。这回答了学习者
必然产生的「inline-patch 模块到底为什么存在」，也符合仓库「一跑便知」的实证教学风格；
同时把「demo 测得 ≠ code-tui 实际行为」的差距转化为显式知识点，而不是隐藏的困惑源。
README 的「与 code-tui 对应表」须逐项标注那 4 个符号「code-tui 实际用的是 patch 版」
（本人裁定：标注必做，精度到类名）。

**示例②③的运行器选择**（本人裁定）：都基于 `InlineTuiRunner`（行内模式）而非全屏
`TuiRunner`。理由：code-tui 用的是行内模式（终端里正常滚动 + 底部 live 区），示例从第②个
起就建立这个心智；全屏模式 code-tui 没用，不教。

## 5. 模块设计

### 5.1 工程结构

```
springai-tamboui-demo/
├── pom.xml                       # parent 继承根 pom；依赖 tamboui-core/tui/widgets/toolkit/jline3-backend（版本随 bom）
├── README.md                     # 模块教学文档（示例表、运行说明、与 code-tui 的对应表、patch 模块说明）
└── src/
    ├── main/java/io/github/javaside/springai/tamboui/
    │   ├── TambouiDemoApplication.java   # 菜单入口（自写简版菜单，不跨模块依赖 core-demo）
    │   ├── demo/TextStylesWidthDemo.java      # 示例①
    │   ├── demo/BufferBlockDemo.java          # 示例②
    │   ├── demo/InlineEventsDemo.java         # 示例③
    │   └── demo/ToolkitChatDemo.java          # 示例④
    └── test/java/...                       # 少量纯逻辑单测
```

打包照 jline-demo：可执行 jar + `target/lib`。

### 5.2 四个示例（每个 = 教学目标 + API 面 + 跑起来看到什么）

**① TextStylesWidthDemo —— 「富文本与宽度口径」（不进 TUI 模式，stdout 直出）**
- API：`Span/Line/Text/Style/Color` + 上游 `CharWidth`（`TerminalWidth` 上游不存在，是 patch
  自建类——这一点本身就是要教的知识点）。
- 内容（三段式「带病演示 → 病因 → 药方」）：
  1. 三种粒度（Span→Line→Text）拼一条带色文本打印；
  2. 宽度测量对照表：ASCII/中文/❄️+VS16/**9️⃣ keycap/ZWJ 家族/ZWNJ** 各测一遍，打印
     「上游 `CharWidth` 计算的宽度」与「终端实际渲染宽度」两列——keycap/ZWJ/Cf 三类
     **现场跳格给你看**（病）；
  3. 用测量结果做一次手动两栏对齐：用测得准的串对齐成功、用测错的串对齐 visibly 崩坏，
     然后讲病因（逐码点/跳宽分支的口径局限）与药方（code-tui 的 patch shadow 了
     `CharWidth` 并自建 `TerminalWidth`，口径=终端实际渲染宽度；给 patch 模块链接）。
- 一句话认知：**终端对齐的唯一真相源是渲染宽度，不是字符个数——连库自己也会算错，
  code-tui 因此维护了 patch**。

**② BufferBlockDemo —— 「立即模式与差分渲染」（InlineTuiRunner 行内模式）**
- API：`Buffer/Cell/Rect` + `Frame/Backend`（经 runner 间接持有）+ `Block/Borders/BorderType`。
- 内容：用 `Block` 画一个带边框面板，内部 `Buffer.set(x,y,Cell)` 手写几个字符；
  演示「整帧重画 → 库做差分 → 只把变化的 cell 发给终端」：开启后台的 diff 日志打印
  （或第二帧只改一个字符让用户肉眼确认瞬间刷新）。
- 一句话认知：**你只负责描述整屏应该长什么样，库负责算最小差量**——立即模式的心智模型。

**③ InlineEventsDemo —— 「事件循环与按键绑定」（InlineTuiRunner 行内模式）**
- API：`InlineTuiConfig/InlineTuiRunner` + `KeyEvent/KeyCode/PasteEvent/ResizeEvent` +
  `KeyTrigger/Actions`。
- 内容：一个「按键仪表盘」——普通键实时回显 key 名；方向键经 `KeyTrigger` 绑定移动一个小光标块；
  改窗口大小触发 `ResizeEvent` 重绘新尺寸。粘贴环节按 B 案三段式：先贴一小段多行文本
  （统计行数/字符数正常，顺带点出括号粘贴协议的存在），再**故意贴一大段**（>50ms 静默阈值）
  ——现场看到统计错乱/后半段变成乱按键（病），然后讲病因（上游 50ms 静默掐断 + CR 泄漏）
  与药方（patch shadow `EventParser` 的括号粘贴静默预算；给 spec 链接）。
- 一句话认知：**TUI 应用 = 事件循环 + 每事件按需重绘**——这是 code-tui「空闲零输出」的由来；
  而事件解析层不可靠时，就得像 code-tui 一样连解析器一起 shadow。

**④ ToolkitChatDemo —— 「迷你版 CodeTuiView」（InlineApp + Toolkit DSL）**
- API：`InlineApp`（覆盖 `height()/render()/configure(int)` 三钩子，对应 code-tui 的四钩子少一个
  `onStart`）+ `Toolkit.text/richText/column/scope` + `textArea/TextAreaState` + `EventResult` +
  `Block/Borders`（输入框圆角边框）。
- 内容：底部 `textArea` 输入框（多行编辑、光标、中文输入原生可用），Enter 提交后：
  - 用户行经 `runner().println` 下沉 scrollback（模拟定稿）；
  - 一个定时器假流式逐字吐一段回答——残行在 live 区原地刷新（`richText`），
    每行定稿后同样下沉 scrollback；
  - 顶部一个 `scope(cond, ...)` 面板（如「生成中…」指示器），有内容才出现、完成即收起。
- 一句话认知：**live 区差分重绘 + scrollback println 下沉，两条通道各司其职**——
  `CodeTuiView` 的架构缩影（注释里给出对应类名与行数对比，鼓励带着这个缩影去读原版）。

### 5.3 学习路线与文档改造

- 根 pom `<modules>`：新模块插在 `springai-jline-demo` 之后、`springai-tamboui-inline-patch` 之前。
- 根 README：模块表加第⑤行（对应公式列「（界面·框架）」），code-tui 行改第⑥；「五个模块」
  措辞改六个；新增「第 5 步」章节（示例表 + 核心认知 + 自测），原第 5 步章节顺延改号；
  开头路线描述「终端界面」处补 TUI 框架半句。
- AGENTS.md：仓库结构表加一行（角色：第⑤步 TUI 框架；测试：少量）；概览段落的教学模块计数同步。
- jline-demo README 结尾衔接语补指向新模块（一句）。
- CI（用户拍板）：`ci.yml` 测试 job 追加 `mvn -B -pl springai-tamboui-demo -am test`
  （新模块不是 code-tui 的依赖，`-pl springai-code-tui -am` 带不到它）；package job 无需改
  （`-DskipTests package` 天然带上新模块）。
- CHANGELOG / release notes：按发版流程（`docs/release-notes/`）另办，不在本分支混做。

### 5.4 测试策略

TUI 交互本身不自动化（与 jline-demo 口径一致），但可提纯的逻辑先行测试：
- 宽度对照表（示例①的数据源）：断言上游 `CharWidth` 对各典型串的返回值——**按 0.4.0 实测值
  写死，包括错的值**（keycap=1、ZWJ 家族=2、ZWNJ=0）。断言「带病值」本身钉住了教学叙事的
  事实基础；上游将来修好时测试变红，提示更新示例文案——这是能被反向改动杀死的活断言。
- Buffer 写入/读取（示例②用的辅助）：断言 cell 内容与坐标。
- `TextAreaState` 编辑行为（示例④输入框）：insert / deleteBackward / 多行换行
  （builder-check 实测上游无「删词」类 API，测试与示例都不提它）。
每个测试先红后绿（AGENTS.md 测试纪律）；示例类本身以「真实终端人工跑」验收，
README 写明须在真实终端运行。

## 6. 约束与风险

- Java 17 语言级别；注释/文档全中文；提交走 `feature/tamboui-demo` 分支，`--no-ff` 合并。
- TamboUI 自述 experimental、API 可能变：示例类 javadoc 首行标注「基于 TamboUI 0.4.0 实测 API」；
  写每个示例前先用 sources jar / javadoc 核对签名，不凭本设计稿的类名臆测方法（设计稿只钉
  类名清单——这些已从 code-tui import 实测锚定）。
- 版权署名 `Copyright 2026 Xinghua Zhou`（若加文件头；现有 demo 模块无文件头，跟随现状可不加）。
- 风险：④ 的假流式定时器与事件循环的线程交互是唯一稍深处——builder 阶段先查
  `InlineToolkitRunner` 的线程模型（UI 线程投递 API，如 code-tui 的 `requestUiUpdate` 同类物），
  禁止从非 UI 线程直接改状态。

## 7. 留给实施计划的拆解

以下留给 plans 文档（每步「先写失败测试→红→最小实现→绿→提交」）：
- 模块骨架与 pom 接线（含 CI 验证 `mvn -DskipTests package` 通过）；
- 四个示例的实现顺序（①→④，依赖递增）与每步的验收（真实终端人工跑）；
- 文档改造的具体 diff 范围；
- 发版相关动作（版本 bump、release notes）是否随本分支——倾向随发版流程单独走。
