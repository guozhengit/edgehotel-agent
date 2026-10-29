package com.edgehotel.agent.agent.verifier;

/**
 * 校验结论（落地方案 §4.2 Verifier）：
 * NORMAL 正常 / EXPLICIT_FAILURE 显式失败 / SILENT_FAILURE 静默失败（副作用已发生但模型未感知）。
 */
public record VerifyVerdict(Verdict verdict, String category, String reason) {

    public enum Verdict {
        NORMAL,
        EXPLICIT_FAILURE,
        SILENT_FAILURE
    }

    public static VerifyVerdict normal() {
        return new VerifyVerdict(Verdict.NORMAL, "ok", "工具返回正常");
    }

    public static VerifyVerdict explicitFailure(String category, String reason) {
        return new VerifyVerdict(Verdict.EXPLICIT_FAILURE, category, reason);
    }

    public static VerifyVerdict silentFailure(String category, String reason) {
        return new VerifyVerdict(Verdict.SILENT_FAILURE, category, reason);
    }

    public boolean isFailure() {
        return verdict != Verdict.NORMAL;
    }
}
