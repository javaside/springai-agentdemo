# springai-jline-demo

JLine 3 `Terminal` 接口的入门示例。

## TerminalBasics —— 一个例子学会 Terminal 怎么用

`io.github.javaside.springai.jline.terminal.TerminalBasics` 是一个**读源码就能学会、跑起来能看到效果**的单文件示例。
它按 `Terminal` 接口 Javadoc 的几大块，依次用**真实运行的代码**演示每个方法的用法，逐行注释；
交互块会提示你操作、你操作后立刻有反应；每节之间停下来等你按键，不会一闪而过。

| 块 | 演示什么 |
|----|---------|
| 1. Creating & Lifecycle | 用 `TerminalBuilder` 真实建一个 Terminal → 往它写 → 看它产出的内容 → `close()` |
| 2. Input and Output | `writer()`/`output()` 输出、`encoding()`、`reader()` 真读你输入的一行 |
| 3. Terminal Capabilities | `getNumericCapability` 画色块、`getStringCapability` 看控制序列、`puts` 真加粗/响铃 |
| 4. Terminal Attributes | `enterRawMode()` 进原始模式，等你按键，看“无回显、不等回车” |
| 5. Signal Handling | `handle(Signal.WINCH)`，提示你拖动改变窗口大小，处理器当场打印新尺寸 |
| 6. Mouse Support | `hasMouseSupport`/`trackMouse`/`readMouseEvent`，点鼠标显示坐标 |

## 运行

必须在**真实终端**里运行（IDE 控制台/管道是 dumb 终端，交互效果看不全）：

```bash
mvn -q -pl springai-jline-demo package
java -jar springai-jline-demo/target/springai-jline-demo-1.0.0.jar
```
