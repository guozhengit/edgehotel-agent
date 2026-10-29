package com.edgehotel.agent.controller;

import com.edgehotel.agent.store.ChannelMessageStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 渠道消息审计查询（M4-2）：GET /api/channel/messages?channel=&peer=&direction=inbound|outbound
 */
@RestController
@RequestMapping("/api/channel/messages")
public class ChannelMessageController {

    private final ChannelMessageStore store;

    public ChannelMessageController(ChannelMessageStore store) {
        this.store = store;
    }

    @GetMapping
    public Map<String, Object> list(@RequestParam(required = false) String channel,
                                    @RequestParam(required = false) String peer,
                                    @RequestParam(required = false) String direction) {
        List<Map<String, Object>> items = store.list(channel, peer, direction);
        return Map.of("total", items.size(), "messages", items);
    }
}
