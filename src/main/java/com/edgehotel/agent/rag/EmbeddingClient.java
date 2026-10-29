package com.edgehotel.agent.rag;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.http.codec.ClientCodecConfigurer;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * 调 LM Studio OpenAI 兼容 {@code /v1/embeddings}，把文本转成向量。
 * 当前加载的嵌入模型：text-embedding-qwen__qwen3-embedding-0.6b（Qwen3-Embedding-0.6B Q8_0，1024 维）。
 */
@Component
public class EmbeddingClient {

    private final WebClient client;
    private final String model;

    public EmbeddingClient(@Value("${rag.embedding.model}") String model,
                           @Value("${rag.embedding.base-url}") String baseUrl) {
        this.model = model;
        // embedding 批量响应（26 段 × 1024 维 ≈ 1.3MB JSON）远超 WebClient 默认 256KB 缓冲上限
        this.client = WebClient.builder()
                .baseUrl(baseUrl)
                .exchangeStrategies(ExchangeStrategies.builder()
                        .codecs(c -> c.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                        .build())
                .build();
    }

    /** 单条文本 → 向量。同步阻塞等待（M0 最小实现；M1 可改批量/异步）。 */
    public float[] embed(String text) {
        return embedAll(List.of(text)).get(0);
    }

    /** 批量文本 → 向量列表，与输入顺序一致。 */
    @SuppressWarnings("unchecked")
    public List<float[]> embedAll(List<String> texts) {
        Map<String, Object> body = Map.of("model", model, "input", texts);
        List<Map<String, Object>> data = client.post()
                .uri("/embeddings")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Map.class)
                .map(resp -> (List<Map<String, Object>>) resp.get("data"))
                .block();
        if (data == null) {
            throw new IllegalStateException("embedding 接口返回空");
        }
        return data.stream().map(d -> {
            List<Number> emb = (List<Number>) d.get("embedding");
            float[] arr = new float[emb.size()];
            for (int i = 0; i < emb.size(); i++) {
                arr[i] = emb.get(i).floatValue();
            }
            return arr;
        }).toList();
    }
}
