package com.example.springai.core.demo;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 示例 6：RAG（检索增强生成）—— 手把手版。
 *
 * <p>RAG 的核心思路：先把“私有知识”存进向量库；用户提问时先「检索」最相关的几条知识，
 * 再把它们拼进提示词，让模型「基于检索到的内容」回答。这样模型就能回答它本来不知道的事。
 *
 * <p>本示例特意手写检索 + 拼接，便于理解 RAG 的每一步：
 * <ol>
 *   <li>把知识文档存入 {@link SimpleVectorStore}（内存向量库，用本地 Embedding 模型向量化）</li>
 *   <li>对用户问题做相似度检索，取最相关的若干条</li>
 *   <li>把检索结果作为「上下文」放进提示词，交给 DeepSeek 回答</li>
 * </ol>
 */
public class RagDemo implements Demo {

    private final ChatClient chatClient;
    private final EmbeddingModel embeddingModel;

    /**
     * chatClient 与 embeddingModel 都由 {@code CoreDemoApplication} 手动创建后传入。
     */
    public RagDemo(ChatClient chatClient, EmbeddingModel embeddingModel) {
        this.chatClient = chatClient;
        this.embeddingModel = embeddingModel;
    }

    @Override
    public String title() {
        return "RAG 检索增强生成（内存向量库，手写流程）";
    }

    @Override
    public int order() {
        return 6;
    }

    @Override
    public void run() {
        // 1) 构建内存向量库，并放入一些“模型不可能知道”的私有知识
        SimpleVectorStore vectorStore = SimpleVectorStore.builder(embeddingModel).build();
        vectorStore.add(List.of(
                new Document("公司内部 Wiki 的访问地址是 https://wiki.acme.internal ，需用工牌账号登录。"),
                new Document("报销流程：在 OA 系统提交申请，财务每周三统一打款。"),
                new Document("我们的旗舰产品代号为「天枢」，将于 2026 年第三季度发布。")
        ));

        String question = "我们的旗舰产品代号叫什么？什么时候发布？";

        // 2) 相似度检索：取与问题最相关的 2 条文档
        List<Document> related = vectorStore.similaritySearch(
                SearchRequest.builder().query(question).topK(2).build());
        String context = related.stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n"));

        System.out.println("【检索到的上下文】\n" + context + "\n");

        // 3) 把检索结果拼进提示词，让模型基于上下文回答
        String answer = chatClient.prompt()
                .system("请只根据给定的「已知信息」回答问题；如果信息不足，就说不知道。")
                .user(u -> u.text("""
                        已知信息：
                        {context}

                        问题：{question}
                        """).param("context", context).param("question", question))
                .call()
                .content();

        System.out.println("问：" + question);
        System.out.println("答：" + answer);
    }
}
