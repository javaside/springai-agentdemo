package io.github.javaside.springai.codetui;

/**
 * 应用元信息。目前只有版本号：打包后从 jar 的 {@code MANIFEST.MF}（{@code Implementation-Version}，
 * 由 maven-jar-plugin 的 {@code addDefaultImplementationEntries} 写入 = {@code project.version}）读取；
 * 在 IDE / target-classes 直跑（无 manifest）时回退成 {@code "dev"}——诚实标注非发布构建。
 */
public final class AppInfo {

    private AppInfo() {}

    /** 版本号，如 {@code 1.0.0}；无法确定时返回 {@code "dev"}。 */
    public static String version() {
        String v = AppInfo.class.getPackage().getImplementationVersion();
        return (v != null && !v.isBlank()) ? v : "dev";
    }

    /** 展示用版本标签：发布构建加 {@code v} 前缀（{@code v1.0.0}），dev 构建原样（{@code dev}）。 */
    public static String versionLabel() {
        String v = version();
        return "dev".equals(v) ? v : "v" + v;
    }
}
