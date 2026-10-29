package com.edgehotel.agent.controller;

import com.edgehotel.agent.agent.Tool;
import com.edgehotel.agent.agent.ToolRegistry;
import com.edgehotel.agent.agent.approval.ApprovalGate;
import com.edgehotel.agent.agent.approval.ApprovalRecord;
import com.edgehotel.agent.agent.verifier.SilentFailureLedger;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * M1-2 审批门与控制面板接口（Web 控制台 v0 对接）：
 * GET  /api/approval/pending        待审批队列
 * POST /api/approval/{id}/grant     人工放行 {operator}
 * POST /api/approval/{id}/deny      人工拒绝 {operator, reason}
 * GET  /api/approval/all            全部审批记录（留痕审计）
 * GET  /api/verifier/ledger         静默失败台账
 */
@RestController
@RequestMapping("/api")
public class ApprovalController {

    private final ApprovalGate gate;
    private final SilentFailureLedger ledger;
    private final ToolRegistry registry;
    private final ObjectMapper mapper = new ObjectMapper();

    public ApprovalController(ApprovalGate gate, SilentFailureLedger ledger, ToolRegistry registry) {
        this.gate = gate;
        this.ledger = ledger;
        this.registry = registry;
    }

    @GetMapping("/approval/pending")
    public List<ApprovalRecord> pending() {
        return gate.pending();
    }

    @GetMapping("/approval/all")
    public Map<String, Object> all() {
        return Map.of("recorded", gate.totalRecorded(), "records", gate.all());
    }

    @PostMapping("/approval/{id}/grant")
    public Map<String, Object> grant(@PathVariable String id, @RequestBody Map<String, Object> body) {
        String operator = String.valueOf(body.getOrDefault("operator", "console-operator"));
        boolean ok = gate.grant(id, operator);
        if (!ok) {
            return Map.of("ok", false, "approval_id", id, "status", "failed（不存在或已决）");
        }
        // 放行即执行（e2e 发现的闭环缺口）：此前 grant 仅翻转状态，写工具（投诉/话术）从未真正落地。
        ApprovalRecord rec = gate.find(id);
        Map<String, Object> out = new java.util.LinkedHashMap<>(Map.of(
                "ok", true, "approval_id", id, "status", "granted", "operator", operator));
        if (rec != null) {
            var toolOpt = registry.find(rec.toolName());
            if (toolOpt.isPresent()) {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> args = mapper.readValue(rec.argsSummary(), Map.class);
                    Tool.ToolResult r = toolOpt.get().run(args,
                            new Tool.ToolContext("approval-exec", rec.sessionId() == null ? id : rec.sessionId()));
                    out.put("execution", Map.of(
                            "tool", rec.toolName(),
                            "ok", r.ok(),
                            "output", String.valueOf(r.output() == null ? r.error() : r.output())));
                } catch (Exception e) {
                    out.put("execution", Map.of("tool", rec.toolName(), "ok", false, "error", String.valueOf(e.getMessage())));
                }
            } else {
                out.put("execution", Map.of("tool", rec.toolName(), "ok", false, "error", "工具不存在（可能模板已切换）"));
            }
        }
        return out;
    }

    @PostMapping("/approval/{id}/deny")
    public Map<String, Object> deny(@PathVariable String id, @RequestBody Map<String, String> body) {
        String operator = body.getOrDefault("operator", "console-operator");
        String reason = body.getOrDefault("reason", "人工拒绝");
        boolean ok = gate.deny(id, operator, reason);
        return Map.of("ok", ok, "approval_id", id, "status", ok ? "denied" : "failed（不存在或已决）");
    }

    @GetMapping("/verifier/ledger")
    public Map<String, Object> ledger() {
        return Map.of(
                "total", ledger.totalCount(),
                "silent_failures", ledger.silentFailureCount(),
                "entries", ledger.recent(50));
    }
}
