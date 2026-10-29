package com.edgehotel.agent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * 运行时守卫（复盘 F1/F2 配套：preflight 只在启动时跑一次，3 引擎同驻 40 分钟无人发现的缺口）：
 * 每 5 分钟复检【内存红线 / 推理引擎数 / 配置模型加载态】，漂移只告警不干预（Drift 可观测，处置留给运维/TTL）。
 *
 * <p>开关：app.runtime-guard（EH_RUNTIME_GUARD，默认 on）。间隔 300s 固定。
 */
@Component
public class RuntimeGuard {

    private static final Logger log = LoggerFactory.getLogger(RuntimeGuard.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final boolean enabled;
    private final String agentModel;
    private final String complaintModel;
    private final String embeddingModel;
    private final String llmBaseUrl;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3)).build();

    public RuntimeGuard(@Value("${app.runtime-guard:on}") String mode,
                        @Value("${agent.model}") String agentModel,
                        @Value("${complaint.model}") String complaintModel,
                        @Value("${rag.embedding.model}") String embeddingModel,
                        @Value("${agent.base-url:http://localhost:1234/v1}") String llmBaseUrl) {
        this.enabled = !"off".equalsIgnoreCase(mode);
        this.agentModel = agentModel;
        this.complaintModel = complaintModel;
        this.embeddingModel = embeddingModel;
        this.llmBaseUrl = llmBaseUrl;
    }

    @Scheduled(fixedDelay = 300_000, initialDelay = 120_000)
    public void guard() {
        if (!enabled) {
            return;
        }
        // 内存
        com.sun.management.OperatingSystemMXBean os =
                (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        long total = os.getTotalPhysicalMemorySize();
        long free = os.getFreePhysicalMemorySize();
        int usedPct = total <= 0 ? -1 : (int) Math.round(100.0 * (total - free) / total);
        long availableMb = free / 1024 / 1024;
        if (usedPct > 85) {
            log.warn("[RUNTIME-GUARD] 内存红线: {}%（>85%，换页/卡死风险）", usedPct);
        }
        // 推理引擎数
        long engines = ProcessHandle.allProcesses()
                .filter(p -> p.info().command().map(c -> c.contains("llama-server")).orElse(false))
                .count();
        if (engines > 2) {
            log.warn("[RUNTIME-GUARD] 推理引擎 {} 个（>2 红线）——建议 lms unload --all", engines);
        }
        // 配置模型加载态（漂移：TTL 卸载/JIT 拉起都会体现在这里）
        String states = checkModelStates();
        if (!states.isEmpty()) {
            log.warn("[RUNTIME-GUARD] 模型漂移: {}", states);
        }
    }

    /** 返回模型漂移描述（空=全部就绪或 LM Studio 不可达时不告警）。 */
    private String checkModelStates() {
        try {
            String url = llmBaseUrl.replaceAll("/v1/?$", "") + "/api/v0/models";
            HttpResponse<String> resp = http.send(HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return "";
            }
            JsonNode data = MAPPER.readTree(resp.body()).path("data");
            Map<String, String> stateById = new java.util.LinkedHashMap<>();
            data.forEach(m -> stateById.put(m.path("id").asText(""), m.path("state").asText("")));
            StringBuilder sb = new StringBuilder();
            Map.of("agent", agentModel, "complaint", complaintModel, "embedding", embeddingModel)
                    .forEach((role, id) -> {
                        String state = stateById.get(id);
                        if (state == null) {
                            sb.append(role).append("=").append(id).append("(未注册) ");
                        } else if (!"loaded".equals(state)) {
                            sb.append(role).append("=").append(id).append("(已卸载) ");
                        }
                    });
            return sb.toString().strip();
        } catch (Exception e) {
            return "";
        }
    }
}
