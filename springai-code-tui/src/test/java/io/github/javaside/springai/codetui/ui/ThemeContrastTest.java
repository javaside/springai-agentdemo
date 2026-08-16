package io.github.javaside.springai.codetui.ui;

import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 调色板的深色终端护栏：不许再出现「颜色由终端说了算」或「暗到看不见」的取值。
 *
 * <p><b>为什么需要</b>：{@code Color.DARK_GRAY} 实际是 ANSI 亮黑（SGR 90），而 ANSI 0–15 的取值
 * 由用户终端 profile 决定——同一个 90 在不同配色下从 {@code #333} 到 {@code #666} 不等，代码侧
 * 控制不了，深色窗口里就是「看不见」。同理 {@code Color.rgb(...)} 发的是 {@code 38;2;r;g;b}，
 * 目标终端（Apple Terminal，{@code COLORTERM} 为空）不支持 truecolor，整段被静默忽略。
 *
 * <p><b>为什么对比度只卡中性色</b>：{@code ERROR}/{@code FAIL}/{@code MODE_BYPASS} 用 ANSI 红
 * （{@code (170,0,0)}），相对亮度天然很低，靠<b>色相</b>而不是明度区分。一刀切的亮度阈值会把它们
 * 全部误伤，所以只对 {@code max-min ≤ 16} 的中性色（灰阶）设下限。
 */
class ThemeContrastTest {

    /** 参考底色 #1e1e1e：深色终端的常见背景。纯黑会高估对比度，故取偏亮的一档。 */
    private static final Color.Rgb REFERENCE_BG = new Color.Rgb(30, 30, 30);
    /** 可读下限。3:1 对应灰阶 242（#6c6c6c，实测 3.18:1）；亮黑按 #555 算只有 2.24:1。 */
    private static final double MIN_CONTRAST = 3.0;
    /** 中性色判定：三通道极差不超过这个值即视为灰。 */
    private static final int NEUTRAL_TOLERANCE = 16;

    private static final List<Class<?>> PALETTES =
            List.of(Theme.class, MarkdownRenderer.class, SyntaxHighlighter.class);

    @Test
    @DisplayName("调色板不得使用 truecolor：目标终端 COLORTERM 为空，38;2 会被静默忽略")
    void paletteNeverUsesTrueColor() {
        List<String> bad = new ArrayList<>();
        forEachStyle((owner, style) -> {
            style.fg().filter(c -> c instanceof Color.Rgb).ifPresent(c -> bad.add(owner + " 的前景"));
            style.bg().filter(c -> c instanceof Color.Rgb).ifPresent(c -> bad.add(owner + " 的底色"));
        });
        forEachColor((owner, color) -> {
            if (color instanceof Color.Rgb) bad.add(owner);
        });
        assertTrue(bad.isEmpty(),
                "这些颜色用了 truecolor，在目标终端上不会生效，改用 Color.indexed(...)：" + bad);
    }

    @Test
    @DisplayName("前景不得用 ANSI 黑/亮黑：取值由终端 profile 决定，深色窗口下常与背景同色")
    void foregroundNeverUsesAnsiBlack() {
        List<String> bad = new ArrayList<>();
        forEachStyle((owner, style) -> style.fg().ifPresent(c -> {
            String sgr = c.toAnsiForeground();
            // ⚠ 不能用 equals 判：Color.DARK_GRAY 是 Named 包着 Ansi，
            // 与 Color.ansi(AnsiColor.BRIGHT_BLACK) 实测 equals == false。
            if ("30".equals(sgr) || "90".equals(sgr)) bad.add(owner + "（SGR " + sgr + "）");
        }));
        assertTrue(bad.isEmpty(),
                "这些前景用了 ANSI 黑/亮黑，深色终端下看不见，改用 Theme 的灰阶三档：" + bad);
    }

    @Test
    @DisplayName("中性色前景对其背景的对比度不低于 3:1")
    void neutralForegroundsAreReadable() {
        List<String> bad = new ArrayList<>();
        forEachStyle((owner, style) -> {
            Optional<Color> fg = style.fg();
            if (fg.isEmpty()) return;
            Color.Rgb rgb = fg.get().toRgb();
            if (!isNeutral(rgb)) return;   // 饱和色靠色相区分，不按亮度卡
            Color.Rgb bg = style.bg().map(Color::toRgb).orElse(REFERENCE_BG);
            double ratio = contrast(rgb, bg);
            if (ratio < MIN_CONTRAST) {
                bad.add(String.format("%s 对比度 %.2f:1", owner, ratio));
            }
        });
        assertTrue(bad.isEmpty(),
                "这些中性色前景在深色终端下达不到 " + MIN_CONTRAST + ":1，请提亮：" + bad);
    }

    // ── 反射遍历 ────────────────────────────────────────────────────────
    private static void forEachStyle(BiConsumer<String, Style> visitor) {
        forEachStaticField(Style.class, (owner, value) -> visitor.accept(owner, (Style) value));
    }

    private static void forEachColor(BiConsumer<String, Color> visitor) {
        forEachStaticField(Color.class, (owner, value) -> visitor.accept(owner, (Color) value));
    }

    private static void forEachStaticField(Class<?> type, BiConsumer<String, Object> visitor) {
        for (Class<?> palette : PALETTES) {
            for (Field field : palette.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())) continue;
                if (!type.isAssignableFrom(field.getType())) continue;
                field.setAccessible(true);
                Object value;
                try {
                    value = field.get(null);
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException("读不到 " + palette.getSimpleName() + "." + field.getName(), e);
                }
                if (value == null) continue;
                visitor.accept(palette.getSimpleName() + "." + field.getName(), value);
            }
        }
    }

    // ── WCAG 相对亮度 / 对比度 ───────────────────────────────────────────
    private static boolean isNeutral(Color.Rgb c) {
        int max = Math.max(c.r(), Math.max(c.g(), c.b()));
        int min = Math.min(c.r(), Math.min(c.g(), c.b()));
        return max - min <= NEUTRAL_TOLERANCE;
    }

    private static double contrast(Color.Rgb a, Color.Rgb b) {
        double la = luminance(a);
        double lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    private static double luminance(Color.Rgb c) {
        return 0.2126 * channel(c.r()) + 0.7152 * channel(c.g()) + 0.0722 * channel(c.b());
    }

    private static double channel(int value) {
        double s = value / 255.0;
        return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
    }
}
