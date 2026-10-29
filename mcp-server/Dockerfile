# EdgeHotel MCP Server 容器化（M6-3）
# MCP SDK 全部来自 Maven Central（无需 m2-vendor），可纯容器内构建。
# 用法：
#   docker build -t edgehotel-mcp-server:0.1 .
#   docker run -d -p 8601:8601 -e EH_MCP_AUTH_TOKEN=change-me edgehotel-mcp-server:0.1
# 说明：stdio 传输供本机客户端拉起进程使用，无需容器；容器化用于 HTTP 远程接入。
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B package -DskipTests

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /build/target/edgehotel-mcp-server.jar app.jar
# 数据文件卷（--data 外置 JSON 数据源可挂载）
VOLUME ["/app/data"]
EXPOSE 8601
# 默认 demo 数据源 + HTTP 8601；生产加 EH_MCP_AUTH_TOKEN 启用 Bearer 门禁
ENV EH_MCP_AUTH_TOKEN=
ENTRYPOINT ["sh", "-c", "java -Dfile.encoding=UTF-8 -jar app.jar --http 8601 --pms ${EH_MCP_PMS:-demo} --pms-url ${EH_MCP_PMS_URL:-http://localhost} --pms-tenant ${EH_MCP_PMS_TENANT:-1} ${EH_MCP_AUTH_TOKEN:+--auth-token $EH_MCP_AUTH_TOKEN} ${EH_MCP_DATA:+--data /app/data/hotel-data.json}"]
