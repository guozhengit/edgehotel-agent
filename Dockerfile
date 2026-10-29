# EdgeHotel-Agent 容器化（M3-3）
# 多阶段构建：Maven 编译 → JRE 运行
# 用法：
#   docker build -t edgehotel-agent:0.1 .
#   docker run --rm -p 8600:8600 \
#     -e EH_LLM_BASE_URL=http://host.docker.internal:1234/v1 \
#     -v edgehotel-data:/app/data \
#     edgehotel-agent:0.1
# 说明：推理层（LM Studio/llama.cpp）保持在宿主机或独立 LLM 容器，应用通过 EH_LLM_BASE_URL 引用。
# 新商家接入 = 一条 docker run（或 compose up），配置全部环境变量化（见 examples/hotel-onboard.md）。

# ---- 构建阶段 ----
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B package -DskipTests

# ---- 运行阶段 ----
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /build/target/*.jar app.jar
# SQLite 数据卷挂载点（approvals/silent_failures/memories/eval_cases 四表）
VOLUME ["/app/data"]
# 环境变量化配置（均带默认值，见 application.yml）
ENV EH_PORT=8600 \
    EH_LLM_BASE_URL=http://host.docker.internal:1234/v1 \
    EH_AGENT_MODEL=qwen3-30b-a3b \
    EH_COMPLAINT_MODEL=qwen/qwen3.5-9b \
    EH_EMB_MODEL=text-embedding-qwen__qwen3-embedding-0.6b \
    EH_DB_PATH=data/edgehotel.db
EXPOSE 8600
ENTRYPOINT ["java", "-jar", "app.jar"]
