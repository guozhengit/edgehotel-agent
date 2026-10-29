package com.edgehotel.agent.rag;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 内存余弦相似度检索器（M0 最小实现；M1 换向量库/FAISS）。
 * 文档向量懒初始化：首次检索时对知识库全量编码并缓存。
 */
@Component
public class RagRetriever {

    public record Hit(String id, String title, String text, float score) {
    }

    private final KnowledgeBaseLoader loader;
    private final EmbeddingClient embeddingClient;

    private volatile List<float[]> docVectors;

    public RagRetriever(KnowledgeBaseLoader loader, EmbeddingClient embeddingClient) {
        this.loader = loader;
        this.embeddingClient = embeddingClient;
    }

    /** 检索 Top-K 文档。首次调用会对知识库全量向量化（26 段，约数秒）。 */
    public synchronized List<Hit> retrieve(String question, int k) {
        List<KnowledgeBaseLoader.KbDoc> docs = loader.loadSafe();
        List<float[]> vectors = docVectors;
        if (vectors == null) {
            vectors = embeddingClient.embedAll(docs.stream().map(d -> d.text()).toList());
            docVectors = vectors;
        }
        float[] q = embeddingClient.embed(question);
        List<Hit> hits = new ArrayList<>();
        for (int i = 0; i < docs.size(); i++) {
            float score = cosine(q, vectors.get(i));
            hits.add(new Hit(docs.get(i).id(), docs.get(i).title(), docs.get(i).text(), score));
        }
        hits.sort(Comparator.comparing(Hit::score).reversed());
        return hits.subList(0, Math.min(k, hits.size()));
    }

    static float cosine(float[] a, float[] b) {
        float dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        return (float) (dot / (Math.sqrt(na) * Math.sqrt(nb) + 1e-9));
    }
}
