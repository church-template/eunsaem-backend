package com.elipair.church.domain.role;

import com.elipair.church.domain.role.dto.RoleCreateRequest;
import com.elipair.church.domain.role.dto.RolePermissionsRequest;
import com.elipair.church.domain.role.dto.RoleResponse;
import com.elipair.church.domain.role.dto.RoleUpdateRequest;
import com.elipair.church.global.security.MemberPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 역할·권한 관리 API(스펙 §5.4). /api/admin/** 인증 + ROLE_MANAGE(클래스 레벨). */
@Tag(name = "역할", description = "역할 목록·생성·수정·삭제·권한 일괄설정 API(스펙 §5.4). 전 메서드 ROLE_MANAGE 필요.")
@RestController
@RequestMapping("/api/admin/roles")
@PreAuthorize("hasAuthority('ROLE_MANAGE')")
public class RoleController {

    private final RoleService service;

    public RoleController(RoleService service) {
        this.service = service;
    }

    @Operation(summary = "역할 목록", description = """
            전체 역할을 priority 내림차순으로 조회한다(비페이징 평배열).

            - 인증(JWT): 필요 — `ROLE_MANAGE`
            - 반환값: `List<RoleResponse>` — 각 역할의 id·name·priority·`isSystem`·description·할당 권한(`permissions`) 목록
            """)
    @GetMapping
    public List<RoleResponse> list() {
        return service.list();
    }

    @Operation(summary = "역할 생성", description = """
            새 역할(권한 묶음 + priority 위계)을 생성한다. API 생성 역할은 `isSystem=false` 고정.

            - 인증(JWT): 필요 — `ROLE_MANAGE`
            - 요청 본문: `RoleCreateRequest` — `name`(필수, ≤50)·`priority`(필수)·`description`(선택, ≤255)
            - 반환값: `RoleResponse` — 생성된 역할(201 Created); 권한 미할당 상태
            - 부수효과: 위계검증 — `priority`가 요청자 maxPriority **이상**이면 403(요청자보다 엄격히 낮아야 함 — 같은 레벨 불가) · `name` 중복 시 409 DUPLICATE_RESOURCE
            """)
    @PostMapping
    public ResponseEntity<RoleResponse> create(
            @Valid @RequestBody RoleCreateRequest request, @AuthenticationPrincipal MemberPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request, principal.maxPriority()));
    }

    @Operation(summary = "역할 수정", description = """
            역할의 name·priority·description을 부분 수정한다(PATCH). null 필드는 미변경.

            - 인증(JWT): 필요 — `ROLE_MANAGE`
            - 경로 변수: `id` — 수정할 역할 ID
            - 요청 본문: `RoleUpdateRequest` — `name`(선택, ≤50)·`priority`(선택)·`description`(선택, ≤255)
            - 반환값: `RoleResponse` — 수정된 역할
            - 부수효과: 위계검증 — 대상 역할 `priority`가 요청자 maxPriority 초과 시 403 · `is_system` 역할은 수정 불가(403) · `priority`를 다른 값으로 바꿀 때 새 값은 maxPriority보다 엄격히 낮아야 함(같은 레벨 불가, 같은 값 재전송은 변경이 아님) · `name` 중복 시 409 DUPLICATE_RESOURCE
            """)
    @PatchMapping("/{id}")
    public RoleResponse update(
            @PathVariable Long id,
            @Valid @RequestBody RoleUpdateRequest request,
            @AuthenticationPrincipal MemberPrincipal principal) {
        return service.update(id, request, principal.maxPriority());
    }

    @Operation(summary = "역할 삭제", description = """
            역할을 물리 삭제한다(soft delete 아님). role_permissions 연결은 DB CASCADE로 함께 제거.

            - 인증(JWT): 필요 — `ROLE_MANAGE`
            - 경로 변수: `id` — 삭제할 역할 ID
            - 반환값: 없음(204)
            - 부수효과: 위계검증 — 대상 역할 `priority`가 요청자 maxPriority 초과 시 403 · `is_system` 역할은 삭제 불가(403) · 멤버에게 할당된 역할이면 409 ROLE_IN_USE(member_roles FK RESTRICT)
            """)
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id, @AuthenticationPrincipal MemberPrincipal principal) {
        service.delete(id, principal.maxPriority());
    }

    @Operation(summary = "역할 권한 일괄설정", description = """
            역할에 할당된 권한을 요청 목록으로 전체 교체한다(PUT 시맨틱). 중복 이름은 흡수.

            - 인증(JWT): 필요 — `ROLE_MANAGE`
            - 경로 변수: `id` — 대상 역할 ID
            - 요청 본문: `RolePermissionsRequest` — `permissions`(권한 **이름** 문자열 배열, 각 ≤50); 빈 배열이면 전 권한 회수
            - 반환값: `RoleResponse` — 권한이 교체된 역할
            - 부수효과: 위계검증 — 대상 역할 `priority`가 요청자 maxPriority 초과 또는 `is_system`이면 403 · 존재하지 않는 권한 이름이 포함되면 400 INVALID_INPUT_VALUE
            """)
    @PutMapping("/{id}/permissions")
    public RoleResponse setPermissions(
            @PathVariable Long id,
            @Valid @RequestBody RolePermissionsRequest request,
            @AuthenticationPrincipal MemberPrincipal principal) {
        return service.setPermissions(id, request, principal.maxPriority());
    }
}
