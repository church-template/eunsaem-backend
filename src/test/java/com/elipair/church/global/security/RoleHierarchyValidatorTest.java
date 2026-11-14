package com.elipair.church.global.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.elipair.church.global.exception.BusinessException;
import com.elipair.church.global.exception.ErrorCode;
import org.junit.jupiter.api.Test;

class RoleHierarchyValidatorTest {

    private final RoleHierarchyValidator validator = new RoleHierarchyValidator();

    @Test
    void assignable_allows_equal_rejects_higher_priority() {
        // 같은 레벨은 허용(<=)
        assertThatCode(() -> validator.validateAssignable(900, 900)).doesNotThrowAnyException();
        // 초과는 거부
        assertThatThrownBy(() -> validator.validateAssignable(900, 1000))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.ACCESS_DENIED);
    }

    @Test
    void assignable_allows_strictly_lower_priority() {
        assertThatCode(() -> validator.validateAssignable(900, 899)).doesNotThrowAnyException();
    }

    @Test
    void grantable_rejects_equal_priority() {
        // 위임/박탈은 동급 차단 — ADMIN(900)은 ADMIN(900)을 위임할 수 없다.
        assertThatThrownBy(() -> validator.validateGrantable(900, 900))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.ACCESS_DENIED);
    }

    @Test
    void grantable_rejects_higher_priority() {
        assertThatThrownBy(() -> validator.validateGrantable(900, 1000))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.ACCESS_DENIED);
    }

    @Test
    void grantable_allows_strictly_lower_priority() {
        // SUPER_ADMIN(1000)은 ADMIN(900)을 위임할 수 있다.
        assertThatCode(() -> validator.validateGrantable(1000, 900)).doesNotThrowAnyException();
    }

    @Test
    void mutable_rejects_system_role_regardless_of_priority() {
        assertThatThrownBy(() -> validator.validateMutable(1000, 100, true)).isInstanceOf(BusinessException.class);
    }

    @Test
    void mutable_allows_non_system_lower_priority() {
        assertThatCode(() -> validator.validateMutable(1000, 100, false)).doesNotThrowAnyException();
    }

    @Test
    void mutable_allows_equal_priority_non_system() {
        assertThatCode(() -> validator.validateMutable(900, 900, false)).doesNotThrowAnyException();
    }

    @Test
    void mutable_rejects_higher_priority_non_system() {
        assertThatThrownBy(() -> validator.validateMutable(900, 1000, false))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.ACCESS_DENIED);
    }

    @Test
    void rejects_changing_own_role() {
        assertThatThrownBy(() -> validator.validateNotSelf(7L, 7L)).isInstanceOf(BusinessException.class);
        assertThatCode(() -> validator.validateNotSelf(7L, 8L)).doesNotThrowAnyException();
    }

    @Test
    void protects_last_super_admin() {
        assertThatThrownBy(() -> validator.validateNotLastSuperAdmin(true, 1)).isInstanceOf(BusinessException.class);
        assertThatCode(() -> validator.validateNotLastSuperAdmin(true, 2)).doesNotThrowAnyException();
        assertThatCode(() -> validator.validateNotLastSuperAdmin(false, 1)).doesNotThrowAnyException();
    }

    @Test
    void new_priority_must_be_strictly_below_requester() {
        org.assertj.core.api.Assertions.assertThatCode(() -> validator.validateNewPriority(900, 899))
                .doesNotThrowAnyException();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> validator.validateNewPriority(900, 900))
                .isInstanceOf(com.elipair.church.global.exception.BusinessException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> validator.validateNewPriority(900, 1000))
                .isInstanceOf(com.elipair.church.global.exception.BusinessException.class);
    }
}
