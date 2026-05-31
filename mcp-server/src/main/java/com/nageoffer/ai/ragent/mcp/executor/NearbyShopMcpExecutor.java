package com.nageoffer.ai.ragent.mcp.executor;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema.*;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.data.geo.*;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class NearbyShopMcpExecutor {

    private final JdbcTemplate jdbcTemplate;
    private final StringRedisTemplate stringRedisTemplate;

    private static final String TOOL_ID = "nearby_shops";
    private static final String GEO_KEY = "shop:geo";

    /**
     * 启动时把所有店铺经纬度写入 Redis GEO
     */
    @PostConstruct
    public void initShopGeo() {
        try {
            // 查询所有店铺的经纬度
            List<Map<String, Object>> shops = jdbcTemplate.queryForList(
                    "SELECT id, name, x, y FROM tb_shop WHERE x IS NOT NULL AND y IS NOT NULL"
            );
            for (Map<String, Object> shop : shops) {
                Long id = ((Number) shop.get("id")).longValue();
                Double x = ((Number) shop.get("x")).doubleValue(); // 经度
                Double y = ((Number) shop.get("y")).doubleValue(); // 纬度
                String name = shop.get("name").toString();
                // 存入 Redis GEO，member 用 id 存
                stringRedisTemplate.opsForGeo().add(
                        GEO_KEY,
                        new Point(x, y),
                        String.valueOf(id)
                );
            }
            log.info("店铺 GEO 数据初始化完成，共 {} 家店铺", shops.size());
        } catch (Exception e) {
            log.error("店铺 GEO 数据初始化失败", e);
        }
    }

    @Bean
    public McpServerFeatures.SyncToolSpecification nearbyShopToolSpecification() {
        return new McpServerFeatures.SyncToolSpecification(
                buildTool(),
                (exchange, request) -> handleCall(request)
        );
    }

    private Tool buildTool() {
        Map<String, Object> properties = new LinkedHashMap<>();

        properties.put("longitude", Map.of(
                "type", "number",
                "description", "用户当前位置的经度，杭州大约是 120.15"
        ));
        properties.put("latitude", Map.of(
                "type", "number",
                "description", "用户当前位置的纬度，杭州大约是 30.32"
        ));
        properties.put("radius", Map.of(
                "type", "integer",
                "description", "搜索半径，单位km，默认5km",
                "default", 5
        ));

        JsonSchema inputSchema = new JsonSchema(
                "object", properties, List.of("longitude", "latitude"), null, null, null);

        return Tool.builder()
                .name(TOOL_ID)
                .description("根据用户当前位置（经纬度）搜索附近的店铺，返回距离和店铺信息。如果用户没有提供经纬度，使用杭州市中心坐标（longitude=120.15, latitude=30.32）作为默认值")
                .inputSchema(inputSchema)
                .build();
    }

    private CallToolResult handleCall(CallToolRequest request) {
        long startMs = System.currentTimeMillis();
        try {
            Map<String, Object> args = request.arguments() != null ? request.arguments() : Map.of();
            Double longitude = doubleArg(args, "longitude");
            Double latitude = doubleArg(args, "latitude");
            Integer radius = intArg(args, "radius");

            if (longitude == null) longitude = 120.15;
            if (latitude == null) latitude = 30.32;
            if (radius == null) radius = 5;

            // Redis GEO 搜索
            Circle circle = new Circle(
                    new Point(longitude, latitude),
                    new Distance(radius, Metrics.KILOMETERS)
            );
            RedisGeoCommands.GeoRadiusCommandArgs args2 = RedisGeoCommands
                    .GeoRadiusCommandArgs.newGeoRadiusArgs()
                    .includeDistance()
                    .sortAscending()
                    .limit(10);

            GeoResults<RedisGeoCommands.GeoLocation<String>> results =
                    stringRedisTemplate.opsForGeo().radius(GEO_KEY, circle, args2);

            if (results == null || results.getContent().isEmpty()) {
                return successResult("附近 " + radius + "km 内暂未找到店铺。");
            }

            // 查询店铺详情
            List<String> shopIds = new ArrayList<>();
            List<Double> distances = new ArrayList<>();
            for (GeoResult<RedisGeoCommands.GeoLocation<String>> result : results.getContent()) {
                shopIds.add(result.getContent().getName());
                distances.add(result.getDistance().getValue());
            }

            StringBuilder sb = new StringBuilder();
            sb.append(String.format("您附近 %dkm 内共找到 %d 家店铺：\n\n", radius, shopIds.size()));

            for (int i = 0; i < shopIds.size(); i++) {
                String shopId = shopIds.get(i);
                Double distance = distances.get(i);
                List<Map<String, Object>> shopInfo = jdbcTemplate.queryForList(
                        "SELECT s.name, t.name as type_name, s.area, s.address, s.avg_price " +
                                "FROM tb_shop s JOIN tb_shop_type t ON s.type_id = t.id WHERE s.id = ?",
                        Long.parseLong(shopId)
                );
                if (!shopInfo.isEmpty()) {
                    Map<String, Object> shop = shopInfo.get(0);
                    sb.append(String.format("%d. 【%s】距您 %.2fkm\n", i + 1, shop.get("name"), distance));
                    sb.append(String.format("   类型：%s | 区域：%s\n", shop.get("type_name"), shop.get("area")));
                    sb.append(String.format("   地址：%s\n", shop.get("address")));
                    sb.append(String.format("   人均：%s元\n\n", shop.get("avg_price")));
                }
            }

            log.info("MCP GEO 查询完成, toolId={}, 结果数={}, elapsed={}ms",
                    TOOL_ID, shopIds.size(), System.currentTimeMillis() - startMs);
            return successResult(sb.toString().trim());

        } catch (Exception e) {
            log.error("MCP GEO 查询失败, toolId={}", TOOL_ID, e);
            return errorResult("查询失败: " + e.getMessage());
        }
    }

    private static Double doubleArg(Map<String, Object> args, String key) {
        Object val = args.get(key);
        if (val instanceof Number n) return n.doubleValue();
        return null;
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