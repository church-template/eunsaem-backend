package com.elipair.church.global.security;

import com.elipair.church.global.security.redis.TokenBlacklist;
import com.elipair.church.global.tenant.TenantContext;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * access 토큰을 검증해 SecurityContext에 권한을 부여한다(스펙 §4.3).
 * 토큰이 없거나(공개 경로 통과), 만료·위변조·refresh-type·블랙리스트면 컨텍스트를 비워 둔다 —
 * 보호 경로면 이후 EntryPoint가 401 INVALID_TOKEN으로 응답한다.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String HEADER = "Authorization";
    private static final String BEARER = "Bearer ";

    private final JwtTokenProvider tokenProvider;
    private final TokenBlacklist tokenBlacklist;

    public JwtAuthenticationFilter(JwtTokenProvider tokenProvider, TokenBlacklist tokenBlacklist) {
        this.tokenProvider = tokenProvider;
        this.tokenBlacklist = tokenBlacklist;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = resolveToken(request);
        if (token != null) {
            try {
                Claims claims = tokenProvider.parse(token);
                String jti = claims.getId();
                boolean isAccess =
                        JwtTokenProvider.TYPE_ACCESS.equals(claims.get(JwtTokenProvider.CLAIM_TYPE, String.class));
                // 토큰의 교회(cid)가 요청의 교회와 같아야 한다(#86). cid가 없는 전환 전 토큰도 여기서 걸러진다
                // — 클라이언트가 refresh로 새 토큰을 받는다. 둘 중 하나라도 null이면 인증을 세우지 않는다.
                Long tokenChurch = claims.get(JwtTokenProvider.CLAIM_CID, Long.class);
                boolean sameChurch = tokenChurch != null && tokenChurch.equals(TenantContext.currentOrNull());
                if (isAccess && sameChurch && jti != null && !tokenBlacklist.isBlacklisted(jti)) {
                    SecurityContextHolder.getContext().setAuthentication(toAuthentication(claims));
                }
            } catch (JwtException | IllegalArgumentException ignored) {
                // 유효하지 않은 토큰 — 컨텍스트를 비워 둔다(보호 경로면 EntryPoint가 401 처리).
            }
        }
        chain.doFilter(request, response);
    }

    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader(HEADER);
        if (header != null && header.startsWith(BEARER)) {
            return header.substring(BEARER.length());
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Authentication toAuthentication(Claims claims) {
        // jjwt는 JSON 배열을 raw List로 역직렬화하므로 unchecked 캐스트가 불가피하다.
        List<String> permissions = claims.get(JwtTokenProvider.CLAIM_PERMISSIONS, List.class);
        List<SimpleGrantedAuthority> authorities = permissions == null
                ? List.of()
                : permissions.stream().map(SimpleGrantedAuthority::new).toList();
        Integer maxPriority = claims.get(JwtTokenProvider.CLAIM_MAX_PRIORITY, Integer.class);
        MemberPrincipal principal = new MemberPrincipal(
                claims.get(JwtTokenProvider.CLAIM_MID, Long.class),
                claims.getSubject(),
                claims.get(JwtTokenProvider.CLAIM_NAME, String.class),
                maxPriority == null ? 0 : maxPriority);
        return new UsernamePasswordAuthenticationToken(principal, null, authorities);
    }
}
