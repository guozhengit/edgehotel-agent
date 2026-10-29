package com.edgehotel.agent.agent.verifier;

import com.edgehotel.agent.agent.Tool;

/**
 * 返回校验 Verifier（落地方案 §4.2 三级防线第 1 级）：
 * 判定工具返回是否"静默失败"——查询执行了但返回空且无异常标记，模型却可能照常作答。
 */
public interface Verifier {

    VerifyVerdict verify(String toolName, Tool.ToolResult result, Tool.ToolContext ctx);
}
