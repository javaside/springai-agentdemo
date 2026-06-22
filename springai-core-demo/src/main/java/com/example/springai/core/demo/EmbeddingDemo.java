package com.example.springai.core.demo;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Component;

/**
 * 示例 5：文本向量化（Embedding）。
 *
 * <p>Embedding 把一段文字转成一串数字（向量），语义相近的文字向量也相近。
 * 这是 RAG / 语义搜索的基础。这里用的是本地 ONNX 模型（无需 API Key）。
 *
 * <p>演示：把 3 句话转成向量，并用「余弦相似度」比较它们的语义接近程度。
 */
@Component
public class EmbeddingDemo implements Demo {

    private final EmbeddingModel embeddingModel;

    public EmbeddingDemo(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    @Override
    public String title() {
        return "文本向量化（本地 Embedding + 相似度）";
    }

    @Override
    public int order() {
        return 5;
    }

    @Override
    public void run() {
        String a = "我喜欢喝咖啡";
        String b = "我爱喝拿铁";          // 与 a 语义相近
        String c = "今天的股市大涨";       // 与 a 语义无关

        float[] va = embeddingModel.embed(a);
        float[] vb = embeddingModel.embed(b);
        float[] vc = embeddingModel.embed(c);

        System.out.println("每个向量的维度：" + va.length);
        System.out.printf("相似度  「%s」 vs 「%s」 = %.4f（应较高）%n", a, b, cosine(va, vb));
        System.out.printf("相似度  「%s」 vs 「%s」 = %.4f（应较低）%n", a, c, cosine(va, vc));
    }

    /** 余弦相似度：两个向量越“同方向”，值越接近 1。 */
    private static double cosine(float[] x, float[] y) {
        double dot = 0, nx = 0, ny = 0;
        for (int i = 0; i < x.length; i++) {
            dot += x[i] * y[i];
            nx += x[i] * x[i];
            ny += y[i] * y[i];
        }
        return dot / (Math.sqrt(nx) * Math.sqrt(ny));
    }
}
