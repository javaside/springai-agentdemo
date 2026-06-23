package com.example.springai.core.demo;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.SimpleVectorStore;

import java.util.List;

/**
 * 示例 7：模块化 RAG（RetrievalAugmentationAdvisor）。
 *
 * <p>这是官方推荐的 RAG 用法，和示例 6 的「手写检索 + 拼接」形成对比：
 * <ul>
 *   <li>示例 6（RagDemo）：你自己 similaritySearch、自己把检索结果塞进提示词——看清每一步原理；</li>
 *   <li>本示例（RagAdvisorDemo）：把检索与增强交给一个 {@link RetrievalAugmentationAdvisor}，
 *       业务代码只需 {@code .advisors(rag)}，其余（检索→拼上下文→增强提问）全自动。</li>
 * </ul>
 *
 * <p>这个 advisor 是“模块化”的：检索（DocumentRetriever）、查询改写（QueryTransformer）、
 * 查询扩展（QueryExpander）、结果合并（DocumentJoiner）、提示增强（QueryAugmenter）都是可插拔组件。
 * 本示例只用最核心的一项 —— 基于向量库的 {@link VectorStoreDocumentRetriever}，其余用默认。
 */
public class RagAdvisorDemo implements Demo {

    private final ChatClient chatClient;
    private final EmbeddingModel embeddingModel;

    public RagAdvisorDemo(ChatClient chatClient, EmbeddingModel embeddingModel) {
        this.chatClient = chatClient;
        this.embeddingModel = embeddingModel;
    }

    @Override
    public String title() {
        return "模块化 RAG（RetrievalAugmentationAdvisor，一个 advisor 全搞定）";
    }

    @Override
    public int order() {
        return 7;
    }

    @Override
    public void run() {
        // 1) 准备向量库 + 私有知识（和示例 6 相同的数据，方便对比）
        SimpleVectorStore vectorStore = SimpleVectorStore.builder(embeddingModel).build();
        vectorStore.add(List.of(
                new Document("公司内部 Wiki 的访问地址是 https://wiki.acme.internal ，需用工牌账号登录。"),
                new Document("报销流程：在 OA 系统提交申请，财务每周三统一打款。"),
                new Document("我们的旗舰产品代号为「天枢」，将于 2026 年第三季度发布。")
        ));

        // 2) 文档检索器：从向量库按相似度取 topK 篇。外面包一层 LoggingRetriever 把“检索到了什么”打印出来。
        DocumentRetriever retriever = new LoggingRetriever(
                VectorStoreDocumentRetriever.builder()
                        .vectorStore(vectorStore)
                        .topK(2)
                        .build());

        // 3) 一个 advisor 搞定 RAG：把检索器交给它即可，检索→拼上下文→增强提问全自动。
        RetrievalAugmentationAdvisor ragAdvisor = RetrievalAugmentationAdvisor.builder()
                .documentRetriever(retriever)
                .build();

        String question = "我们的旗舰产品代号叫什么？什么时候发布？";
        System.out.println("问：" + question + "\n");

        // 注意：业务代码这里非常干净——只加一个 advisor，不再手写检索与拼接。
        String answer = chatClient.prompt()
                .advisors(ragAdvisor)
                .user(question)
                .call()
                .content();

        System.out.println("\n答：" + answer);
        System.out.println("""

                对比示例 6：那边要自己 similaritySearch、自己拼上下文进提示词；
                这里只用 .advisors(ragAdvisor) 一行，检索与增强由 RetrievalAugmentationAdvisor 自动完成。
                它还是“模块化”的：查询改写、查询扩展、结果合并、提示增强都能各自替换，适合搭建更复杂的 RAG。
                """);
    }

    /**
     * 给 DocumentRetriever 包一层日志：把每次实际检索到的文档打印出来，让“自动检索”看得见。
     * DocumentRetriever 是个函数式接口（Query -> List<Document>），实现 retrieve 即可。
     */
    private record LoggingRetriever(DocumentRetriever delegate) implements DocumentRetriever {
        @Override
        public List<Document> retrieve(Query query) {
            List<Document> docs = delegate.retrieve(query);
            System.out.println("  🔎 RAG 自动检索：query=\"" + query.text() + "\" → 取回 " + docs.size() + " 篇：");
            docs.forEach(d -> System.out.println("     - " + d.getText()));
            return docs;
        }
    }
}
