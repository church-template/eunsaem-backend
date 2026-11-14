package com.elipair.church.global.tenant;

import com.elipair.church.global.exception.ErrorCode;
import com.elipair.church.global.security.SecurityErrorResponses;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * 요청의 교회를 정한다(#86). 보안 필터 체인보다 <b>앞</b>에서 돈다.
 *
 * <ul>
 *   <li>{@code /church/{slug}/api/...} — slug로 교회를 찾고, 접두사를 뗀 {@code /api/...}로 감싸 넘긴다.
 *       그래서 SecurityConfig 매처와 컨트롤러 매핑은 접두사를 모른다.
 *   <li>접두사 없는 {@code /api/...} — tenant.default-slug의 교회로 처리한다. 값이 없으면 404.
 *   <li>그 외({@code /actuator/health}, Swagger, {@code /error}) — 교회 없이 통과.
 * </ul>
 *
 * 교회를 못 찾거나 {@code /church/}로 시작하는데 형식이 다르면 404(RFC 7807).
 */
@Slf4j
public class TenantFilter extends OncePerRequestFilter {

    /** request attribute: 이 요청의 {@link ChurchInfo}. CORS가 교회의 frontend_origin을 읽는 데 쓴다. */
    public static final String CHURCH_ATTRIBUTE = "church.info";

    private static final String MDC_KEY = "churchId";
    private static final Pattern PREFIXED = Pattern.compile("^(/church/([^/]+))(/api/.*)$");

    private final ChurchResolver resolver;
    private final TenantProperties properties;
    private final ObjectMapper objectMapper;

    public TenantFilter(ChurchResolver resolver, TenantProperties properties, ObjectMapper objectMapper) {
        this.resolver = resolver;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        Matcher prefixed = PREFIXED.matcher(path);
        if (prefixed.matches()) {
            Optional<ChurchInfo> church = resolver.findBySlug(prefixed.group(2));
            if (church.isEmpty()) {
                notFound(request, response);
                return;
            }
            proceed(new PrefixStrippedRequest(request, prefixed.group(1)), response, chain, church.get());
        } else if (path.equals("/church") || path.startsWith("/church/")) {
            notFound(request, response);
        } else if (path.equals("/api") || path.startsWith("/api/")) {
            Optional<ChurchInfo> church =
                    properties.hasDefaultSlug() ? resolver.findBySlug(properties.defaultSlug()) : Optional.empty();
            if (church.isEmpty()) {
                notFound(request, response);
                return;
            }
            // 운영 전환 기간에 이 로그가 0건이 되면 TENANT_DEFAULT_SLUG를 지워도 된다(설계 §6-7).
            log.info("기본 교회로 처리(접두사 없는 요청): {} {}", request.getMethod(), path);
            proceed(request, response, chain, church.get());
        } else {
            chain.doFilter(request, response);
        }
    }

    private void proceed(HttpServletRequest request, HttpServletResponse response, FilterChain chain, ChurchInfo church)
            throws ServletException, IOException {
        request.setAttribute(CHURCH_ATTRIBUTE, church);
        String previousMdc = MDC.get(MDC_KEY);
        MDC.put(MDC_KEY, String.valueOf(church.id()));
        try (TenantContext.Scope ignored = TenantContext.enter(church.id())) {
            chain.doFilter(request, response);
        } finally {
            if (previousMdc == null) {
                MDC.remove(MDC_KEY);
            } else {
                MDC.put(MDC_KEY, previousMdc);
            }
        }
    }

    private void notFound(HttpServletRequest request, HttpServletResponse response) throws IOException {
        SecurityErrorResponses.write(response, request, ErrorCode.RESOURCE_NOT_FOUND, objectMapper);
    }

    /**
     * 접두사({@code /church/{slug}})를 뗀 경로를 돌려주는 래퍼. 보안 매처와 MVC가 같은 경로를 보도록
     * requestURI·servletPath·requestURL을 모두 맞춘다(하나라도 다르면 인가를 우회한다).
     */
    private static final class PrefixStrippedRequest extends HttpServletRequestWrapper {

        private final String prefix;

        PrefixStrippedRequest(HttpServletRequest request, String prefix) {
            super(request);
            this.prefix = prefix;
        }

        @Override
        public String getRequestURI() {
            String contextPath = super.getContextPath();
            return contextPath + super.getRequestURI().substring(contextPath.length() + prefix.length());
        }

        @Override
        public String getServletPath() {
            String servletPath = super.getServletPath();
            return servletPath.startsWith(prefix) ? servletPath.substring(prefix.length()) : servletPath;
        }

        @Override
        public String getPathInfo() {
            String pathInfo = super.getPathInfo();
            return pathInfo != null && pathInfo.startsWith(prefix) ? pathInfo.substring(prefix.length()) : pathInfo;
        }

        @Override
        public StringBuffer getRequestURL() {
            StringBuffer url = super.getRequestURL();
            int at = url.indexOf(prefix + "/api/");
            return at < 0 ? url : url.delete(at, at + prefix.length());
        }
    }
}
