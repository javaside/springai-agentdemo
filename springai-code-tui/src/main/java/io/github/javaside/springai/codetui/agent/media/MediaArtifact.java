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

    /** 显示用短 id = 完整 sha 前 16 位。 */
    public String shortId() { return sha.substring(0, 16); }
}
