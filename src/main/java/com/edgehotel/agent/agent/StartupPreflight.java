package com.edgehotel.agent.agent;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.util.Locale;

/**
 * 启动前置检查（治理文档 §八承诺项）：复用 ITGG resource-check 阈值，在应用启动时体检宿主机资源。
 *
 * <p>模式（EH_PREFLIGHT）：off=跳过；warn（默认）=超阈值仅告警；enforce=内存红线超限拒绝启动。
 * 检查项（对齐《ITGG资源治理与运行规范-2026-10》红线）：
 * <ul>
 *   <li>物理内存使用率 &gt; 85% → 红线（换页/卡死风险，enforce 下拒绝启动）</li>
 *   <li>可用物理内存 &lt; 3.5GB → 警告（9B 级模型驻留后将进入磁盘瓶颈，2026-10-30 实测两次复现）</li>
 *   <li>llama-server 推理引擎进程 &gt; 2 → 红线（同驻模型上限）</li>
 * </ul>
 */
@Component
public class StartupPreflight implements ApplicationRunner {

    @Value("${app.preflight:${EH_PREFLIGHT:warn}}")
    private String mode;

    @Override
    public void run(ApplicationArguments args) {
        if ("off".equalsIgnoreCase(mode)) {
            System.err.println("[PREFLIGHT] 已关闭（EH_PREFLIGHT=off）");
            return;
        }
        long totalBytes = totalPhysical();
        long freeBytes = freePhysical();
        int usedPct = totalBytes <= 0 ? -1 : (int) Math.round(100.0 * (totalBytes - freeBytes) / totalBytes);
        long availableMb = freeBytes / 1024 / 1024;
        long engines = ProcessHandle.allProcesses()
                .filter(p -> p.info().command()
                        .map(c -> c.contains("llama-server"))
                        .orElse(false))
                .count();

        boolean memRedline = usedPct > 85;
        boolean memTight = availableMb < 3500;
        boolean enginesRedline = engines > 2;

        System.err.printf("[PREFLIGHT] 内存 %d%%（可用 %d MB）| 推理引擎 %d 个 | 模式=%s%n",
                usedPct, availableMb, engines, mode.toLowerCase(Locale.ROOT));
        if (memRedline) {
            System.err.println("[PREFLIGHT] 红线: 内存使用率 >85%（换页/卡死风险）——加载推理模型前先释放宿主内存");
        }
        if (memTight) {
            System.err.println("[PREFLIGHT] 警告: 可用内存 <3.5GB——9B 级模型驻留后推理将进入磁盘瓶颈（实测）");
        }
        if (enginesRedline) {
            System.err.println("[PREFLIGHT] 红线: 推理引擎 >2 个（治理上限），请先 lms unload --all");
        }
        if ("enforce".equalsIgnoreCase(mode) && memRedline) {
            throw new IllegalStateException("[PREFLIGHT] enforce 模式：内存红线超限，拒绝启动（释放宿主内存或改 EH_PREFLIGHT=warn）");
        }
    }

    private static long totalPhysical() {
        com.sun.management.OperatingSystemMXBean os =
                (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        return os.getTotalPhysicalMemorySize();
    }

    private static long freePhysical() {
        com.sun.management.OperatingSystemMXBean os =
                (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        return os.getFreePhysicalMemorySize();
    }
}
