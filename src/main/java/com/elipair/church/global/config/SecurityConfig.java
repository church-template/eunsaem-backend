package com.elipair.church.global.config;

import com.elipair.church.global.security.JwtAccessDeniedHandler;
import com.elipair.church.global.security.JwtAuthenticationEntryPoint;
import com.elipair.church.global.security.JwtAuthenticationFilter;
import com.elipair.church.global.security.JwtProperties;
import com.elipair.church.global.security.JwtTokenProvider;
import com.elipair.church.global.security.redis.TokenBlacklist;
import com.elipair.church.global.tenant.ChurchInfo;
import com.elipair.church.global.tenant.TenantFilter;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

/**
 * 경로 인가(스펙 §4.3): /api/admin/** 인증(세부 권한은 메서드 @PreAuthorize), /api/gallery/** GALLERY_VIEW,
 * /api/bible-challenges/** CHALLENGE_PARTICIPATE, /api/sermons/** SERMON_VIEW(회원 전용), /api/vehicle-runs/** VEHICLE_APPLY,
 * 그 외 공개.
 * JWT 필터·인증 실패 RFC 7807 변환·CORS·메서드 보안을 배선한다.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties(JwtProperties.class)
public class SecurityConfig {

    private final JwtTokenProvider tokenProvider;
    private final TokenBlacklist tokenBlacklist;
    private final JwtAuthenticationEntryPoint authenticationEntryPoint;
    private final JwtAccessDeniedHandler accessDeniedHandler;

    public SecurityConfig(
            JwtTokenProvider tokenProvider,
            TokenBlacklist tokenBlacklist,
            JwtAuthenticationEntryPoint authenticationEntryPoint,
            JwtAccessDeniedHandler accessDeniedHandler) {
        this.tokenProvider = tokenProvider;
        this.tokenBlacklist = tokenBlacklist;
        this.authenticationEntryPoint = authenticationEntryPoint;
        this.accessDeniedHandler = accessDeniedHandler;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.requestMatchers(
                                "/v3/api-docs", "/v3/api-docs/**", "/docs/swagger-ui/**", "/docs/swagger-ui.html")
                        .permitAll()
                        .requestMatchers("/error")
                        .permitAll()
                        .requestMatchers("/actuator/health")
                        .permitAll()
                        .requestMatchers("/api/admin/**")
                        .authenticated()
                        .requestMatchers("/api/gallery/**")
                        .hasAuthority("GALLERY_VIEW")
                        .requestMatchers("/api/bible-challenges/**")
                        .hasAuthority("CHALLENGE_PARTICIPATE")
                        .requestMatchers("/api/sermons/**")
                        .hasAuthority("SERMON_VIEW")
                        .requestMatchers("/api/vehicle-runs/**")
                        .hasAuthority("VEHICLE_APPLY")
                        .anyRequest()
                        .permitAll())
                .exceptionHandling(ex ->
                        ex.authenticationEntryPoint(authenticationEntryPoint).accessDeniedHandler(accessDeniedHandler))
                .addFilterBefore(
                        new JwtAuthenticationFilter(tokenProvider, tokenBlacklist),
                        UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /** 회원 비밀번호 해시(스펙 §4.1, 복잡도 강제 없음·BCrypt). reset-password·비번변경·부트스트랩·D4 login이 사용. */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * 요청별 CORS(#86): 요청의 교회({@link TenantFilter}가 request attribute에 넣는다)의 프론트 주소 하나만 허용한다.
     * 교회가 없는 요청(헬스·Swagger)은 CORS 설정이 없다 — 프리플라이트는 거부되고 일반 요청은 헤더 없이 통과한다.
     * allowCredentials(true)는 와일드카드 origin과 공존할 수 없으므로 '*'는 허용하지 않는다.
     */
    private CorsConfigurationSource corsConfigurationSource() {
        return request -> {
            if (!(request.getAttribute(TenantFilter.CHURCH_ATTRIBUTE) instanceof ChurchInfo church)
                    || "*".equals(church.frontendOrigin())) {
                return null;
            }
            CorsConfiguration config = new CorsConfiguration();
            config.setAllowedOrigins(List.of(church.frontendOrigin()));
            config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
            config.setAllowedHeaders(List.of("*"));
            config.setAllowCredentials(true);
            return config;
        };
    }
}
