package com.elipair.church.global.tenant;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** tenant.default-slug — 접두사 없는 /api/** 요청을 처리할 교회. 비어 있으면 그런 요청은 404. */
@ConfigurationProperties(prefix = "tenant")
public record TenantProperties(String defaultSlug) {

    public boolean hasDefaultSlug() {
        return defaultSlug != null && !defaultSlug.isBlank();
    }
}
