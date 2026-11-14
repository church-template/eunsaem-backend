package com.elipair.church.global.config;

import com.elipair.church.global.security.SecurityAuditorAware;
import com.elipair.church.global.tenant.TenantIdentifierResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * JPA Auditing 활성화. createdAt/updatedAt는 자동, createdBy/updatedBy는 SecurityAuditorAware가 공급한다(#4).
 */
@Configuration
@EnableJpaAuditing(auditorAwareRef = "securityAuditorAware")
public class JpaConfig {

    @Bean
    public SecurityAuditorAware securityAuditorAware() {
        return new SecurityAuditorAware();
    }

    /**
     * Hibernate에 현재 교회를 알려 주는 리졸버(#86). 빈으로 등록하면 부트가 Hibernate에 자동 연결한다.
     * 리포지토리 슬라이스 테스트(@DataJpaTest)도 이 설정을 import하므로 여기서 등록한다.
     */
    @Bean
    public TenantIdentifierResolver tenantIdentifierResolver() {
        return new TenantIdentifierResolver();
    }
}
