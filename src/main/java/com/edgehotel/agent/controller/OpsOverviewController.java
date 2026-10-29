package com.edgehotel.agent.controller;

import com.edgehotel.agent.agent.TemplateDescriptor;
import com.edgehotel.agent.agent.channel.ChannelStats;
import com.edgehotel.agent.store.ChannelMessageStore;
import com.edgehotel.agent.store.TicketStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 运行视图（M5-1 观测聚合）：GET /api/ops/overview
 * 一屏聚合：应用身份/资源/模型加载态/渠道计数/台账与工单存量——运营眼底（不替代逐项明细端点）。
 */
@RestController
@RequestMapping("/api/ops")
public class OpsOverviewController {

    private final TemplateDescriptor template;
    private final ChannelStats channelStats;
    private final ChannelMessageStore channelMessages;
    private final TicketStore tickets;
    private final String agentModel;
    private final String complaintModel;
    private final String embeddingModel;
    private final String llmBaseUrl;
    private final long startTime = System.currentTimeMillis();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3)).build();
    private final ObjectMapper mapper = new ObjectMapper();

    public OpsOverviewController(TemplateDescriptor template, ChannelStats channelStats,
                                 ChannelMessageStore channelMessages, TicketStore tickets,
                                 @Value("${agent.model}") String agentModel,
                                 @Value("${complaint.model}") String complaintModel,
                                 @Value("${rag.embedding.model}") String embeddingModel,
                                 @Value("${agent.base-url:http://localhost:1234/v1}") String llmBaseUrl) {
        this.template = template;
        this.channelStats = channelStats;
        this.channelMessages = channelMessages;
        this.tickets = tickets;
        this.agentModel = agentModel;
        this.complaintModel = complaintModel;
        this.embeddingModel = embeddingModel;
        this.llmBaseUrl = llmBaseUrl;
    }

    @GetMapping("/overview")
    public Map<String, Object> overview() {
        Map<String, Object> out = new LinkedHashMap<>();

        // 应用身份
        out.put("template", template.key());
        out.put("uptimeMinutes", (System.currentTimeMillis() - startTime) / 60000);

        // 宿主资源（同 preflight 口径）
        com.sun.management.OperatingSystemMXBean os =
                (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        long total = os.getTotalPhysicalMemorySize();
        long free = os.getFreePhysicalMemorySize();
        out.put("memory", Map.of(
                "usedPct", total <= 0 ? -1 : Math.round(100.0 * (total - free) / total),
                "availableMb", free / 1024 / 1024));

        // 模型加载态（LM Studio /api/v0/models，不触发 JIT）
        out.put("models", modelStates());

        // 渠道
        out.put("channelCounters", channelStats.snapshot());
        out.put("channelMessagesTotal", channelMessages.list(null, null, null).size());

        // 台账与工单存量
        out.put("tickets", Map.of(
                "complaintOpen", tickets.list("complaint", "OPEN").size(),
                "marketingDraft", tickets.list("marketing", "DRAFT").size()));
        return out;
    }

    /** 配置模型的加载状态（与 StartupPreflight 同源口径）。 */
    private List<Map<String, Object>> modelStates() {
        List<Map<String, Object>> out = new ArrayList<>();
        String url = llmBaseUrl.replaceAll("/v1/?$", "") + "/api/v0/models";
        Map<String, String> stateById = new LinkedHashMap<>();
        String error = null;
        try {
            HttpResponse<String> resp = http.send(HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                JsonNode data = mapper.readTree(resp.body()).path("data");
                data.forEach(m -> stateById.put(m.path("id").asText(""), m.path("state").asText("")));
            } else {
                error = "HTTP " + resp.statusCode();
            }
        } catch (Exception e) {
            error = e.getMessage();
        }
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("agent", agentModel);
        expected.put("complaint", complaintModel);
        expected.put("embedding", embeddingModel);
        final String errorFinal = error;
        expected.forEach((role, id) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("role", role);
            m.put("id", id);
            m.put("state", stateById.getOrDefault(id, errorFinal != null ? "unknown(" + errorFinal + ")" : "not-registered"));
            out.add(m);
        });
        return out;
    }
}
