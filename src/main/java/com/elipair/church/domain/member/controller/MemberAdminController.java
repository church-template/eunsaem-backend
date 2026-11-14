package com.elipair.church.domain.member.controller;

import com.elipair.church.domain.member.MemberRoleService;
import com.elipair.church.domain.member.MemberService;
import com.elipair.church.domain.member.dto.AdminMemberUpdateRequest;
import com.elipair.church.domain.member.dto.MemberDetailResponse;
import com.elipair.church.domain.member.dto.PositionAssignRequest;
import com.elipair.church.domain.member.dto.ResetPasswordResponse;
import com.elipair.church.domain.member.dto.RoleGrantRequest;
import com.elipair.church.global.security.MemberPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 관리자 회원 관리(스펙 §5.2). /api/admin/** 인증 + 메서드별 권한. */
@Tag(name = "회원(관리)")
@RestController
@RequestMapping("/api/admin/members")
public class MemberAdminController {

    private final MemberService memberService;
    private final MemberRoleService memberRoleService;

    public MemberAdminController(MemberService memberService, MemberRoleService memberRoleService) {
        this.memberService = memberService;
        this.memberRoleService = memberRoleService;
    }

    @Operation(summary = "회원 정보 수정", description = """
                    관리자가 대상 회원의 프로필을 부분 수정(PATCH). 번호 변경 구제 등. 비밀번호는 reset-password로만 변경.

                    - 인증(JWT): 필요 — `MEMBER_MANAGE`
                    - 경로 변수: `uuid` — 수정할 회원 uuid
                    - 요청 본문: `AdminMemberUpdateRequest` — 이름·전화번호·이메일 (모두 선택)
                    - 반환값: `MemberDetailResponse` — 수정된 회원 상세
                    - 부수효과: 소프트 삭제 회원은 조회되지 않아 404 · 전화번호 자기 제외 중복 시 409 DUPLICATE_RESOURCE
                    """)
    @PatchMapping("/{uuid}")
    @PreAuthorize("hasAuthority('MEMBER_MANAGE')")
    public MemberDetailResponse update(
            @PathVariable UUID uuid,
            @Valid @RequestBody AdminMemberUpdateRequest request,
            @AuthenticationPrincipal MemberPrincipal principal) {
        return memberService.adminUpdate(uuid, request, principal.maxPriority());
    }

    @Operation(summary = "직분 부여/해제", description = """
                    대상 회원의 직분을 지정·변경하거나 해제한다(`positionId`가 null이면 해제).

                    - 인증(JWT): 필요 — `MEMBER_MANAGE`
                    - 경로 변수: `uuid` — 대상 회원 uuid
                    - 요청 본문: `PositionAssignRequest` — `positionId`(null이면 직분 해제)
                    - 반환값: `MemberDetailResponse` — 직분 반영된 회원 상세
                    - 부수효과: 직분은 권한과 무관(위계 검증 없음) · 회원/직분 미존재 시 404
                    """)
    @PutMapping("/{uuid}/position")
    @PreAuthorize("hasAuthority('MEMBER_MANAGE')")
    public MemberDetailResponse changePosition(
            @PathVariable UUID uuid,
            @RequestBody PositionAssignRequest request,
            @AuthenticationPrincipal MemberPrincipal principal) {
        return memberService.changePosition(uuid, request.positionId(), principal.maxPriority());
    }

    @Operation(summary = "비밀번호 초기화", description = """
                    대상 회원의 비밀번호를 임시 값으로 재설정하고 평문을 1회 반환(관리자가 대면 전달).

                    - 인증(JWT): 필요 — `MEMBER_MANAGE`
                    - 경로 변수: `uuid` — 대상 회원 uuid
                    - 반환값: `ResetPasswordResponse` — `temporaryPassword`(평문, 로그·예외에 미노출)
                    - 부수효과: 대상 회원 비밀번호를 임시값(BCrypt 해시)으로 교체
                    """)
    @PostMapping("/{uuid}/reset-password")
    @PreAuthorize("hasAuthority('MEMBER_MANAGE')")
    public ResetPasswordResponse resetPassword(
            @PathVariable UUID uuid, @AuthenticationPrincipal MemberPrincipal principal) {
        return memberService.resetPassword(uuid, principal.maxPriority());
    }

    @Operation(summary = "역할 부여", description = """
                    대상 회원에게 역할 부여(멱등; 이미 보유 시 no-op). `MEMBER` 부여가 곧 교인 승인이다.

                    - 인증(JWT): 필요 — `ROLE_MANAGE`
                    - 경로 변수: `uuid` — 대상 회원 uuid
                    - 요청 본문: `RoleGrantRequest` — `roleId`
                    - 반환값: `MemberDetailResponse` — 역할 반영된 회원 상세
                    - 부수효과: 요청자 maxPriority보다 높은 priority 역할이면 403(에스컬레이션 방지) · 자기 자신에게 부여 불가(403)
                    """)
    @PostMapping("/{uuid}/roles")
    @PreAuthorize("hasAuthority('ROLE_MANAGE')")
    public MemberDetailResponse grantRole(
            @PathVariable UUID uuid,
            @Valid @RequestBody RoleGrantRequest request,
            @AuthenticationPrincipal MemberPrincipal principal) {
        return memberRoleService.grant(uuid, request.roleId(), principal.id(), principal.maxPriority());
    }

    @Operation(summary = "역할 회수", description = """
                    대상 회원의 역할 회수(204; 멱등 — 미보유 시 no-op).

                    - 인증(JWT): 필요 — `ROLE_MANAGE`
                    - 경로 변수: `uuid` — 대상 회원 uuid; `roleId` — 회수할 역할 ID
                    - 반환값: 없음(204)
                    - 부수효과: 요청자 maxPriority보다 높은 priority면 403 · 자기 자신 회수 불가(403) · 마지막 활성 SUPER_ADMIN 회수 시 차단(403)
                    """)
    @DeleteMapping("/{uuid}/roles/{roleId}")
    @PreAuthorize("hasAuthority('ROLE_MANAGE')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeRole(
            @PathVariable UUID uuid, @PathVariable Long roleId, @AuthenticationPrincipal MemberPrincipal principal) {
        memberRoleService.revoke(uuid, roleId, principal.id(), principal.maxPriority());
    }
}
