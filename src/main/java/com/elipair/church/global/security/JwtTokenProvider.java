package com.elipair.church.global.security;

import com.elipair.church.global.tenant.TenantContext;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Component;

/**
 * JWT 발급·파싱·검증(스펙 §4.2). HS256. sub=uuid, mid=member.id(내부), 펼쳐진 permissions·maxPriority.
 * 발급은 D4(auth)가 로그인 시 호출한다. parse는 서명·만료·형식을 검증하며 실패 시 JwtException 계열을 던진다.
 */
@Component
public class JwtTokenProvider {

    public static final String CLAIM_MID = "mid";
    public static final String CLAIM_NAME = "name";
    public static final String CLAIM_POSITION = "position";
    public static final String CLAIM_PERMISSIONS = "permissions";
    public static final String CLAIM_MAX_PRIORITY = "maxPriority";
    public static final String CLAIM_TYPE = "type";
    /** 교회 id(#86). 요청의 교회와 다르면 그 토큰은 무효다 — 한 교회의 토큰을 다른 교회 주소에서 쓸 수 없다. */
    public static final String CLAIM_CID = "cid";

    public static final String TYPE_ACCESS = "access";
    public static final String TYPE_REFRESH = "refresh";

    private final SecretKey key;
    private final Duration accessExpiry;
    private final Duration refreshExpiry;

    public JwtTokenProvider(JwtProperties properties) {
        // 32바이트 미만이면 Keys.hmacShaKeyFor가 WeakKeyException으로 기동을 막는다(빠른 실패).
        this.key = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
        this.accessExpiry = Duration.ofSeconds(properties.accessExpiry());
        this.refreshExpiry = Duration.ofSeconds(properties.refreshExpiry());
    }

    public String issueAccess(MemberPrincipal principal, String position, List<String> permissions) {
        Date now = new Date();
        return Jwts.builder()
                .subject(principal.uuid())
                .id(UUID.randomUUID().toString())
                .issuedAt(now)
                .expiration(new Date(now.getTime() + accessExpiry.toMillis()))
                .claim(CLAIM_TYPE, TYPE_ACCESS)
                .claim(CLAIM_MID, principal.id())
                .claim(CLAIM_NAME, principal.name())
                .claim(CLAIM_POSITION, position)
                .claim(CLAIM_PERMISSIONS, permissions)
                .claim(CLAIM_MAX_PRIORITY, principal.maxPriority())
                .claim(CLAIM_CID, TenantContext.require())
                .signWith(key)
                .compact();
    }

    public String issueRefresh(String uuid) {
        Date now = new Date();
        return Jwts.builder()
                .subject(uuid)
                .id(UUID.randomUUID().toString())
                .issuedAt(now)
                .expiration(new Date(now.getTime() + refreshExpiry.toMillis()))
                .claim(CLAIM_TYPE, TYPE_REFRESH)
                .signWith(key)
                .compact();
    }

    /** 서명·만료·형식 검증. 실패 시 JwtException 계열(Expired/Signature/Malformed) 또는 IllegalArgumentException. */
    public Claims parse(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }
}
