package io.github.javaside.springai.codetui.ui.output;

/**
 * 一条<b>逻辑输出</b>展开成的<b>物理终端行</b>的可续消费游标（设计 §9.1「严格批次」的核心抽象）。
 *
 * <p><b>为什么必须有它</b>：旧 drain 的限速单位是「条」——一条 {@code OutputLine} 一旦开始渲染就
 * 必须 println 到最后一行（markdown 正文、diff 块、无换行超长行都走这条原子路径），单个大输出
 * 展开后可写几百上千行，物理行预算形同虚设（{@code DrainBurstCapTest} 曾靠 SLACK=200 掩盖）。
 * 游标把「渲染」与「提交」拆开：每次只物化下一段物理行，消费方在两个预算（行数 / 时间）任一
 * 耗尽时停下，剩余部分留在游标里等下一批——顺序不变、内容不丢。
 *
 * <p><b>staging 有界性</b>（本接口的契约，实现必须遵守）：
 * <ul>
 *   <li>{@link #hasNext()} 与 {@link #next()} 都以<b>摊还 O(一个物理段)</b>物化下一段物理行，
 *       绝不一次物化整条大输出的全部物理行——也<b>不一次物化单条逻辑行折行的全部段</b>
 *       （fix round I-1：60k 无换行长逻辑行第一次 {@code next()} 只折一段 80 列的内容，
 *       不再整行建 ~770 段；段级推进见 {@code SegmentedWrap}）；</li>
 *   <li>允许的上界是「<b>当前正在产出的那一个物理段</b>」（含其 O(1) 推进状态：剩余偏移 /
 *       当前 span 引用 / 渲染器跨行状态），与逻辑行长度、整条输出的总行数都无关；</li>
 *   <li><b>已知例外（如实声明）</b>：cursor 工厂的一次性成本（diff 的读文件 + LCS，
 *       O(一个工具入参)，受 DiffRenderer 的 LCS_MAX/BODY_CAP 上限约束）无法按段切片，发生在
 *       第一段之前、时间预算之外——每条 diff 输出只付一次。详见
 *       {@code PhysicalOutputQueue} 类注释；</li>
 *   <li>每条物化出的物理行宽度 ≤ 终端宽（消费方出口处另有兜底折行，但游标必须保证它退化为
 *       no-op，否则行数记账在两处会对不上）；</li>
 *   <li>{@code next()} 返回的 {@code PhysicalLine.raw} 是该段所属<b>逻辑行的折行前原文</b>
 *       （String 或 Text；同一条逻辑行的所有段共享同一引用），留底方据此记录原文；
 *       无折行来源的自包含行 raw 为 null；</li>
 *   <li>游标内部异常由实现自兜（渲染降级），绝不把异常抛进 UI 更新批。</li>
 * </ul>
 *
 * <p><b>状态跨调用保持</b>：markdown 代码围栏 / 围栏内语言 / 跨行块注释状态、diff 的
 * header/hunk/行样式与真实行号推进，都保存在游标（或其工厂持有的渲染器）里，跨多次
 * {@code next()} 与多个批次不回退、不重放——拆批只是拆提交，不拆渲染语义。
 */
public interface OutputCursor {

    /**
     * 是否还有未提交的物理行。实现只允许为回答这个问题物化「推进状态」（见类注释的 staging
     * 契约），不得借机展开整条输出或整条逻辑行的折行。
     */
    boolean hasNext();

    /**
     * 取下一条物理行。仅在 {@link #hasNext()} 为 true 时调用；无行时返回 {@code null}
     * （防御性约定，不抛异常——消费方拿到 null 就当游标耗尽处理）。
     */
    PhysicalOutputQueue.PhysicalLine next();
}
