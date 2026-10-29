package com.edgehotel.agent.agent;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 启动前置检查（治理文档 §八承诺项）：复用 ITGG resource-check 阈值，在应用启动时体检宿主机资源。
 *
 * <p>模式（EH_PREFLIGHT）：off=跳过；warn（默认）=超阈值仅告警；enforce=内存红线超限拒绝启动。
 * 检查项（对齐《ITGG资源治理与运行规范-2026-10》红线）：
 * <ul>
 *   <li>物理内存使用率 &gt; 85% → 红线（换页/卡死风险，enforce 下拒绝启动）</li>
 *   <li>可用物理内存 &lt; 3.5GB → 警告（9B 级模型驻留后将进入磁盘瓶颈，2026-10-30 实测两次复现）</li>
 *   <li>llama-server 推理引擎进程 &gt; 2 → 红线（同驻模型上限）</li>
 * </ul>
 */
@Component
public class StartupPreflight implements ApplicationRunner {

    @Value("${app.preflight:${EH_PREFLIGHT:warn}}")
    private String mode;

    @Value("${agent.model}")
    private String agentModel;

    @Value("${complaint.model}")
    private String complaintModel;

    @Value("${rag.embedding.model}")
    private String embeddingModel;

    @Value("${agent.base-url:http://localhost:1234/v1}")
    private String llmBaseUrl;

    @Override
    public void run(ApplicationArguments args) {
        if ("off".equalsIgnoreCase(mode)) {
            System.err.println("[PREFLIGHT] 已关闭（EH_PREFLIGHT=off）");
            return;
        }
        long totalBytes = totalPhysical();
        long freeBytes = freePhysical();
        int usedPct = totalBytes <= 0 ? -1 : (int) Math.round(100.0 * (totalBytes - freeBytes) / totalBytes);
        long availableMb = freeBytes / 1024 / 1024;
        long engines = ProcessHandle.allProcesses()
                .filter(p -> p.info().command()
                        .map(c -> c.contains("llama-server"))
                        .orElse(false))
                .count();

        boolean memRedline = usedPct > 85;
        boolean memTight = availableMb < 3500;
        boolean enginesRedline = engines > 2;

        System.err.printf("[PREFLIGHT] 内存 %d%%（可用 %d MB）| 推理引擎 %d 个 | 模式=%s%n",
                usedPct, availableMb, engines, mode.toLowerCase(Locale.ROOT));
        if (memRedline) {
            System.err.println("[PREFLIGHT] 红线: 内存使用率 >85%（换页/卡死风险）——加载推理模型前先释放宿主内存");
        }
        if (memTight) {
            System.err.println("[PREFLIGHT] 警告: 可用内存 <3.5GB——9B 级模型驻留后推理将进入磁盘瓶颈（实测）");
        }
        if (enginesRedline) {
            System.err.println("[PREFLIGHT] 红线: 推理引擎 >2 个（治理上限），请先 lms unload --all");
        }
        // 配置-现实一致性检查（复盘 F2/P0）：三个配置模型必须在 LM Studio 已加载——
        // 缺失时首条推理会触发 JIT 加载（30B=17.35GB，内存基线紧张时即事故）。enforce 下拒绝启动。
        List<String> modelIssues = checkConfiguredModelsLoaded();
        if ("enforce".equalsIgnoreCase(mode) && (memRedline || !modelIssues.isEmpty())) {
            throw new IllegalStateException("[PREFLIGHT] enforce 模式拒绝启动："
                    + (memRedline ? "内存红线超限；" : "")
                    + (modelIssues.isEmpty() ? "" : "模型一致性: " + String.join("；", modelIssues))
                    + "（释放资源/加载模型，或改 EH_PREFLIGHT=warn）");
        }
    }

    /**
     * 校验 agent/complaint/embedding 三个配置模型在 LM Studio 的加载状态（/api/v0/models 的 state 字段，
     * 不会触发 JIT）。返回问题描述列表（空=全部就绪）；LM Studio 不可达时返回单条提示（不阻断）。
     */
    private List<String> checkConfiguredModelsLoaded() {
        String modelsUrl = llmBaseUrl.replaceAll("/v1/?$", "") + "/api/v0/models";
        Map<String, String> stateById = new java.util.LinkedHashMap<>();
        try {
            java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(modelsUrl))
                    .timeout(java.time.Duration.ofSeconds(5)).GET().build();
            java.net.http.HttpResponse<String> resp = java.net.http.HttpClient.newHttpClient()
                    .send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return List.of("LM Studio /api/v0/models HTTP " + resp.statusCode() + "，无法校验配置模型");
            }
            com.fasterxml.jackson.databind.JsonNode data = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(resp.body()).path("data");
            data.forEach(m -> stateById.put(m.path("id").asText(""), m.path("state").asText("")));
        } catch (Exception e) {
            return List.of("LM Studio 不可达（" + e.getMessage() + "），无法校验配置模型——推理将失败");
        }
        List<String> issues = new java.util.ArrayList<>();
        Map<String, String> expected = new java.util.LinkedHashMap<>();
        expected.put("agent(编排)", agentModel);
        expected.put("complaint(快路径)", complaintModel);
        expected.put("embedding(RAG/记忆)", embeddingModel);
        expected.forEach((role, id) -> {
            String state = stateById.get(id);
            if (state == null) {
                issues.add(role + " 模型 " + id + " 未下载/未注册");
            } else if (!"loaded".equals(state)) {
                issues.add(role + " 模型 " + id + " 未加载（state=" + state + "，首条推理将 JIT 拉起）");
            }
        });
        if (issues.isEmpty()) {
            System.err.println("[PREFLIGHT] 模型一致性: " + expected.size() + " 个配置模型全部已加载 ✓");
        } else {
            issues.forEach(i -> System.err.println("[PREFLIGHT] 模型一致性警告: " + i));
        }
        return issues;
    }

    private static long totalPhysical() {
        com.sun.management.OperatingSystemMXBean os =
                (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        return os.getTotalPhysicalMemorySize();
    }

    private static long freePhysical() {
        com.sun.management.OperatingSystemMXBean os =
                (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        return os.getFreePhysicalMemorySize();
    }
}
