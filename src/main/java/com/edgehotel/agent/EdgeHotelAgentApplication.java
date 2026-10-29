package com.edgehotel.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * EdgeHotel-Agent 启动类。
 *
 * <p>M0-2 最小工程：Spring Boot 3.x 外壳 + AgentScope Java 2.0（HarnessAgent）+
 * LM Studio 本地推理（Qwen3-30B-A3B）。
 */
@EnableScheduling
@SpringBootApplication
public class EdgeHotelAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(EdgeHotelAgentApplication.class, args);
    }
}
