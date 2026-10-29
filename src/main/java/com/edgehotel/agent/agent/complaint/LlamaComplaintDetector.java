package com.edgehotel.agent.agent.complaint;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

/**
 * LLM 实现：Qwen 本地模型 few-shot 分类，输出 JSON {is_complaint, category, confidence, reason}。
 * 若模型不可用/超时 → 保守降级：规则兜底（含"投诉/差/脏/慢/吵/贵/破/不满"等信号词）→ 判投诉，
 * 宁误报不漏报（投诉漏识别危害更大，符合"投诉升级到人工"的产品原则）。
 */
@Component
public class LlamaComplaintDetector implements ComplaintDetector {

    private static final String PROMPT_TEMPLATE = """
            你是酒店客服质检员。判断客人消息是否包含投诉/强烈负面情绪（如不满、指责、要求赔偿、差评意图）。
            只输出 JSON：{"is_complaint": true/false, "category": "卫生|设施|服务|价格|噪音|其他|无", "confidence": 0~1, "reason": "一句话理由"}

            示例：
            客人: 你们房间马桶是堵的，昨晚根本没睡好，必须给个说法！
            输出: {"is_complaint": true, "category": "设施", "confidence": 0.95, "reason": "明确指责设施故障并要求说法"}

            客人: 请问退房时间是几点？可以延后吗？
            输出: {"is_complaint": false, "category": "无", "confidence": 0.98, "reason": "普通询问无负面情绪"}

            客人: 早餐种类有点少，不过总体还行。
            输出: {"is_complaint": false, "category": "无", "confidence": 0.7, "reason": "轻微不满但整体中性，不构成投诉"}

            客人: %s
            输出:
            """;

    private final WebClient client;
    private final String model;
    private final ObjectMapper mapper = new ObjectMapper();

    public LlamaComplaintDetector(@Value("${complaint.model}") String model,
                                  @Value("${complaint.base-url}") String baseUrl) {
        this.model = model;
        this.client = WebClient.builder()
                .baseUrl(baseUrl)
                .exchangeStrategies(ExchangeStrategies.builder()
                        .codecs(c -> c.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                        .build())
                .build();
    }

    @Override
    public ComplaintResult detect(String text) {
        if (text == null || text.isBlank()) {
            return new ComplaintResult(false, "无", 1.0, "空输入");
        }
        try {
            String prompt = PROMPT_TEMPLATE.formatted(text);
            Map<String, Object> body = Map.of(
                    "model", model,
                    "messages", List.of(
                            Map.of("role", "system", "content", "你只输出 JSON，不要输出其他文字。"),
                            Map.of("role", "user", "content", prompt)),
                    "temperature", 0.0);
            Map<String, Object> resp = client.post().uri("/chat/completions")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();
            String content = extractContent(resp);
            return parseJson(content);
        } catch (Exception e) {
            // 降级：规则兜底
            return fallback(text);
        }
    }

    private String extractContent(Map<String, Object> resp) {
        try {
            List<Map<String, Object>> choices = (List<Map<String, Object>>) resp.get("choices");
            Map<String, Object> msg = (Map<String, Object>) choices.get(0).get("message");
            Object c = msg.get("content");
            return c == null ? "" : c.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private ComplaintResult parseJson(String content) throws com.fasterxml.jackson.core.JsonProcessingException {
        // 容错截取 {...} 部分
        int s = content.indexOf('{');
        int e = content.lastIndexOf('}');
        if (s >= 0 && e > s) {
            JsonNode node = mapper.readTree(content.substring(s, e + 1));
            boolean isComplaint = node.path("is_complaint").asBoolean(false);
            String category = node.path("category").asText("其他");
            double conf = node.path("confidence").asDouble(0.5);
            String reason = node.path("reason").asText("");
            if (conf < 0.4) {
                // 模型低置信度 → 保守判投诉（产品原则：投诉漏识别危害更大）
                return new ComplaintResult(true, category, conf, reason + "（低置信度保守升级）");
            }
            return new ComplaintResult(isComplaint, category, conf, reason);
        }
        return fallback(content);
    }

    private ComplaintResult fallback(String text) {
        boolean hit = List.of("投诉", "差评", "脏", "臭", "堵", "坏", "破", "慢", "吵", "贵",
                        "难吃", "态度差", "赔偿", "说法", "换房", "退钱", "差", "不满", "忍不了", "恶心")
                .stream().anyMatch(text::contains);
        return hit
                ? new ComplaintResult(true, "其他", 0.5, "规则兜底命中投诉信号词")
                : new ComplaintResult(false, "无", 0.5, "规则兜底未命中");
    }
}
