package com.edgehotel.agent.agent.complaint;

/**
 * 投诉识别结果。
 * isComplaint：是否投诉/负面情绪；category：投诉类别（卫生/设施/服务/价格/噪音/其他）；confidence：0~1。
 */
public record ComplaintResult(boolean isComplaint, String category, double confidence, String reason) {
}
