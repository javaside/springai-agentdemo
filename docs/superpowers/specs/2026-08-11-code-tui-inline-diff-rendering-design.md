# code-tui 行内差分渲染与无闪烁设计

## 背景

code-tui 使用 TamboUI 0.4.0 的 `InlineApp`、`InlineToolkitRunner` 和 `InlineDisplay`，把动态面板、输入框与状态行固定在终端 scrollback 下方。当前界面即使逻辑内容不变也会闪动，Windows Terminal/ConPTY 下尤其明显；拖动窗口时还会出现更强烈的整屏闪烁。

这不是输入框或输入法自身的问题。TamboUI 普通全屏 `Terminal.draw()` 已有前后帧 `Buffer.diff()`，但行内模式的 `InlineDisplay.redrawDisplayArea()` 绕过了该机制：每个 tick 都逐行执行“回车、擦除整行、重写整行”，最后重新定位光标并 flush。`InlineToolkitRunner` 又让每个 `TickEvent` 都触发重绘，因此一帧不变也会向终端持续写入；状态栏与后台任务的波光动画还会让每帧确有少量样式变化，却连带整个输入框和全部 live 区一起被擦除、重画。

当前 resize 修复进一步放大了现象：每次宽度变化先用 `ESC[J` 清扫 live 区，停稳后又清整屏并重放最近 scrollback。它解决了终端 reflow 导致的残影和光标漂移，但代价是拖动中反复暴露“清除后尚未重画”的中间状态。

## 目标

1. 逻辑画面和逻辑光标均不变时，首帧之后终端输出严格为零。
2. 输入、光标移动或波光动画只更新实际变化的单元格，不擦除或重写无关行。
3. 保留 30 FPS 波光动画、声明式 UI、动态高度、终端 scrollback 和中文 IME 定位。
4. Windows Terminal/ConPTY 是主要验收环境；不支持同步输出的终端也必须无闪烁。
5. resize 从“每个事件清扫一次”改为“合并后单次恢复”，避免拖动中反复清屏。
6. `/clear` 继续保留明确的整屏和 scrollback 清理语义。

## 非目标

- 不改 code-tui 的业务面板、输入模型或键位语义。
- 不为规避闪烁而取消波光动画或简单降低全局帧率。
- 不切换到 alternate screen；scrollback 仍由终端持有。
- 不在 code-tui 中重写完整 TUI 框架或长期 fork 整个 TamboUI。
- 不把 DEC mode 2026 当成正确性的前提。

## 已核实的根因

TamboUI 0.4.0 的行内链路为：

```text
InlineToolkitRunner
  → InlineTuiRunner
    → InlineViewport.draw()
      → InlineDisplay.render()
        → InlineDisplay.redrawDisplayArea()
```

`InlineViewport` 每帧构造完整 Buffer，但 `InlineDisplay` 只保存本帧 Buffer，没有上一帧快照。`redrawDisplayArea()` 每次都：

1. 从当前硬件光标移动到 live 区第 0 行；
2. 对每一行执行 `eraseToEndOfLine()`；
3. 重写该行全部非空内容；
4. 移回目标逻辑光标；
5. flush。

此外，`InlineDisplay.println()` 每打印一条 scrollback 后还会无条件调用一次 `redrawDisplayArea()`。一次 drain 最多打印 300 行时，live 区可能被完整重画 300 次。

相比之下，TamboUI 全屏 `Terminal` 已维护 `previousBuffer/currentBuffer`，调用 `previousBuffer.diff(currentBuffer, diffResult)`，diff 为空时不执行 `backend.draw()`。本设计把同一原则带到行内模式，同时保留行内区域的相对光标与动态高度语义。

## 方案比较

### 方案 A：code-tui 内容签名与降帧

只在 code-tui 状态签名变化时请求重画，空闲时低频保活。改动较小，但状态栏动画每帧变化时仍会触发 `InlineDisplay` 整块擦写；历史上跳帧还导致过输入框消失和动态高度记账漂移。它只能减少触发次数，不能消除根因。

### 方案 B：TamboUI 行内帧差分提交（采用）

保持每个 tick 构造完整虚拟帧，但在 `InlineDisplay` 边界维护前后 Buffer，向终端只提交最小 cell diff。无变化则零输出；动画、输入与光标移动只触及实际变化区域。动态高度、scrollback 与 resize 分别处理，不再混入普通帧刷新。

### 方案 C：code-tui 自行实现完整行内 compositor

绕过 `InlineDisplay`，自行维护渲染、焦点、scrollback、resize 与光标。控制力最强，但会复制 TamboUI 大量职责，维护成本和回归风险过高。

## 架构

