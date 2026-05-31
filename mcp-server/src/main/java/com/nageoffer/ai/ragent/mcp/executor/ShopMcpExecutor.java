package com.nageoffer.ai.ragent.mcp.executor;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class ShopMcpExecutor {

    private final JdbcTemplate jdbcTemplate;

    private static final String TOOL_ID = "search_shop";

    @Bean
    public McpServerFeatures.SyncToolSpecification shopToolSpecification() {
        return new McpServerFeatures.SyncToolSpecification(
                buildTool(),
                (exchange, request) -> handleCall(request)
        );
    }

    private Tool buildTool() {
        Map<String, Object> properties = new LinkedHashMap<>();

        properties.put("type_name", Map.of(
                "type", "string",
                "description", "店铺类型，如美食、KTV等，不填则查询所有类型"
        ));
        properties.put("max_price", Map.of(
                "type", "integer",
                "description", "人均最高价格，单位元，如100表示人均100元以内"
        ));
        properties.put("area", Map.of(
                "type", "string",
                "description", "区域名称，如大关、运河上街、拱宸桥等"
        ));
        properties.put("keyword", Map.of(
                "type", "string",
                "description", "店铺名称关键词，如火锅、寿司、KTV等"
        ));

        JsonSchema inputSchema = new JsonSchema(
                "object", properties, List.of(), null, null, null);

        return Tool.builder()
                .name(TOOL_ID)
                .description("查询黑马点评店铺信息，支持按类型、价格、区域、关键词筛选，返回店铺名称、地址、人均消费等信息")
                .inputSchema(inputSchema)
                .build();
    }

    private CallToolResult handleCall(CallToolRequest request) {
        long startMs = System.currentTimeMillis();
        try {
            Map<String, Object> args = request.arguments() != null ? request.arguments() : Map.of();
            String typeName = stringArg(args, "type_name");
            Integer maxPrice = intArg(args, "max_price");
            String area = stringArg(args, "area");
            String keyword = stringArg(args, "keyword");

            // 动态拼接 SQL
            StringBuilder sql = new StringBuilder("""
                    SELECT s.name, t.name as type_name, s.area, s.address, s.avg_price, s.score
                    FROM tb_shop s
                    JOIN tb_shop_type t ON s.type_id = t.id
                    WHERE 1=1
                    """);
            List<Object> params = new java.util.ArrayList<>();

            if (typeName != null && !typeName.isBlank()) {
                sql.append(" AND t.name LIKE ?");
                params.add("%" + typeName + "%");
            }
            if (maxPrice != null) {
                sql.append(" AND s.avg_price <= ?");
                params.add(maxPrice);
            }
            if (area != null && !area.isBlank()) {
                sql.append(" AND s.area LIKE ?");
                params.add("%" + area + "%");
            }
            if (keyword != null && !keyword.isBlank()) {
                sql.append(" AND s.name LIKE ?");
                params.add("%" + keyword + "%");
            }
            sql.append(" ORDER BY s.avg_price ASC LIMIT 10");

            List<Map<String, Object>> shops = jdbcTemplate.queryForList(sql.toString(), params.toArray());

            if (shops.isEmpty()) {
                return successResult("未找到符合条件的店铺，请尝试放宽筛选条件。");
            }

            // 格式化结果
            StringBuilder result = new StringBuilder("为您找到以下店铺：\n\n");
            for (int i = 0; i < shops.size(); i++) {
                Map<String, Object> shop = shops.get(i);
                result.append(String.format("%d. 【%s】\n", i + 1, shop.get("name")));
                result.append(String.format("   类型：%s\n", shop.get("type_name")));
                result.append(String.format("   区域：%s\n", shop.get("area")));
                result.append(String.format("   地址：%s\n", shop.get("address")));
                result.append(String.format("   人均：%s元\n", shop.get("avg_price")));
                result.append("\n");
            }

            log.info("MCP 工具调用完成, toolId={}, 结果数={}, elapsed={}ms",
                    TOOL_ID, shops.size(), System.currentTimeMillis() - startMs);
            return successResult(result.toString().trim());

        } catch (Exception e) {
            log.error("MCP 工具调用失败, toolId={}", TOOL_ID, e);
            return errorResult("查询失败: " + e.getMessage());
        }
    }

    private static String stringArg(Map<String, Object> args, String key) {
        Object val = args.get(key);
        return val != null ? val.toString() : null;
    }

    private static Integer intArg(Map<String, Object> args, String key) {
        Object val = args.get(key);
        if (val instanceof Number n) return n.intValue();
        return null;
    }

    private static CallToolResult successResult(String text) {
        return CallToolResult.builder()
                .content(List.of(new TextContent(text)))
                .isError(false)
                .build();
    }

    private static CallToolResult errorResult(String message) {
        return CallToolResult.builder()
                .content(List.of(new TextContent(message)))
                .isError(true)
                .build();
    }
}