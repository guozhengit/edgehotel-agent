package com.edgehotel.agent.controller;

import com.edgehotel.agent.agent.approval.ApprovalGate;
import com.edgehotel.agent.agent.approval.ApprovalRecord;
import com.edgehotel.agent.agent.verifier.SilentFailureLedger;
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

    public ApprovalController(ApprovalGate gate, SilentFailureLedger ledger) {
        this.gate = gate;
        this.ledger = ledger;
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
    public Map<String, Object> grant(@PathVariable String id, @RequestBody Map<String, String> body) {
        String operator = body.getOrDefault("operator", "console-operator");
        boolean ok = gate.grant(id, operator);
        return Map.of("ok", ok, "approval_id", id, "status", ok ? "granted" : "failed（不存在或已决）");
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
