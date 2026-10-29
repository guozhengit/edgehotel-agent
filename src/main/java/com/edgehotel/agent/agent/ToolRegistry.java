package com.edgehotel.agent.agent;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 工具注册表：按名称注册/查找工具（M1-1 内存版；M2 换 MCP 桥接）。
 */
@Component
public class ToolRegistry {

    private final Map<String, Tool> tools = new LinkedHashMap<>();

    /** 自动收集 Spring 容器中的 Tool bean 并注册。 */
    public ToolRegistry(List<Tool> toolBeans) {
        for (Tool t : toolBeans) {
            register(t);
        }
    }

    public void register(Tool tool) {
        tools.put(tool.name(), tool);
    }

    public Optional<Tool> find(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    public List<Tool> all() {
        return List.copyOf(tools.values());
    }

    /** 转为 OpenAI function calling 的 tools 参数。 */
    public List<Map<String, Object>> toOpenAITools() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Tool t : tools.values()) {
            Map<String, Object> fn = new LinkedHashMap<>();
            fn.put("type", "function");
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("name", t.name());
            f.put("description", t.description());
            f.put("parameters", t.jsonSchema());
            fn.put("function", f);
            out.add(fn);
        }
        return out;
    }
}
