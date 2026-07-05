package io.github.javaside.springai.codetui.ui;

import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.text.Span;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 基于 token 的轻量语法高亮器（正则/扫描，非完整语法解析）。
 *
 * <p>逐字符从左到右扫描一行代码，按优先级识别 块注释 / 行注释 / 字符串 / 数字 / 标识符（关键字），
 * 各自产出一个带样式的 {@link Span}。注释和字符串优先级最高——出现在字符串/注释内部的关键字
 * 不会被再次当作关键字高亮。</p>
 *
 * <p>健壮性：对任何奇怪输入都不抛异常；空行返回 {@code List.of(Span.raw(""))}。</p>
 */
public final class SyntaxHighlighter {

    private SyntaxHighlighter() {
    }

    // ---- token 样式常量 ----
    // 采用 One Dark 风格的 256 色（indexed，不走终端 16 色主题，绿底/深底上都读得清）：
    //   关键字=紫，类型=金，函数=蓝，字符串=绿，数字=橙，注解=金，注释=灰。
    private static final Style KEYWORD    = Style.create().fg(Color.indexed(176));  // #d787d7 紫
    private static final Style TYPE       = Style.create().fg(Color.indexed(180));  // #d7af87 金：大写开头标识符
    private static final Style FUNCTION   = Style.create().fg(Color.indexed(75));   // #5fafff 蓝：后跟 ( 的标识符
    private static final Style STRING     = Style.create().fg(Color.indexed(114));  // #87d787 绿
    private static final Style NUMBER     = Style.create().fg(Color.indexed(173));  // #d7875f 橙
    private static final Style ANNOTATION = Style.create().fg(Color.indexed(180));  // #d7af87 金：@Xxx
    private static final Style COMMENT    = Style.create().fg(Color.indexed(243));  // #767676 灰

    /**
     * 高亮一行代码。
     *
     * @param line           一行代码文本（不含换行符）
     * @param lang           代码块语言（可能为 null / 空 / 未知）
     * @param inBlockComment 进入本行时是否处于跨行块注释中
     * @return spans + 离开本行后是否仍在块注释里
     */
    public static Result highlight(String line, String lang, boolean inBlockComment) {
        if (line == null || line.isEmpty()) {
            return new Result(List.of(Span.raw("")), inBlockComment);
        }
        try {
            return scan(line, Lang.of(lang), inBlockComment);
        } catch (RuntimeException ex) {
            // 兜底：任何扫描异常都退化为单个默认 span，绝不向调用方抛出。
            return new Result(List.of(Span.raw(line)), inBlockComment);
        }
    }

