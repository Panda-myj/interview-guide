package interview.guide.modules.voiceinterview.service;

import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 语音会话租约服务。
 *
 * <p>每个 WebSocket 连接在接入时申请一份带 fencing token 的租约，之后按心跳周期续期。
 * 同一会话同一时刻只允许一个持有者真正驱动会话：</p>
 * <ul>
 *   <li>窗口内（默认 5 秒）的第二个连接视为「重复连接」，直接拒绝；</li>
 *   <li>窗口外的连接视为「断线重连」，接管租约并把 fencing token +1，旧连接的续期/释放将被拒绝；</li>
 *   <li>Redis 不可用时降级为进程内租约，保证单机会话不因缓存故障中断，同时通过 {@link #isDegraded()}
 *       让孤儿会话回收任务暂停，避免误结束其它节点上的存活会话。</li>
 * </ul>
 *
 * <p>租约存储格式：{@code connectionId|fencingToken|expiresAtEpochMs|lastRenewAtEpochMs}，
 * 只包含连接标识与时间，不包含音频或识别文本。</p>
 */
@Service
@Slf4j
public class VoiceSessionLeaseService {

    static final String LEASE_KEY_PREFIX = "voice:interview:lease:";
    private static final String FIELD_SEPARATOR = "|";
    private static final String SAFE_CONNECTION_ID_FALLBACK = "unknown";

    private final RedisService redisService;
    private final VoiceInterviewProperties properties;
    private final Clock clock;
    private final ConcurrentMap<String, LeaseSnapshot> localLeases = new ConcurrentHashMap<>();

    private volatile boolean degraded;

    @Autowired
    public VoiceSessionLeaseService(RedisService redisService, VoiceInterviewProperties properties) {
        this(redisService, properties, Clock.systemUTC());
    }

    VoiceSessionLeaseService(RedisService redisService, VoiceInterviewProperties properties, Clock clock) {
        this.redisService = redisService;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 申请会话租约。
     */
    public LeaseAttempt acquire(String sessionId, String connectionId) {
        String normalizedConnectionId = normalizeConnectionId(connectionId);
        long now = clock.millis();
        long ttlMs = leaseTtlMs();
        LeaseSnapshot existing = read(sessionId);

        if (existing != null
            && !existing.isExpired(now)
            && !existing.connectionId().equals(normalizedConnectionId)
            && now - existing.lastRenewAt() < duplicateWindowMs()) {
            log.info("Voice session lease held by another connection: sessionId={}, holder={}, candidate={}",
                sessionId, existing.connectionId(), normalizedConnectionId);
            return LeaseAttempt.rejected(existing);
        }

        long fencingToken = existing == null ? 1L : existing.fencingToken() + 1L;
        LeaseSnapshot lease = new LeaseSnapshot(normalizedConnectionId, fencingToken, now + ttlMs, now);
        boolean persisted = write(sessionId, lease, ttlMs);
        localLeases.put(sessionId, lease);
        if (!persisted) {
            return LeaseAttempt.degraded(lease);
        }
        return LeaseAttempt.granted(lease);
    }

    /**
     * 续期。持有者与 fencing token 必须同时匹配，否则视为租约丢失。
     *
     * <p>租约因 Redis 丢失或 TTL 过期而不存在时，同一持有者会尝试自愈式重建，
     * 避免缓存抖动直接中断进行中的会话。</p>
     */
    public boolean renew(String sessionId, String connectionId, long fencingToken) {
        String normalizedConnectionId = normalizeConnectionId(connectionId);
        long now = clock.millis();
        long ttlMs = leaseTtlMs();
        LeaseSnapshot current = read(sessionId);

        if (current == null) {
            // 租约丢失（Redis 清空 / TTL 过期）：持有者原地重建，保留原 fencing token，
            // 以免把仍在进行的会话误判为失联；若其它节点已接管，则本地写入会被后续读覆盖，
            // 但不会再放大 token，最终仍由对账任务收敛。
            LeaseSnapshot rebuilt = new LeaseSnapshot(normalizedConnectionId, fencingToken, now + ttlMs, now);
            boolean persisted = write(sessionId, rebuilt, ttlMs);
            localLeases.put(sessionId, rebuilt);
            log.warn("Voice session lease rebuilt after loss: sessionId={}, connectionId={}, persisted={}",
                sessionId, normalizedConnectionId, persisted);
            return persisted || degraded;
        }
        if (!tokenMatches(current, normalizedConnectionId, fencingToken)) {
            return false;
        }

        LeaseSnapshot renewed = new LeaseSnapshot(normalizedConnectionId, fencingToken, now + ttlMs, now);
        boolean persisted = write(sessionId, renewed, ttlMs);
        localLeases.put(sessionId, renewed);
        return persisted || degraded;
    }

    /**
     * 释放租约。只有当前持有者可以释放，避免旧连接关闭时删掉新连接的租约。
     */
    public boolean release(String sessionId, String connectionId, long fencingToken) {
        String normalizedConnectionId = normalizeConnectionId(connectionId);
        LeaseSnapshot current = read(sessionId);
        localLeases.remove(sessionId);
        if (current == null) {
            return true;
        }
        if (!tokenMatches(current, normalizedConnectionId, fencingToken)) {
            log.info("Ignore lease release from stale connection: sessionId={}, connectionId={}",
                sessionId, normalizedConnectionId);
            return false;
        }
        try {
            redisService.delete(leaseKey(sessionId));
        } catch (RuntimeException e) {
            log.warn("Failed to delete voice session lease: sessionId={}, error={}", sessionId, e.getMessage());
        }
        return true;
    }

    /**
     * 当前连接是否仍持有有效租约。
     */
    public boolean isLeaseHolder(String sessionId, String connectionId, long fencingToken) {
        LeaseSnapshot current = read(sessionId);
        return current != null
            && tokenMatches(current, normalizeConnectionId(connectionId), fencingToken)
            && !current.isExpired(clock.millis());
    }

    /**
     * 会话是否存在有效租约（用于孤儿会话回收判定）。
     */
    public boolean hasActiveLease(String sessionId) {
        LeaseSnapshot current = read(sessionId);
        return current != null && !current.isExpired(clock.millis());
    }

    public Optional<LeaseSnapshot> inspect(String sessionId) {
        return Optional.ofNullable(read(sessionId));
    }

    /**
     * Redis 是否处于降级状态。降级期间孤儿会话回收任务应暂停，避免误判存活会话。
     */
    public boolean isDegraded() {
        return degraded;
    }

    private boolean tokenMatches(LeaseSnapshot lease, String connectionId, long fencingToken) {
        return lease != null
            && lease.connectionId().equals(connectionId)
            && lease.fencingToken() == fencingToken;
    }

    private LeaseSnapshot read(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        try {
            Object raw = redisService.get(leaseKey(sessionId));
            degraded = false;
            return parse(raw);
        } catch (RuntimeException e) {
            degraded = true;
            log.warn("Voice session lease read degraded to local cache: sessionId={}, error={}",
                sessionId, e.getMessage());
            return localLeases.get(sessionId);
        }
    }

    private boolean write(String sessionId, LeaseSnapshot lease, long ttlMs) {
        try {
            redisService.set(leaseKey(sessionId), format(lease), Duration.ofMillis(ttlMs));
            degraded = false;
            return true;
        } catch (RuntimeException e) {
            degraded = true;
            log.warn("Voice session lease write degraded to local cache: sessionId={}, error={}",
                sessionId, e.getMessage());
            return false;
        }
    }

    private static String format(LeaseSnapshot lease) {
        return lease.connectionId()
            + FIELD_SEPARATOR + lease.fencingToken()
            + FIELD_SEPARATOR + lease.expiresAt()
            + FIELD_SEPARATOR + lease.lastRenewAt();
    }

    private static LeaseSnapshot parse(Object raw) {
        if (raw == null) {
            return null;
        }
        String[] parts = raw.toString().split("\\" + FIELD_SEPARATOR, -1);
        if (parts.length != 4) {
            log.warn("Malformed voice session lease value ignored");
            return null;
        }
        try {
            return new LeaseSnapshot(parts[0], Long.parseLong(parts[1]),
                Long.parseLong(parts[2]), Long.parseLong(parts[3]));
        } catch (NumberFormatException e) {
            log.warn("Malformed voice session lease timestamps ignored");
            return null;
        }
    }

    private String leaseKey(String sessionId) {
        return LEASE_KEY_PREFIX + sessionId;
    }

    private static String normalizeConnectionId(String connectionId) {
        if (connectionId == null || connectionId.isBlank()) {
            return SAFE_CONNECTION_ID_FALLBACK;
        }
        return connectionId.replace(FIELD_SEPARATOR, "_");
    }

    private long leaseTtlMs() {
        return Math.max(1, properties.getLifecycle().getLeaseTtlSeconds()) * 1000L;
    }

    private long duplicateWindowMs() {
        return Math.max(0, properties.getLifecycle().getDuplicateConnectionWindowSeconds()) * 1000L;
    }

    /**
     * 租约快照。
     *
     * @param connectionId 持有者连接标识
     * @param fencingToken 单调递增的栅栏令牌，用于拒绝旧连接的续期与释放
     * @param expiresAt    过期时间（epoch 毫秒）
     * @param lastRenewAt  最近一次续期时间（epoch 毫秒）
     */
    public record LeaseSnapshot(String connectionId, long fencingToken, long expiresAt, long lastRenewAt) {

        public boolean isExpired(long now) {
            return now >= expiresAt;
        }
    }

    /**
     * 申请结果。{@code rejected} 表示窗口内已有其它连接持有租约。
     */
    public record LeaseAttempt(boolean granted, boolean degraded, LeaseSnapshot lease) {

        public static LeaseAttempt granted(LeaseSnapshot lease) {
            return new LeaseAttempt(true, false, lease);
        }

        public static LeaseAttempt rejected(LeaseSnapshot lease) {
            return new LeaseAttempt(false, false, lease);
        }

        public static LeaseAttempt degraded(LeaseSnapshot lease) {
            return new LeaseAttempt(true, true, lease);
        }

        public boolean rejected() {
            return !granted;
        }
    }
}
