package com.elipair.church.global.security;

import com.elipair.church.global.exception.BusinessException;
import com.elipair.church.global.exception.ErrorCode;
import org.springframework.stereotype.Component;

/**
 * priority 기반 위계 검증(스펙 §4.3). DB 의존 없는 순수 컴포넌트 — 카운트·플래그는 호출자(role·member 도메인)가 주입한다.
 * 위반 시 BusinessException(ACCESS_DENIED). 호출자는 D3·D4에서 등장한다.
 */
@Component
public class RoleHierarchyValidator {

    /** 대상 priority가 요청자 maxPriority 이하여야 한다(같은 레벨 허용, 초과만 escalation 차단). 기존 역할·회원을 관리할 때 쓴다. */
    public void validateAssignable(int requesterMaxPriority, int targetPriority) {
        if (targetPriority > requesterMaxPriority) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED, "대상 역할의 priority가 요청자 권한을 초과합니다");
        }
    }

    /**
     * 역할의 priority를 <b>정할 때</b>(생성, 또는 다른 값으로 변경): 새 priority가 요청자 maxPriority보다 엄격히 낮아야 한다.
     * 같아도 되면 위임 규칙({@link #validateGrantable})이 우회된다 — 낮은 역할을 회원에게 준 뒤 그 역할을 자기 등급까지 올리면
     * 동급 관리자를 만들 수 있다. 요청자가 만들거나 올린 역할은 항상 요청자보다 아래라, 동급·상위 관리자가 회수할 수 있다.
     */
    public void validateNewPriority(int requesterMaxPriority, int newPriority) {
        if (newPriority >= requesterMaxPriority) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED, "역할의 priority는 요청자보다 낮게만 정할 수 있습니다");
        }
    }

    /**
     * 회원에게 역할 위임/박탈: 대상 역할 priority가 요청자 maxPriority보다 <b>엄격히 낮아야</b> 한다(동급 차단).
     * 상위 역할만 하위 역할을 위임/박탈할 수 있다 — 예: SUPER_ADMIN(1000)은 ADMIN(900)을 위임하나, ADMIN(900)은 ADMIN(900)을 위임 못 한다.
     * 최상위 역할(SUPER_ADMIN)은 위에 아무것도 없으므로 API 위임 불가 — 시드/DB로만 구성한다.
     */
    public void validateGrantable(int requesterMaxPriority, int targetPriority) {
        if (targetPriority >= requesterMaxPriority) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED, "대상 역할은 요청자와 동급이거나 상위라 위임/박탈할 수 없습니다");
        }
    }

    /** 역할 수정/삭제/권한변경: is_system 보호 + priority 가드. */
    public void validateMutable(int requesterMaxPriority, int targetPriority, boolean targetIsSystem) {
        if (targetIsSystem) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED, "시스템 역할은 수정·삭제할 수 없습니다");
        }
        validateAssignable(requesterMaxPriority, targetPriority);
    }

    /** 자기 자신의 역할은 부여/회수할 수 없다. */
    public void validateNotSelf(long requesterMemberId, long targetMemberId) {
        if (requesterMemberId == targetMemberId) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED, "자신의 역할은 변경할 수 없습니다");
        }
    }

    /** 마지막 SUPER_ADMIN 회수·강등·삭제 금지. */
    public void validateNotLastSuperAdmin(boolean targetIsSuperAdmin, long superAdminCount) {
        if (targetIsSuperAdmin && superAdminCount <= 1) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED, "마지막 SUPER_ADMIN은 회수·강등·삭제할 수 없습니다");
        }
    }
}