核心修复位于 TamboUI `InlineDisplay` 层，code-tui 只调整 resize 接线与测试：

```text
CodeTuiView.render()
  → 每帧构造完整 Element 树和 Frame Buffer
  → InlineViewport 将完整帧交给 InlineDisplay
  → InlineDisplay 比较 previous/current Buffer
      ├─ 相同：零终端输出
      ├─ 同尺寸有变化：提交最小 cell patch
      ├─ 高度变化：调整结构后提交重叠区 diff 与新增区
      └─ 快照失效：仅完整重建 live 区一次
  → 批次末统一定位逻辑光标并 flush
```

### 组件边界

1. **行内帧快照**
   - 保存上一帧 live Buffer、宽高和逻辑光标。
   - 新帧完整构造结束后才参与比较，避免渲染中间态进入终端。

2. **行内差分规划器**
   - 输入旧 Buffer、新 Buffer 与光标状态。
   - 输出按行归并的变化区间以及结构变化计划。
   - 纯逻辑、无终端副作用，便于穷举测试。

3. **行内提交器**
   - 把光标移动、样式和 cell patch 组装成单一输出批次。
   - 每帧最多一次 write/flush。
   - 可选使用 DEC mode 2026 包裹批次。

4. **code-tui resize 协调**
   - 合并连续宽度变化。
   - 移除逐事件 `ESC[J` 清扫。
   - 停稳后执行一次新宽度重建和 scrollback 重放。

## 普通帧差分

### 同尺寸、同内容

新旧 Buffer 和逻辑光标都相同时：

- 不移动硬件光标；
- 不写样式复位；
- 不 flush；
- 终端输出严格为零。

### 同尺寸、有变化

1. 以 `Cell` 为比较单位，样式变化也属于变化。
2. 按行把相邻变化单元格合并成 patch 区间，减少光标移动与 SGR 数量。
3. 直接覆盖变化区间，不在普通帧中调用整行 `eraseToEndOfLine()`。
4. 旧行尾从非空变为空白时，只覆盖需要清理的旧尾部区间。
5. patch 完成后定位到新逻辑光标，交换前后 Buffer。

### 宽字符

- patch 起点若落在 continuation cell，向左扩到宽字符首格。
- patch 终点若截断宽字符，向右覆盖其 continuation cell。
- CJK、emoji 和组合宽字符不得出现半字残留。
- 宽字符 diff 使用 TamboUI 现有 `Cell`/`CharWidth` 口径，避免另造宽度规则。

### 光标

code-tui 同时有 Buffer 中的反显格和隐藏的硬件光标。两者分开处理：

- 反显格是普通 cell，进入 Buffer diff。
- 硬件光标仅作相对位置记账和 IME 锚点。
- Buffer 不变而逻辑光标变化时，只发送光标定位；不重写输入框。
- 平时仍将硬件光标停在文本行，保持中文 IME 预编辑位置正确。

## 原子提交与同步输出

一帧内的结构调整、cell patch、样式复位和最终光标定位先组成一个输出批次，只进行一次 write/flush，避免多个 backend 调用在 ConPTY 中形成可见中间状态。

DEC private mode 2026 同步输出是可选增强：

- 确认支持时用 `CSI ? 2026 h` 与 `CSI ? 2026 l` 包裹一帧更新；
- 不支持、探测无响应或探测失败时直接使用单批差分；
- 不允许等待探测响应阻塞启动或污染正常输入事件；
- 关闭同步输出后仍须满足无整行擦除、静止零输出等全部正确性要求。

## 动态高度

### 高度不变

绝大多数输入、波光与状态变化走普通 cell diff，不执行增删行。

### 增高

1. 在 live 区底部扩展所需行数。
2. 保留旧帧与新帧重叠区域的快照。
3. 只绘制新增行和重叠区域的真实 diff。
4. 结构调整、patch 与光标定位在同一提交事务完成。

### 缩短

1. 删除多出的底部行。
2. 保留剩余重叠区域的快照。
3. 对保留区域提交真实 diff。
4. 不因高度变化清空全部前帧 Buffer。

斜杠菜单、todo、权限面板等出现或消失时仍能挤压 live 区，但不再强制重画输入框。

## scrollback 批处理

当前 `println()` 每行后完整重画 live 区，必须改为批处理语义：

1. 一个 drain 收集本批 scrollback 行。
2. 在 live 区上方连续插行并打印该批内容。
3. 更新 live 区相对位置记账。
4. 由于插行会整体下移终端中已有 live 区，终端画面内容本身通常无需重写。
5. 批次末仅在新虚拟帧与已显示帧不同的情况下提交一次 live diff，并统一定位光标。

