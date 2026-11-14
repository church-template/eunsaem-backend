package com.elipair.church.global.tenant;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/** 기본 교회 slug 오타는 헬스가 초록인 채 전 요청을 404로 만든다 — 기동 실패로 드러나야 한다(#86). */
class TenantStartupCheckTest {

    private final ChurchResolver resolver = mock(ChurchResolver.class);

    @Test
    void 기본_slug가_등록되지_않았으면_기동_실패() {
        when(resolver.findBySlug("nope")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> new TenantStartupCheck(resolver, new TenantProperties("nope")).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("nope");
    }

    @Test
    void 기본_slug가_등록돼_있으면_통과() {
        when(resolver.findBySlug("dev"))
                .thenReturn(Optional.of(new ChurchInfo(1L, "dev", "개발교회", "http://localhost:3000")));
        assertThatCode(() -> new TenantStartupCheck(resolver, new TenantProperties("dev")).run(null))
                .doesNotThrowAnyException();
    }

    @Test
    void 기본_slug가_비어_있으면_검사하지_않는다() {
        assertThatCode(() -> new TenantStartupCheck(resolver, new TenantProperties("")).run(null))
                .doesNotThrowAnyException();
        assertThatCode(() -> new TenantStartupCheck(resolver, new TenantProperties(null)).run(null))
                .doesNotThrowAnyException();
    }
}
