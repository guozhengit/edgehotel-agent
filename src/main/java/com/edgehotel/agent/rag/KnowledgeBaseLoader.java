package com.edgehotel.agent.rag;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 加载示例酒店知识库（M0-3 产物 hotel-knowledge-base.md），按 {@code ### KB-xx} 切分为检索文档段。
 *
 * <p>切分规则：每个 {@code ### KB-xx 标题} 段落为一个文档；标题行与正文合并为检索文本。
 */
@Component
public class KnowledgeBaseLoader {

    public record KbDoc(String id, String title, String text) {
    }

    /** 匹配段头行：### KB-01 健身与泳池 */
    private static final Pattern HEADER = Pattern.compile(
            "^###\\s+(KB-\\d+)\\s*(.*)$",
            Pattern.MULTILINE);

    static List<KbDoc> parse(String markdown) {
        List<KbDoc> out = new ArrayList<>();
        Matcher m = HEADER.matcher(markdown);
        int prevBodyEnd = -1;
        String prevId = null, prevTitle = null;
        while (m.find()) {
            if (prevBodyEnd >= 0) {
                // 上一段正文 = 上一段头结束 → 当前段头开始
                String body = markdown.substring(prevBodyEnd, m.start()).trim();
                out.add(toDoc(prevId, prevTitle, body));
            }
            prevBodyEnd = m.end();
            prevId = m.group(1);
            prevTitle = m.group(2).trim();
        }
        if (prevBodyEnd >= 0) {
            String body = markdown.substring(prevBodyEnd).trim();
            out.add(toDoc(prevId, prevTitle, body));
        }
        return out;
    }

    private static KbDoc toDoc(String id, String title, String body) {
        String text = body.lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .map(line -> line.replaceAll("^[-*]\\s*", ""))
                .reduce((a, b) -> a + " " + b)
                .orElse("");
        return new KbDoc(id, title.isEmpty() ? id : title, text);
    }

    private static final String KB_PATH = "D:/AI/edgehotel-agent/data/hotel-knowledge-base.md";

    private volatile List<KbDoc> docs;
    private volatile boolean failed;

    /** 懒加载：启动时 LM Studio 可能尚未就绪，首次检索时再初始化，失败允许重试。 */
    public List<KbDoc> load() throws IOException {
        List<KbDoc> local = docs;
        if (local != null) {
            return local;
        }
        synchronized (this) {
            if (docs == null && !failed) {
                try {
                    docs = parse(Files.readString(Path.of(KB_PATH), StandardCharsets.UTF_8));
                } catch (IOException e) {
                    failed = true;
                    throw e;
                }
            }
            if (docs == null) {
                throw new IOException("知识库加载失败（上次尝试失败，未重试）");
            }
            return docs;
        }
    }

    /** 供检索器使用：加载失败时转运行时异常（一次失败后允许下次重试）。 */
    public List<KbDoc> loadSafe() {
        try {
            return load();
        } catch (IOException e) {
            throw new IllegalStateException("知识库加载失败: " + KB_PATH, e);
        }
    }
}
