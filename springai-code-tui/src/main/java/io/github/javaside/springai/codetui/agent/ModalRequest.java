package io.github.javaside.springai.codetui.agent;

/**
 * 一次需要抢占 UI 焦点的模态请求。
 *
 * <p><b>为何要统一</b>：问询（{@link AskRequest}）与审批（{@link PermissionRequest}）竞争同一个输入焦点，
 * 各搞一套状态必然互相覆盖。二者统一进 {@code ConversationState} 的模态请求队列，UI 逐个弹。
 *
 * <p><b>sealed 的包约束</b>：本项目无 {@code module-info}（unnamed module），
 * permitted 子类型必须与本接口<b>同包</b>——这就是 {@link PermissionRequest} 放在
 * {@code agent/} 而非 {@code agent/permission/} 的原因。
 *
 * <p><b>穷尽分支怎么写</b>：本模块编译在 {@code maven.compiler.release=17}，
 * 而按类型模式 {@code switch}（JEP 441）要 21，故消费方一律用
 * {@code if (r instanceof AskRequest a) … else if (r instanceof PermissionRequest p) …}。
 * sealed 在 17 上照常生效（禁止外部实现、{@code getPermittedSubclasses} 可读），
 * 只是穷尽性由人保证而非编译器——新增子类型时须手动巡查所有 instanceof 链。
 */
public sealed interface ModalRequest permits AskRequest, PermissionRequest {

    /** 发起该请求的回合（供 UI 迟到过滤）。 */
    long turnId();

    /**
     * 统一取消入口：唤醒阻塞在本请求上的工具线程。
     *
     * <p><b>活性纪律（本接口不自保）</b>：实现只负责「投一个取消信号」，
     * 它<b>不</b>保证工具线程一定会醒——那取决于应答口的实现与调用方是否真的调了本方法。
     * 两条外部逃生口缺一不可：① 回合取消时 {@code ConversationState.cancelCurrent()} 必须
     * <b>遍历整条模态队列</b>对每个 pending 请求调用本方法；② 回合被 dispose 时框架中断工具线程。
     * 漏了第一条，被取消回合里阻塞的（子 agent）工具线程会<b>永久 park</b>，
     * 而它持着回合——整个 agent 静默挂死，无报错也无出口。
     *
     * <p>一请求一个应答口：取消其一不得影响其余 pending 请求（并发审批必须各自独立唤醒）。
     * 实现须可重复调用且与正常应答竞争安全——「首个信号胜出」，迟到的取消被丢弃。
     */
    void cancel();
}
