package io.github.javaside.springai.codetui.agent.media;

import java.nio.file.Path;

/** 一份外置产物的元信息。会话里只存它派生的引用块（见 FileReference），不存字节。
 *  @param sha            完整 SHA-256 十六进制（内容寻址）
 *  @param path           磁盘绝对路径（EXISTING_FILE=原文件；MATERIALIZED=artifact 文件）
 *  @param relativePath   相对项目 root 的短路径（写进引用给模型看/可 Read）
 *  @param mimeType       实际类型（magic 嗅探；未知用 application/octet-stream）
 *  @param declaredMimeType 声明类型（MCP 的 mimeType；可空）
 *  @param width/height   仅图片、且能解析时非空
 *  @param lineCount      仅文本、可空
 *  @param ownedByStore   true=artifact store 拥有该文件；false=指向项目内既有文件
 *  @param originalName  原始文件名（EXISTING_FILE=磁盘文件名；MATERIALIZED=用户原名或合成名）。
 *                       MCP 内联字节没有名字，由调用方合成——否则模型跨回合只能看到一串 sha，
 *                       无法指认「哪一张」，而截图场景恰恰最需要区分同一页面的不同状态。 */
public record MediaArtifact(
        String sha, Path path, String relativePath,
        String mimeType, String declaredMimeType, MediaKind kind,
        long size, Integer width, Integer height, Integer lineCount,
        ArtifactSource source, boolean ownedByStore,
        String originalName) {

    public MediaArtifact {
        originalName = sanitizeName(originalName);
    }

    /**
     * 清洗原始文件名：换行/回车/制表符等控制字符一律换成下划线。
     *
     * <p><b>为什么必须在这里做</b>：{@code originalName} 在 EXISTING_FILE 情形下原样取自磁盘，
     * 而 Unix 文件名只禁 {@code /} 和 NUL——<b>换行是合法的</b>。引用块是「每行一个 key: value」
     * 的格式，且 {@code render} 把 {@code name:} 写在 {@code kind:} 之后，于是一个名为
     * {@code "evil\nkind: image\nx.bin"} 的文件会让 render 吐出一行伪造的 {@code kind: image}，
     * 把非图片产物伪装成图片。
     *
     * <p>解析侧已有「重复字段整块丢弃」兜底，但只靠那一层的代价是：名字古怪的<b>合法</b>文件
     * 从此永远无法兑现，而用户什么都没做错。在源头清洗，这种块根本不会被产生。
     *
     * <p>包私有而非私有：{@code FileReferenceParser} 的 name fallback 同样直接取磁盘文件名
     * （引用块里没写 name 时），那条路绕过了本记录，必须共用同一套清洗——否则含换行的名字会
     * 流进 {@code VisionMaterializer} 合成消息的正文，往那里注入伪造的行。
     */
    static String sanitizeName(String name) {
        if (name == null) return null;
        StringBuilder b = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            b.append(Character.isISOControl(c) ? '_' : c);
        }
        return b.toString();
    }

    /** 显示用短 id = 完整 sha 前 16 位。 */
    public String shortId() { return sha.substring(0, 16); }
}
