package com.elipair.church.global.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TenantContextTest {

    /** 전역 확장(TenantTestSupport)이 깔아 둔 기본 교회를 걷어 낸다 — 이 테스트는 "값 없음"에서 시작해야 한다. */
    @BeforeEach
    void clearDefaultTenant() {
        TenantContext.clear();
    }

    @Test
    void require_없으면_예외() {
        assertThatThrownBy(TenantContext::require).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void runAs_끝나면_이전값_복원() {
        TenantContext.runAs(1L, () -> {
            TenantContext.runAs(2L, () -> assertThat(TenantContext.require()).isEqualTo(2L));
            assertThat(TenantContext.require()).isEqualTo(1L);
        });
        assertThat(TenantContext.currentOrNull()).isNull();
    }

    @Test
    void runAs_예외가_나도_복원() {
        assertThatThrownBy(() -> TenantContext.runAs(1L, () -> {
                    throw new IllegalArgumentException("boom");
                }))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(TenantContext.currentOrNull()).isNull();
    }

    @Test
    void callAs는_값을_돌려준다() {
        assertThat(TenantContext.callAs(7L, TenantContext::require)).isEqualTo(7L);
        assertThat(TenantContext.currentOrNull()).isNull();
    }
}
