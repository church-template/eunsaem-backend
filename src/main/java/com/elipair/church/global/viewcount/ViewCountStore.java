package com.elipair.church.global.viewcount;

import com.elipair.church.global.tenant.TenantContext;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 조회수 버퍼(스펙 §9). 키 = view:{churchId}:{namespace}:{id}, 값 = 미플러시 누적 조회수.
 * 상세조회는 increment(원자 INCR)로 카운팅하고, 주기 잡이 drain(SCAN+GETDEL)으로 빼 DB에 반영한다.
 * KEYS 금지(Redis keyspace 공유) → SCAN 사용. GETDEL(Redis 6.2+)로 get+삭제를 원자화해 유실/중복을 막는다.
 *
 * <p>멀티테넌시(#86): 키에 교회 id가 들어간다. 주기 잡은 요청 밖에서 돌아 교회를 모르므로,
 * drain이 교회별로 묶어 돌려주고 잡이 교회마다 테넌트를 지정해 반영한다.
 */
@Component
public class ViewCountStore {

    static final String PREFIX = "view:";

    private final StringRedisTemplate redis;

    public ViewCountStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    private String key(long churchId, String namespace, long id) {
        return PREFIX + churchId + ":" + namespace + ":" + id;
    }

    /** 현재 교회의 조회수 +1. 증가 후의 미플러시 누적값을 반환(라이브 표시용). */
    public long increment(String namespace, long id) {
        Long value = redis.opsForValue().increment(key(TenantContext.require(), namespace, id));
        return value == null ? 0L : value;
    }

    /** 지정 delta만큼 버퍼 증가. 플러시(drain) 후 DB 반영 실패 시 드레인분을 버퍼에 되돌리는 복구용. */
    public long incrementBy(long churchId, String namespace, long id, long delta) {
        Long value = redis.opsForValue().increment(key(churchId, namespace, id), delta);
        return value == null ? 0L : value;
    }

    /**
     * 해당 namespace의 모든 교회 버퍼를 원자적으로 비우고 (churchId → (id → delta))로 반환.
     * 형식이 맞지 않는 키(전환 전 형식 view:{namespace}:{id} 등)는 건드리지 않고 건너뛴다.
     */
    public Map<Long, Map<Long, Long>> drain(String namespace) {
        Pattern keyPattern =
                Pattern.compile("^" + Pattern.quote(PREFIX) + "(\\d+):" + Pattern.quote(namespace) + ":(\\d+)$");
        ScanOptions options = ScanOptions.scanOptions()
                .match(PREFIX + "*:" + namespace + ":*")
                .count(100)
                .build();
        Map<Long, Map<Long, Long>> byChurch = new HashMap<>();
        try (Cursor<String> cursor = redis.scan(options)) {
            cursor.forEachRemaining(key -> {
                Matcher matcher = keyPattern.matcher(key);
                if (!matcher.matches()) {
                    return;
                }
                String value = redis.opsForValue().getAndDelete(key); // GETDEL: 원자 get+삭제
                if (value != null) {
                    byChurch.computeIfAbsent(Long.parseLong(matcher.group(1)), church -> new HashMap<>())
                            .merge(Long.parseLong(matcher.group(2)), Long.parseLong(value), Long::sum);
                }
            });
        }
        return byChurch;
    }
}
