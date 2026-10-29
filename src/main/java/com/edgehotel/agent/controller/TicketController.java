package com.edgehotel.agent.controller;

import com.edgehotel.agent.store.TicketStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 工单/话术草稿查询（M2 遗留真实化）：GET /api/tickets?kind=complaint|marketing&status=OPEN|DRAFT
 */
@RestController
@RequestMapping("/api/tickets")
public class TicketController {

    private final TicketStore store;

    public TicketController(TicketStore store) {
        this.store = store;
    }

    @GetMapping
    public Map<String, Object> list(@RequestParam(required = false) String kind,
                                    @RequestParam(required = false) String status) {
        List<Map<String, Object>> tickets = store.list(kind, status);
        return Map.of("total", tickets.size(), "tickets", tickets);
    }
}
