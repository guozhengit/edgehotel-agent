package com.edgehotel.agent.config;

import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelRegistry;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.spring.boot.openai.OpenAIChatModelBuilderCustomizer;
import java.nio.file.Paths;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * HarnessAgent 装配：单例 Bean，多用户/多会话并发安全。
 *
 * <p>模型由 agentscope-openai-spring-boot-starter 自动装配（OpenAIChatModel），
 * 这里注册到 {@link ModelRegistry} 后以字符串 id 传给 HarnessAgent。
 */
@Configuration
public class HarnessAgentConfig {

    /** ModelRegistry 注册名（供 HarnessAgent.model(...) 解析） */
    public static final String LM_STUDIO_MODEL = "edgehotel:lmstudio";

    /** 工作区根（AGENTS.md / MEMORY.md / sessions 落在这里）。相对路径：宿主机以工程根为 cwd，容器内为 /app。 */
    private static final String WORKSPACE_ROOT = "application.yml: app.workspace（默认 .agentscope/workspace）";

    /**
     * 通过官方 customizer 钩子给 OpenAIChatModel 挂载 /no_think formatter
     * （Qwen3 关思考，实测 84.4s → 2.9s）。
     */
    @Bean
    public OpenAIChatModelBuilderCustomizer noThinkCustomizer() {
        return builder -> builder.formatter(new QwenNoThinkFormatter());
    }

    @Bean
    public HarnessAgent edgeHotelAgent(OpenAIChatModel openAIChatModel,
            @Value("${app.workspace:.agentscope/workspace}") String workspaceRoot) {
        // 注册命名模型（重复 register 同名会覆盖，单例 Bean 只执行一次）
        ModelRegistry.register(LM_STUDIO_MODEL, openAIChatModel);

        return HarnessAgent.builder()
                .name("edgehotel-agent")
                .sysPrompt("""
                        你是 EdgeHotel 酒店私域运营 AI 助手。
                        职责：解答住客关于酒店设施、入住/退房、周边服务的问题；
                        帮助商家生成对客话术；语气专业、简洁、中文优先。
                        不要编造酒店不存在的信息；不确定时请用户咨询前台。
                        """)
                .model(LM_STUDIO_MODEL)
                .workspace(Paths.get(workspaceRoot))
                .compaction(CompactionConfig.builder()
                        .triggerMessages(30)
                        .keepMessages(10)
                        .build())
                .build();
    }
}
