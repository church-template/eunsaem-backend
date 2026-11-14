package com.elipair.church.global.tenant;

import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;

/**
 * Hibernate가 세션을 열 때마다 묻는 "현재 교회"(#86).
 *
 * <p>fail-closed: 교회가 정해지지 않았으면 예외를 던진다. "전체 교회를 보는" 값은 없다.
 *
 * <p>예외는 기동 중뿐이다. Spring Data가 {@code @Query} 검증을 위해 기동 중에 세션을 여는데 이때는 교회가 없다.
 * 항상 예외를 던지면 앱이 뜨지 않으므로, 컨텍스트 기동이 끝나기 전에는 존재하지 않는 교회 id를 돌려준다.
 * 그 id의 교회는 없으므로 어떤 행도 보이지 않고, 저장은 FK 위반으로 실패한다.
 * 기동 여부는 인스턴스 필드에 둔다(static이면 테스트에서 뜨는 여러 컨텍스트가 서로 영향을 준다).
 */
public class TenantIdentifierResolver
        implements CurrentTenantIdentifierResolver<Long>, ApplicationListener<ContextRefreshedEvent> {

    /** 존재하지 않는 교회. churches.id는 IDENTITY라 음수가 없다. */
    static final long NO_CHURCH = -1L;

    private volatile boolean started;

    @Override
    public void onApplicationEvent(ContextRefreshedEvent event) {
        started = true;
    }

    @Override
    public Long resolveCurrentTenantIdentifier() {
        Long churchId = TenantContext.currentOrNull();
        if (churchId != null) {
            return churchId;
        }
        if (!started) {
            return NO_CHURCH;
        }
        return TenantContext.require(); // 예외
    }

    @Override
    public boolean validateExistingCurrentSessions() {
        return true;
    }
}
