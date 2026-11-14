package com.elipair.church.domain.auth;

import com.elipair.church.global.exception.BusinessException;
import com.elipair.church.global.exception.ErrorCode;
import com.elipair.church.global.tenant.TenantContext;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * 로그인 실패 횟수 제한(#86). 비밀번호 정책이 최소 길이뿐이고(어르신 사용자 배려) 서버 하나에 전 교회 계정이 모이므로
 * 무제한 대입을 막는다.
 *
 * <ul>
 *   <li>계정 단위: (교회, 전화번호, IP)당 {@value #MAX_PER_ACCOUNT}회 / 15분. IP를 섞는 이유 — 전화번호만으로 잠그면
 *       번호만 아는 사람이 특정 회원을 계속 잠글 수 있다. 공격자는 자기 IP에서만 막히고 본인은 자기 IP에서 로그인된다.
 *   <li>IP 단위: IP당 {@value #MAX_PER_IP}회 / 15분. 번호를 바꿔 가며 시도하는 공격 대응(전 교회 합산).
 * </ul>
 *
 * 회원이 없는 번호도 똑같이 센다(번호 존재 여부를 드러내지 않는다). 성공하면 계정 단위 횟수만 지운다.
 * Redis 장애 시에는 통과시킨다(fail-open) — {@code InquiryRateLimiter}와 같은 가용성 우선 정책.
 */
@Component
public class LoginRateLimiter {

    static final String ACCOUNT_PREFIX = "auth:login:fail:";
    static final String IP_PREFIX = "auth:login:fail-ip:";
    static final int MAX_PER_ACCOUNT = 10;
    static final int MAX_PER_IP = 100;
    private static final Duration WINDOW = Duration.ofMinutes(15);
    private static final int MAX_IP_LENGTH = 64;

    /** INCR 후 최초(=1)일 때만 TTL을 건다(InquiryRateLimiter와 같은 스크립트 — TTL 없는 키가 남지 않게). */
    private static final RedisScript<Long> INCR_WITH_TTL = new DefaultRedisScript<>("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then
              redis.call('EXPIRE', KEYS[1], ARGV[1])
            end
            return count
            """, Long.class);

    private static final Logger log = LoggerFactory.getLogger(LoginRateLimiter.class);

    private final StringRedisTemplate redis;

    public LoginRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** 한도를 넘었으면 429. phone은 정규화된 값(숫자만). */
    public void checkAllowed(String phone, String clientIp) {
        if (count(accountKey(phone, clientIp)) >= MAX_PER_ACCOUNT || count(ipKey(clientIp)) >= MAX_PER_IP) {
            throw new BusinessException(ErrorCode.RATE_LIMIT_EXCEEDED, "로그인 시도가 너무 많습니다. 15분 뒤에 다시 시도해 주세요.");
        }
    }

    public void recordFailure(String phone, String clientIp) {
        increment(accountKey(phone, clientIp));
        increment(ipKey(clientIp));
    }

    public void reset(String phone, String clientIp) {
        try {
            redis.delete(accountKey(phone, clientIp));
        } catch (DataAccessException e) {
            log.warn("로그인 제한 Redis 접근 실패 — 초기화를 건너뛴다(fail-open).", e);
        }
    }

    private String accountKey(String phone, String clientIp) {
        return ACCOUNT_PREFIX + TenantContext.require() + ":" + phone + ":" + ip(clientIp);
    }

    private String ipKey(String clientIp) {
        return IP_PREFIX + ip(clientIp);
    }

    /** 헤더에서 온 값일 수 있으므로 키에 넣기 전에 길이를 자른다. */
    private static String ip(String clientIp) {
        if (clientIp == null) {
            return "unknown";
        }
        return clientIp.length() > MAX_IP_LENGTH ? clientIp.substring(0, MAX_IP_LENGTH) : clientIp;
    }

    private long count(String key) {
        try {
            String value = redis.opsForValue().get(key);
            return value == null ? 0L : Long.parseLong(value);
        } catch (DataAccessException | NumberFormatException e) {
            log.warn("로그인 제한 Redis 접근 실패 — 로그인은 허용한다(fail-open).", e);
            return 0L;
        }
    }

    private void increment(String key) {
        try {
            redis.execute(INCR_WITH_TTL, List.of(key), String.valueOf(WINDOW.toSeconds()));
        } catch (DataAccessException e) {
            log.warn("로그인 제한 Redis 접근 실패 — 실패 기록을 건너뛴다(fail-open).", e);
        }
    }
}