    private static Result scan(String s, Lang lang, boolean inBlockComment) {
        List<Span> spans = new ArrayList<>();
        StringBuilder plain = new StringBuilder();
        int i = 0;
        int n = s.length();
        boolean block = inBlockComment;

        while (i < n) {
            // 1) 处于块注释中：吞到 "*/" 或行尾。
            if (block) {
                int end = s.indexOf("*/", i);
                if (end < 0) {
                    flushPlain(spans, plain);
                    spans.add(Span.styled(s.substring(i), COMMENT));
                    return new Result(spans, true);
                }
                flushPlain(spans, plain);
                spans.add(Span.styled(s.substring(i, end + 2), COMMENT));
                i = end + 2;
                block = false;
                continue;
            }

            char c = s.charAt(i);

            // 2) 块注释起始 "/*"（仅支持块注释的语言）。
            if (lang.blockComments && c == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
                flushPlain(spans, plain);
                int end = s.indexOf("*/", i + 2);
                if (end < 0) {
                    spans.add(Span.styled(s.substring(i), COMMENT));
                    return new Result(spans, true);
                }
                spans.add(Span.styled(s.substring(i, end + 2), COMMENT));
                i = end + 2;
                continue;
            }

            // 3) 行注释：吞到行尾。
            if (isLineCommentStart(s, i, lang)) {
                flushPlain(spans, plain);
                spans.add(Span.styled(s.substring(i), COMMENT));
                return new Result(spans, false);
            }

            // 3.5) 注解 @Xxx（Java/TS 装饰器风格）：@ 紧跟标识符起始时，整体作为注解。
            if (c == '@' && i + 1 < n && isIdentStart(s.charAt(i + 1))) {
                flushPlain(spans, plain);
                int end = i + 1;
                while (end < n && isIdentPart(s.charAt(end))) {
                    end++;
                }
                spans.add(Span.styled(s.substring(i, end), ANNOTATION));
                i = end;
                continue;
            }

            // 4) 字符串： " ' `
            if (c == '"' || c == '\'' || c == '`') {
                flushPlain(spans, plain);
                int end = consumeString(s, i, c);
                spans.add(Span.styled(s.substring(i, end), STRING));
                i = end;
                continue;
            }

            // 5) 数字：位于单词边界处的数字起始。
            if (Character.isDigit(c) && !isIdentPart(prevChar(s, i))) {
                flushPlain(spans, plain);
                int end = consumeNumber(s, i);
                spans.add(Span.styled(s.substring(i, end), NUMBER));
                i = end;
                continue;
            }

            // 6) 标识符 / 关键字。
            if (isIdentStart(c)) {
                int end = i + 1;
                while (end < n && isIdentPart(s.charAt(end))) {
                    end++;
                }
                String word = s.substring(i, end);
                Style tok = lang.keywords.contains(word) ? KEYWORD : classifyWord(word, s, end);
                if (tok != null) {
                    flushPlain(spans, plain);
                    spans.add(Span.styled(word, tok));
                } else {
                    plain.append(word);   // 普通标识符（变量/字段）：默认色
                }
                i = end;
                continue;
            }

            // 7) 其它字符：累积为默认文本。
            plain.append(c);
            i++;
        }

        flushPlain(spans, plain);
        if (spans.isEmpty()) {
            spans.add(Span.raw(s));
        }
        return new Result(spans, block);
    }

    /**
     * 非关键字标识符的细分：紧跟 {@code (} 的→函数(蓝)；大写开头的→类型(金)；否则 null（普通标识符，默认色）。
     * {@code end} 为该标识符在 s 中的结束下标（keyword 判断已在调用处用 lang.keywords 处理）。
     */
    private static Style classifyWord(String word, String s, int end) {
        if (end < s.length() && s.charAt(end) == '(') {
            return FUNCTION;                               // 紧跟 ( → 函数/方法调用
        }
        if (Character.isUpperCase(word.charAt(0))) {
            return TYPE;                                   // 大写开头 → 类型名（App / String / Test）
        }
        return null;
    }

    private static void flushPlain(List<Span> spans, StringBuilder plain) {
        if (plain.length() > 0) {
            spans.add(Span.raw(plain.toString()));
            plain.setLength(0);
        }
    }