目标是“一批 scrollback 至多一次 live 提交”，而不是每打印一行都刷新 live 区。

## resize

### 当前问题

逐 `ResizeEvent` 执行 `ESC[J`，再由库重画；停稳后又整屏清理和重放。Windows Terminal 会明显展示这些阶段，连续拖动时尤甚。

### 新策略

1. resize 过程中不逐事件清屏，只记录最新目标宽度并重置停稳计时。
2. 合并连续 resize，连续 120ms 没有新宽度事件后执行一次恢复。
3. 在单一同步输出事务中：
   - 使旧宽度帧快照失效；
   - 按新宽度重建最近 scrollback；
   - 构造并提交新宽度 live 区；
   - 恢复逻辑光标。
4. 支持 DEC 2026 时只展示最终重建画面；不支持时也只重建一次。
5. `/clear` 不走该合并策略，继续执行用户明确请求的整屏和 scrollback 清理。

取舍：拖动尚未停稳的 120ms 窗口内，终端自身 reflow 可能短暂错行；但不会每个宽度档位都清屏。停稳后一次恢复到正确画面。

## 错误与降级

若发现 Buffer 尺寸、宽字符边界、相对光标或内部快照不一致：

1. 放弃本帧差分。
2. 仅对 live 区执行一次受控完整重建。
3. 重置前后帧快照和逻辑光标记账。
4. 下一帧恢复正常差分。

异常恢复不能扩大为整屏清理，也不能触及 scrollback；只有 `/clear` 和 resize 停稳重放允许重建 scrollback。

TamboUI 内部实现发生版本变化时应由结构测试或编译失败暴露，不能依赖运行时反射静默失效来提供核心差分能力。

## 发布与上游策略

最新 TamboUI 上游源码仍采用逐行擦除重画，且当前最新发布版是 0.4.0。实施时：

1. 以最小、通用的 `InlineDisplay` 差分修复为目标，准备上游贡献。
2. 上游版本发布前，项目内使用可重复构建的兼容补丁或受控依赖，不修改开发机本地 Maven 仓库中的 jar。
3. 补丁仅覆盖行内显示所需实现与测试，不长期 fork 整个 TamboUI。
4. code-tui 的 resize 改动保持独立，便于上游差分版本发布后移除兼容层。

上游版本发布前，在仓库内增加一个只包含必要改动的 `dev.tamboui` 兼容模块，以 0.4.0 源码为基线编译并替代对应行内实现；模块纳入 Maven reactor 和发布包依赖解析。禁止手改 `~/.m2`，也不复制与本修复无关的 TamboUI 模块。上游发布兼容版本后，删除该模块并恢复官方依赖。

## 测试策略

### 纯单元测试

- 相同 Buffer 的 diff 为空。
- 单字符、单样式、行尾缩短只产生最小区间。
- 状态栏波光变化不包含输入框边框。
- CJK、emoji、continuation cell 的 patch 边界完整。
- 高度增减后重叠区域保留。
- `println` 后 live Buffer 快照仍有效。
- 错误快照只触发一次完整 live 区恢复。

### 记录型 Backend 测试

- 静止连续 100 帧：首帧后零字符写入、零 `EL`、零 flush。
- 波光连续 100 帧：只写状态行变化范围，不出现输入框边框内容。
- 输入 ASCII 或 CJK：不触碰其他行。
- 一帧最多一次 write/flush。
- 一批 scrollback 不逐行重画 live 区。
- 禁用同步输出时上述断言全部成立。

### PTY 端到端测试

macOS/Linux 回归现有 PTY 脚本；Windows Terminal/ConPTY 增加原始输出与视觉验收：

- 空闲时无持续 VT 输出。
- 输入框更新不含整行清除。
- 思考动画期间输入框区域的字符不被重写。
- 快速输入、退格、中文 IME 不错位。
- 打开和关闭动态面板不留残影。
- 连续调整窗口后最终画面正确，停稳只重建一次。

### 人工验收

空闲、输入、思考、运行工具和后台任务五种状态均无可见闪动；Windows Terminal 是主要环境。不支持 DEC 2026 的终端也必须稳定。

## 验收标准

1. 静态逻辑帧不产生终端输出。
2. 普通帧不执行整行 `EL` 或整块清空。
3. 波光动画只更新自身变化 cell，输入框不被重写。
4. 输入一个字符只更新输入相关区域和光标。
5. 一帧最多一次 write/flush。
6. scrollback 批次至多触发一次 live 提交。
7. resize 过程中不逐事件 `ESC[J`；停稳只恢复一次。
8. 动态高度、scrollback、中文 IME、现有按键和面板行为全部保持。
9. Windows Terminal 中不再出现输入框或整屏闪动。
