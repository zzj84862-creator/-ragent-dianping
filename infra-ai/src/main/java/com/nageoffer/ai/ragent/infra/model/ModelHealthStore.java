package com.nageoffer.ai.ragent.infra.model;

import cn.hutool.json.JSONUtil;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import lombok.extern.slf4j.Slf4j;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 模型健康状态存储器（Redis 分布式版本）
 * 将熔断状态从单机内存改为 Redis 持久化，解决多实例部署下状态不一致问题
 */

@Slf4j
@Component
@RequiredArgsConstructor
public class ModelHealthStore {

    private final AIModelProperties properties;
    private final StringRedisTemplate redisTemplate;

    private static final String KEY_PREFIX = "model:health:";

    // ==================== 公共方法 ====================

    public boolean isUnavailable(String id) {
        ModelHealth health = getHealth(id);
        if (health == null) {
            return false;
        }
        if (health.getState() == State.OPEN && health.getOpenUntil() > System.currentTimeMillis()) {
            return true;
        }
        return health.getState() == State.HALF_OPEN && health.isHalfOpenInFlight();
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public boolean allowCall(String id) {
        if (id == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        ModelHealth health = getHealth(id);
        if (health == null) {
            health = new ModelHealth();
        }

        boolean allowed = false;

        if (health.getState() == State.OPEN) {
            if (health.getOpenUntil() > now) {
                // 还在冷却期，不允许
                return false;
            }
            // 冷却期结束，转为半开，放行一个探测请求
            health.setState(State.HALF_OPEN);
            health.setHalfOpenInFlight(true);
            allowed = true;
        } else if (health.getState() == State.HALF_OPEN) {
            if (health.isHalfOpenInFlight()) {
                // 已有探测请求在飞，不允许
                return false;
            }
            health.setHalfOpenInFlight(true);
            allowed = true;
        } else {
            // CLOSED 状态，正常放行
            allowed = true;
        }

        saveHealth(id, health);
        return allowed;
    }

    public void markSuccess(String id) {
        if (id == null) {
            return;
        }
        ModelHealth health = getHealth(id);
        if (health == null) {
            health = new ModelHealth();
        }
        // 无论什么状态，成功就重置为 CLOSED
        health.setState(State.CLOSED);
        health.setConsecutiveFailures(0);
        health.setOpenUntil(0L);
        health.setHalfOpenInFlight(false);
        saveHealth(id, health);
    }

    public void markFailure(String id) {
        log.info("markFailure called for model: {}", id);  // 加这行
        if (id == null) {
            return;
        }
        long now = System.currentTimeMillis();
        long openDurationMs = properties.getSelection().getOpenDurationMs();
        int failureThreshold = properties.getSelection().getFailureThreshold();

        ModelHealth health = getHealth(id);
        if (health == null) {
            health = new ModelHealth();
        }

        if (health.getState() == State.HALF_OPEN) {
            // 半开状态下探测失败，直接回到 OPEN
            health.setState(State.OPEN);
            health.setOpenUntil(now + openDurationMs);
            health.setConsecutiveFailures(0);
            health.setHalfOpenInFlight(false);
        } else {
            // CLOSED 状态下累计失败次数
            health.setConsecutiveFailures(health.getConsecutiveFailures() + 1);
            if (health.getConsecutiveFailures() >= failureThreshold) {
                health.setState(State.OPEN);
                health.setOpenUntil(now + openDurationMs);
                health.setConsecutiveFailures(0);
            }
        }

        saveHealth(id, health);
    }

    // ==================== Redis 读写 ====================

    private ModelHealth getHealth(String id) {
        String json = redisTemplate.opsForValue().get(KEY_PREFIX + id);
        if (json == null) {
            return null;
        }
        return JSONUtil.toBean(json, ModelHealth.class);
    }

    private void saveHealth(String id, ModelHealth health) {
        // 过期时间设为冷却期的2倍，CLOSED状态的key自然过期清理
        long ttlMs = properties.getSelection().getOpenDurationMs() * 2;
        redisTemplate.opsForValue().set(
                KEY_PREFIX + id,
                JSONUtil.toJsonStr(health),
                ttlMs,
                TimeUnit.MILLISECONDS
        );
    }

    // ==================== 内部类 ====================

    @Data
    @NoArgsConstructor
    static class ModelHealth {
        private int consecutiveFailures = 0;
        private long openUntil = 0L;
        private boolean halfOpenInFlight = false;
        private State state = State.CLOSED;
    }

    enum State {
        CLOSED,
        OPEN,
        HALF_OPEN
    }
}