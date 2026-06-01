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
public class ReviewSummaryMcpExecutor {

    private final JdbcTemplate jdbcTemplate;

    private static final String TOOL_ID = "review_summary";

    @Bean
    public McpServerFeatures.SyncToolSpecification reviewSummaryToolSpecification() {
        return new McpServerFeatures.SyncToolSpecification(
                buildTool(),
                (exchange, request) -> handleCall(request)
        );
    }

    private Tool buildTool() {
        Map<String, Object> properties = new LinkedHashMap<>();

        // 店铺ID参数
        properties.put("shop_id", Map.of(
                "type", "integer",
                "description", "店铺ID，查询该店铺下的所有评论"
        ));
        // 店铺名称参数（与shop_id二选一）
        properties.put("shop_name", Map.of(
                "type", "string",
                "description", "店铺名称关键词，如不知道shop_id可用店铺名称模糊查询"
        ));

        JsonSchema inputSchema = new JsonSchema(
                "object", properties, List.of(), null, null, null);

        return Tool.builder()
                .name(TOOL_ID)
                .description("查询指定店铺的用户评论，返回评论标题和内容，供AI进行好差评总结和情绪分析")
                .inputSchema(inputSchema)
                .build();
    }

    private CallToolResult handleCall(CallToolRequest request) {
        long startMs = System.currentTimeMillis();
        try {
            Map<String, Object> args = request.arguments() != null ? request.arguments() : Map.of();
            Integer shopId = intArg(args, "shop_id");
            String shopName = stringArg(args, "shop_name");

            // shop_id 和 shop_name 都没传，返回提示
            if (shopId == null && (shopName == null || shopName.isBlank())) {
                return successResult("请提供店铺ID或店铺名称以查询评论。");
            }

            // 如果传了店铺名称但没有shop_id，先查出shop_id
            if (shopId == null) {
                List<Map<String, Object>> shops = jdbcTemplate.queryForList(
                        "SELECT id, name FROM tb_shop WHERE name LIKE ? LIMIT 1",
                        "%" + shopName + "%"
                );
                if (shops.isEmpty()) {
                    return successResult("未找到名称包含「" + shopName + "」的店铺。");
                }
                shopId = ((Number) shops.get(0).get("id")).intValue();
                String foundName = (String) shops.get(0).get("name");
                log.info("通过店铺名称找到店铺: name={}, id={}", foundName, shopId);
            }

            // 查询该店铺的评论，取最新20条
            List<Map<String, Object>> reviews = jdbcTemplate.queryForList(
                    "SELECT title, content, liked FROM tb_blog WHERE shop_id = ? ORDER BY create_time DESC LIMIT 20",
                    shopId
            );

            if (reviews.isEmpty()) {
                return successResult("该店铺暂无评论数据。");
            }

            // 查询店铺名称（用于展示）
            String finalShopName = shopName;
            try {
                finalShopName = (String) jdbcTemplate.queryForMap(
                        "SELECT name FROM tb_shop WHERE id = ?", shopId
                ).get("name");
            } catch (Exception ignored) {}

            // 格式化评论供AI分析
            StringBuilder result = new StringBuilder();
            result.append(String.format("【%s】共找到 %d 条评论：\n\n", finalShopName, reviews.size()));

            for (int i = 0; i < reviews.size(); i++) {
                Map<String, Object> review = reviews.get(i);
                String title = (String) review.get("title");
                String content = (String) review.get("content");
                Object liked = review.get("liked");

                result.append(String.format("评论%d：\n", i + 1));
                if (title != null && !title.isBlank()) {
                    result.append(String.format("  标题：%s\n", title));
                }
                if (content != null && !content.isBlank()) {
                    result.append(String.format("  内容：%s\n", content));
                }
                if (liked != null) {
                    result.append(String.format("  点赞数：%s\n", liked));
                }
                result.append("\n");
            }

            result.append("请根据以上评论，总结：\n");
            result.append("1. 好评关键词（3-5个）\n");
            result.append("2. 差评关键词（3-5个，如无差评可不列）\n");
            result.append("3. 用户整体情绪（正面/中性/负面）\n");
            result.append("4. 一句话总结");

            log.info("MCP 工具调用完成, toolId={}, shopId={}, 评论数={}, elapsed={}ms",
                    TOOL_ID, shopId, reviews.size(), System.currentTimeMillis() - startMs);
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