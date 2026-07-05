package io.github.javaside.springai.codetui.agent;

/** 用户取消了问询（Esc）。桥从阻塞中被唤醒后抛出；此时回合已被 dispose，本异常随流被丢弃。 */
public class QuestionCancelledException extends RuntimeException {
    public QuestionCancelledException() { super("user cancelled the question"); }
}
