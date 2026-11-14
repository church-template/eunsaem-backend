package com.elipair.testfixture;

import com.elipair.church.global.tenant.TenantContext;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * 모든 테스트 스레드에 기본 교회를 깔아 두는 전역 JUnit 확장(#86).
 *
 * <p>테넌트가 없으면 DB 접근이 예외이므로, 교회를 의식하지 않는 기존 테스트가 그대로 돌게 한다.
 * {@code junit-platform.properties}의 자동 감지 + {@code META-INF/services}로 모든 테스트에 적용된다.
 * 기본 교회는 V18이 만든 첫 교회(slug {@code dev}, id 1)다. 리포지토리 슬라이스(create-drop)에는 churches 테이블이
 * 없지만 church_id가 엔티티 관계가 아니라 FK가 생기지 않으므로 같은 값으로 동작한다.
 *
 * <p>교회 간 격리를 검증하는 테스트는 이 기본 교회를 쓰지 않고 {@link TwoChurches}의 두 교회를 쓴다.
 */
public class TenantTestSupport implements BeforeAllCallback, BeforeEachCallback, AfterAllCallback {

    public static final long DEFAULT_CHURCH_ID = 1L;

    @Override
    public void beforeAll(ExtensionContext context) {
        TenantContext.set(DEFAULT_CHURCH_ID);
    }

    @Override
    public void beforeEach(ExtensionContext context) {
        TenantContext.set(DEFAULT_CHURCH_ID);
    }

    @Override
    public void afterAll(ExtensionContext context) {
        TenantContext.clear();
    }
}
