package com.edgehotel.agent.config;

import io.agentscope.core.message.Msg;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.extensions.model.openai.dto.OpenAIMessage;
import io.agentscope.extensions.model.openai.formatter.OpenAIChatFormatter;
import java.util.List;

/**
 * Qwen3 思考开关 Formatter。
 *
 * <p>Qwen3 系列（含 Qwen3-30B-A3B）默认思考模式，LM Studio 端点忽略 thinking 参数，
 * 只能在 prompt 末尾追加 {@code /no_think} 关闭思考（实测问答 84.4s → 2.9s）。
 * 本 Formatter 只在最后一轮 user 消息末尾追加，不污染会话历史。
 */
public class QwenNoThinkFormatter extends OpenAIChatFormatter {

    /** Qwen3 关闭思考指令（原样追加到最终 user 消息末尾） */
    private static final String NO_THINK = " /no_think";

    @Override
    protected List<OpenAIMessage> doFormat(List<Msg> msgs) {
        return appendNoThink(super.doFormat(msgs));
    }

    @Override
    protected List<OpenAIMessage> doFormat(List<Msg> msgs, GenerateOptions options) {
        return appendNoThink(super.doFormat(msgs, options));
    }

    private List<OpenAIMessage> appendNoThink(List<OpenAIMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            OpenAIMessage msg = messages.get(i);
            if ("user".equals(msg.getRole()) && msg.getContent() instanceof String text) {
                msg.setContent(text + NO_THINK);
                break;
            }
        }
        return messages;
    }
}
