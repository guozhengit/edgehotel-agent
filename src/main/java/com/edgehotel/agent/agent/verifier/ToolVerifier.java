package com.edgehotel.agent.agent.verifier;

import com.edgehotel.agent.agent.Tool;
import org.springframework.stereotype.Component;

/**
 * 工具返回校验实现：
 * - ERROR（ok=false）→ 显式失败（工具自报错误，模型看到 error 文本）
 * - EMPTY（ok=true 但 status=EMPTY，即"查询了但返回空且无异常标记"）→ 静默失败（可疑，回填时提示人工确认）
 * - STANDARD 但 output 空白 → 静默失败（防御性兜底）
 * - STANDARD 正常输出 → 正常
 */
@Component
public class ToolVerifier implements Verifier {

    @Override
    public VerifyVerdict verify(String toolName, Tool.ToolResult result, Tool.ToolContext ctx) {
        if (!result.ok()) {
            return VerifyVerdict.explicitFailure("tool_error", "工具 " + toolName + " 显式报错: " + result.error());
        }
        if ("EMPTY".equals(result.status())) {
            return VerifyVerdict.silentFailure("empty_result",
                    "工具 " + toolName + " 返回空且无异常标记（静默失败风险），须人工核对后再答复客人");
        }
        if (result.output() == null || result.output().isBlank()) {
            return VerifyVerdict.silentFailure("blank_output",
                    "工具 " + toolName + " 返回空白输出，判定静默失败，须人工核对");
        }
        return VerifyVerdict.normal();
    }
}
