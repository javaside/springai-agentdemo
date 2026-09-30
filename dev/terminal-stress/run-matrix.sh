#!/bin/bash
# Terminal.app 崩溃诱因定位矩阵 —— 交互驱动脚本
#
# 用法（必须在 Apple Terminal.app 里运行）：
#   bash dev/terminal-stress/run-matrix.sh [每场景秒数，默认 60]
#
# 流程：逐场景运行 ScenarioStress（真实 InlineDisplay 字节流），期间请你
#   在同一个窗口里持续用中文拼音输入法打字（像平时插话一样）。
# 每个场景结束后自动检查 ~/Library/Logs/DiagnosticReports/ 是否新增
#   Terminal-*.ips（= Terminal 崩溃）。全部跑完输出判定表。
#
# 场景清单见 ScenarioStress.java 头注释：
#   println(code-tui 流式打印:中部插行) ink(Claude Code 式整帧擦写)
#   preview(预览行起落) band(IME 光标带) anim(整帧动画) flood(裸洪峰,打字可选)

set -u
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
MOD="$ROOT/springai-tamboui-inline-patch"
DUR="${1:-60}"
REPORTS="$HOME/Library/Logs/DiagnosticReports"
RESULTS=()

# 类路径：patch 模块 classes + 依赖（dependency:build-classpath 生成）+ jline3 后端
CPFILE="$(mktemp)"
( cd "$MOD" && mvn -q dependency:build-classpath -Dmdep.outputFile="$CPFILE" -DincludeScope=compile ) || { echo "classpath 生成失败"; exit 1; }
JLINE3=$(ls "$HOME"/.m2/repository/dev/tamboui/tamboui-jline3-backend/*/tamboui-jline3-backend-*.jar 2>/dev/null | grep -v sources | head -1)
CP="$MOD/target/classes:$JLINE3:$(cat "$CPFILE"):$ROOT/dev/terminal-stress/classes"

# 编译
mkdir -p "$ROOT/dev/terminal-stress/classes"
javac -cp "$MOD/target/classes:$JLINE3:$(cat "$CPFILE")" \
      -d "$ROOT/dev/terminal-stress/classes" \
      "$ROOT/dev/terminal-stress/ScenarioStress.java" || { echo "编译失败"; exit 1; }

count_reports() { ls "$REPORTS" 2>/dev/null | grep -c '^Terminal-.*\.ips$'; }

for SC in println ink preview band anim flood; do
  BEFORE=$(count_reports)
  echo ""
  echo "══════════════════════════════════════════════════════════"
  echo "场景 [$SC] 即将开始，持续 ${DUR}s"
  if [ "$SC" = "flood" ]; then
    echo "→ 本场景无输入框（纯吞吐基线），打字可选。"
  else
    echo "→ 屏幕底部会出现圆角【输入框】：直接打字即可，字符会回显；"
    echo "→ 请用【中文拼音输入法】持续打字/删改/上屏，像平时插话一样。"
    echo "→ Enter=清空发送，Ctrl+C=提前结束本场景（不会杀终端）。"
  fi
  echo "══════════════════════════════════════════════════════════"
  sleep 3
  java -cp "$CP" ScenarioStress "$SC" "$DUR"
  RC=$?
  AFTER=$(count_reports)
  if [ "$AFTER" -gt "$BEFORE" ]; then
    RESULTS+=("[$SC] rc=$RC  ❌ 崩溃（新增 $((AFTER-BEFORE)) 份 Terminal-*.ips）")
  else
    RESULTS+=("[$SC] rc=$RC  ✅ 存活")
  fi
  echo ""
  echo "—— [$SC] 结束，判定见上；3 秒后进入下一场景 ——"
  sleep 3
done

echo ""
echo "════════════════ 判定表 ════════════════"
for r in "${RESULTS[@]}"; do echo "$r"; done
echo "════════════════════════════════════════"
echo "关键对比：println(code-tui 现行) vs ink(Claude Code 式)。
  println 崩而 ink 活 → 把 code-tui 打印路径改成 ink 式即根治；
  其他单项崩 → 对应模式即诱因（preview=行增删/band=IME光标带/anim=帧率）；
  flood 都崩 → 与代码无关，纯终端吞吐极限，换 iTerm2/ghostty。"
