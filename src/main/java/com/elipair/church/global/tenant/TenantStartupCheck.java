package com.elipair.church.global.tenant;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * tenant.default-slug가 등록되지 않은 교회를 가리키면 기동을 실패시킨다(#86).
 *
 * <p>헬스 체크(/actuator/health)는 교회 없이 통과하므로, 오타가 나면 배포는 성공으로 끝나는데
 * 접두사 없는 /api/** 요청이 전부 404가 된다. 기동 실패로 바꿔 배포 헬스 게이트가 잡게 한다.
 */
@Component
public class TenantStartupCheck implements ApplicationRunner {

    private final ChurchResolver resolver;
    private final TenantProperties properties;

    public TenantStartupCheck(ChurchResolver resolver, TenantProperties properties) {
        this.resolver = resolver;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (properties.hasDefaultSlug()
                && resolver.findBySlug(properties.defaultSlug()).isEmpty()) {
            throw new IllegalStateException("tenant.default-slug(TENANT_DEFAULT_SLUG)='" + properties.defaultSlug()
                    + "'에 해당하는 교회가 churches에 없습니다. slug를 확인하거나 값을 비우세요.");
        }
    }
}
