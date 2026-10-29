package com.edgehotel.agent.agent;

/**
 * 行业模板描述符（M3 后模板化验证）：把"行业身份"从控制器硬编码里解耦出来。
 * 一个模板 = 一套工具集（Tool Bean 集，条件装配）+ 一份 system prompt（本接口）。
 * Runtime（AgentRunner/ApprovalGate/Verifier/Memory/eval）对模板零感知——复用性验证的核心。
 */
public interface TemplateDescriptor {

    /** 模板标识（hotel / restaurant），与 EH_TEMPLATE 配置对应 */
    String key();

    /** Agent system prompt（含工具使用纪律与如实转述要求） */
    String systemPrompt();
}
