package com.elipair.church.global.tenant;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

@Configuration
@EnableConfigurationProperties(TenantProperties.class)
public class TenantConfig {

    /**
     * Spring Security 필터 체인(기본 순서 -100)보다 앞에 둔다. 뒤에 두면 보안 매처가 접두사가 붙은 경로를 보고
     * {@code /c/x/api/admin/**}을 공개 경로로 통과시킨다. (@Component로 등록하지 않는다 — 부트가 맨 뒤 순서로 한 번 더 등록한다.)
     */
    static final int ORDER = -101;

    @Bean
    FilterRegistrationBean<TenantFilter> tenantFilterRegistration(
            ChurchResolver resolver, TenantProperties properties, ObjectMapper objectMapper) {
        FilterRegistrationBean<TenantFilter> registration =
                new FilterRegistrationBean<>(new TenantFilter(resolver, properties, objectMapper));
        registration.setOrder(ORDER);
        return registration;
    }
}
