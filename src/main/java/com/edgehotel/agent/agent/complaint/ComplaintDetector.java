package com.edgehotel.agent.agent.complaint;

/**
 * 投诉/负面情绪识别器（M1 出口标准：投诉识别 F1 ≥ 0.8）。
 * 用于对客消息进入 Agent 前分级：投诉 → 转人工/升级路径；正常 → 正常答复。
 */
public interface ComplaintDetector {

    ComplaintResult detect(String text);
}