    /** 消费从 start（引号）开始的字符串字面量，返回结束引号之后的下标；未闭合则到行尾。 */
    private static int consumeString(String s, int start, char quote) {
        int i = start + 1;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < n) {
                i += 2; // 跳过被转义字符（含 \" \' \` \\）。
                continue;
            }
            if (c == quote) {
                return i + 1;
            }
            i++;
        }
        return n; // 未闭合：整行剩余都算字符串。
    }

    /** 消费从 start 开始的数字，允许 . _ x X 及十六进制字母。 */
    private static int consumeNumber(String s, int start) {
        int i = start;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (Character.isDigit(c) || c == '.' || c == '_'
                    || c == 'x' || c == 'X'
                    || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')) {
                i++;
            } else {
                break;
            }
        }
        return i;
    }

    private static boolean isLineCommentStart(String s, int i, Lang lang) {
        if (lang.slashSlash && s.charAt(i) == '/' && i + 1 < s.length() && s.charAt(i + 1) == '/') {
            return true;
        }
        return lang.hash && s.charAt(i) == '#';
    }

    private static char prevChar(String s, int i) {
        return i > 0 ? s.charAt(i - 1) : ' ';
    }

    private static boolean isIdentStart(char c) {
        return Character.isLetter(c) || c == '_' || c == '$';
    }

    private static boolean isIdentPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    /** 高亮结果：spans 及离开本行后是否仍处于跨行块注释中。 */
    public record Result(List<Span> spans, boolean stillInBlockComment) {
    }

    // ---- 语言配置 ----

    private static final class Lang {
        final Set<String> keywords;
        final boolean slashSlash;   // 支持 // 行注释
        final boolean hash;         // 支持 #  行注释
        final boolean blockComments; // 支持 /* */ 块注释

        Lang(Set<String> keywords, boolean slashSlash, boolean hash, boolean blockComments) {
            this.keywords = keywords;
            this.slashSlash = slashSlash;
            this.hash = hash;
            this.blockComments = blockComments;
        }

        static Lang of(String raw) {
            String lang = raw == null ? "" : raw.trim().toLowerCase();
            switch (lang) {
                case "java":
                    return new Lang(JAVA_KW, true, false, true);
                case "python":
                case "py":
                    return new Lang(PYTHON_KW, false, true, false);
                case "javascript":
                case "js":
                case "typescript":
                case "ts":
                    return new Lang(JS_KW, true, false, true);
                case "bash":
                case "sh":
                case "shell":
                    return new Lang(BASH_KW, false, true, false);
                case "json":
                    return new Lang(JSON_KW, true, false, false);
                case "css":
                    return new Lang(Set.of(), false, false, true);
                case "xml":
                case "html":
                    return new Lang(Set.of(), false, false, false);
                default:
                    // 未知/通用：关键字取并集，行注释 // 与 # 都支持，块注释也支持。
                    return new Lang(GENERIC_KW, true, true, true);
            }
        }
    }

    private static final Set<String> JAVA_KW = Set.of(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char",
            "class", "const", "continue", "default", "do", "double", "else", "enum",
            "extends", "final", "finally", "float", "for", "goto", "if", "implements",
            "import", "instanceof", "int", "interface", "long", "native", "new", "package",
            "private", "protected", "public", "return", "short", "static", "strictfp",
            "super", "switch", "synchronized", "this", "throw", "throws", "transient",
            "try", "void", "volatile", "while", "var", "record", "sealed", "yield",
            "true", "false", "null");

    private static final Set<String> PYTHON_KW = Set.of(
            "False", "None", "True", "and", "as", "assert", "async", "await", "break",
            "class", "continue", "def", "del", "elif", "else", "except", "finally",
            "for", "from", "global", "if", "import", "in", "is", "lambda", "nonlocal",
            "not", "or", "pass", "raise", "return", "try", "while", "with", "yield",
            "match", "case", "self");

    private static final Set<String> JS_KW = Set.of(
            "abstract", "any", "as", "async", "await", "boolean", "break", "case", "catch",
            "class", "const", "continue", "debugger", "declare", "default", "delete", "do",
            "else", "enum", "export", "extends", "false", "finally", "for", "from",
            "function", "get", "if", "implements", "import", "in", "instanceof", "interface",
            "let", "new", "null", "number", "of", "private", "protected", "public", "readonly",
            "return", "set", "static", "string", "super", "switch", "this", "throw", "true",
            "try", "type", "typeof", "undefined", "var", "void", "while", "with", "yield");

    private static final Set<String> BASH_KW = Set.of(
            "if", "then", "else", "elif", "fi", "case", "esac", "for", "select", "while",
            "until", "do", "done", "in", "function", "time", "coproc", "return", "break",
            "continue", "exit", "export", "local", "readonly", "declare", "unset", "echo",
            "cd", "source", "alias", "set", "trap", "shift", "eval", "exec", "test");

    private static final Set<String> JSON_KW = Set.of("true", "false", "null");

    private static final Set<String> GENERIC_KW = union(JAVA_KW, PYTHON_KW, JS_KW, BASH_KW);

    @SafeVarargs
    private static Set<String> union(Set<String>... sets) {
        java.util.HashSet<String> all = new java.util.HashSet<>();
        for (Set<String> s : sets) {
            all.addAll(s);
        }
        return Set.copyOf(all);
    }
}
