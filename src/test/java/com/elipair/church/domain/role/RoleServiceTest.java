package com.elipair.church.domain.role;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.elipair.church.domain.role.dto.RoleCreateRequest;
import com.elipair.church.domain.role.dto.RolePermissionsRequest;
import com.elipair.church.domain.role.dto.RoleResponse;
import com.elipair.church.domain.role.dto.RoleUpdateRequest;
import com.elipair.church.global.exception.BusinessException;
import com.elipair.church.global.exception.ErrorCode;
import com.elipair.church.global.security.RoleHierarchyValidator;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RoleServiceTest {

    @Mock
    private RoleRepository roleRepository;

    @Mock
    private PermissionRepository permissionRepository;

    // 실제 검증기 사용(순수 컴포넌트, <= 규칙).
    private final RoleHierarchyValidator hierarchyValidator = new RoleHierarchyValidator();

    private RoleService service() {
        return new RoleService(roleRepository, permissionRepository, hierarchyValidator);
    }

    @Test
    void create_forces_non_system_and_trims_name() {
        when(roleRepository.existsByName("EDITOR")).thenReturn(false);
        when(roleRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        service().create(new RoleCreateRequest("  EDITOR  ", 500, "편집자"), 1000);

        ArgumentCaptor<Role> captor = ArgumentCaptor.forClass(Role.class);
        verify(roleRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getName()).isEqualTo("EDITOR");
        assertThat(captor.getValue().isSystem()).isFalse();
    }

    @Test
    void create_rejects_equal_priority_to_requester() {
        // 같은 레벨(900)은 만들 수 없다 — 낮은 역할을 준 뒤 올리는 우회를 막으려면 생성·변경 모두 엄격히 낮아야 한다.
        assertThatThrownBy(() -> service().create(new RoleCreateRequest("PEER", 900, "동급"), 900))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(ErrorCode.ACCESS_DENIED));
    }

    @Test
    void create_allows_priority_just_below_requester() {
        when(roleRepository.existsByName("NEAR")).thenReturn(false);
        when(roleRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        service().create(new RoleCreateRequest("NEAR", 899, "바로 아래"), 900);

        verify(roleRepository).saveAndFlush(any());
    }

    @Test
    void create_rejects_priority_above_requester() {
        assertThatThrownBy(() -> service().create(new RoleCreateRequest("HIGH", 901, "상위"), 900))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(ErrorCode.ACCESS_DENIED));
        verify(roleRepository, never()).saveAndFlush(any());
    }

    @Test
    void create_blank_name_throws_invalid_input() {
        assertThatThrownBy(() -> service().create(new RoleCreateRequest("   ", 500, "x"), 1000))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_INPUT_VALUE));
        verify(roleRepository, never()).existsByName(any());
    }

    @Test
    void create_duplicate_name_throws() {
        when(roleRepository.existsByName("EDITOR")).thenReturn(true);

        assertThatThrownBy(() -> service().create(new RoleCreateRequest("EDITOR", 500, "x"), 1000))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(ErrorCode.DUPLICATE_RESOURCE));
        verify(roleRepository, never()).saveAndFlush(any());
    }

    @Test
    void update_partial_name_only_keeps_priority() {
        Role role = Role.create("EDITOR", 500, "편집자");
        when(roleRepository.findById(1L)).thenReturn(Optional.of(role));
        when(roleRepository.existsByName("AUTHOR")).thenReturn(false);
        when(roleRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        RoleResponse result = service().update(1L, new RoleUpdateRequest("AUTHOR", null, null), 1000);

        assertThat(result.name()).isEqualTo("AUTHOR");
        assertThat(result.priority()).isEqualTo(500);
    }

    @Test
    void update_system_role_is_rejected() {
        Role system = Role.create("X", 100, "x");
        org.springframework.test.util.ReflectionTestUtils.setField(system, "isSystem", true);
        when(roleRepository.findById(1L)).thenReturn(Optional.of(system));

        assertThatThrownBy(() -> service().update(1L, new RoleUpdateRequest("Y", null, null), 1000))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(ErrorCode.ACCESS_DENIED));
        verify(roleRepository, never()).saveAndFlush(any());
    }

    @Test
    void update_unknown_id_is_not_found() {
        when(roleRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().update(999L, new RoleUpdateRequest("Y", null, null), 1000))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }

    @Test
    void update_priority_above_requester_is_rejected() {
        Role role = Role.create("EDITOR", 500, "편집자");
        when(roleRepository.findById(1L)).thenReturn(Optional.of(role));

        assertThatThrownBy(() -> service().update(1L, new RoleUpdateRequest(null, 901, null), 900))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(ErrorCode.ACCESS_DENIED));
    }

    @Test
    void delete_non_system_within_level_calls_delete() {
        Role role = Role.create("EDITOR", 500, "편집자");
        when(roleRepository.findById(1L)).thenReturn(Optional.of(role));

        service().delete(1L, 1000);

        verify(roleRepository).delete(role);
    }

    @Test
    void delete_system_role_is_rejected() {
        Role system = Role.create("X", 100, "x");
        org.springframework.test.util.ReflectionTestUtils.setField(system, "isSystem", true);
        when(roleRepository.findById(1L)).thenReturn(Optional.of(system));

        assertThatThrownBy(() -> service().delete(1L, 1000))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(ErrorCode.ACCESS_DENIED));
        verify(roleRepository, never()).delete(any());
    }

    @Test
    void delete_unknown_id_is_not_found() {
        when(roleRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().delete(999L, 1000))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }

    @Test
    void setPermissions_replaces_with_known_keys() {
        Role role = Role.create("EDITOR", 500, "편집자");
        when(roleRepository.findById(1L)).thenReturn(Optional.of(role));
        when(permissionRepository.findByNameIn(any()))
                .thenReturn(List.of(Permission.of("SERMON_WRITE", "설교"), Permission.of("NOTICE_WRITE", "공지")));

        RoleResponse result =
                service().setPermissions(1L, new RolePermissionsRequest(List.of("SERMON_WRITE", "NOTICE_WRITE")), 1000);

        assertThat(result.permissions()).extracting("name").containsExactlyInAnyOrder("SERMON_WRITE", "NOTICE_WRITE");
    }

    @Test
    void setPermissions_dedups_duplicate_request() {
        Role role = Role.create("EDITOR", 500, "편집자");
        when(roleRepository.findById(1L)).thenReturn(Optional.of(role));
        when(permissionRepository.findByNameIn(any())).thenReturn(List.of(Permission.of("GALLERY_VIEW", "갤러리")));

        // 중복 요청이 미지 키 400으로 오인되지 않아야 함
        RoleResponse result =
                service().setPermissions(1L, new RolePermissionsRequest(List.of("GALLERY_VIEW", "GALLERY_VIEW")), 1000);

        assertThat(result.permissions()).extracting("name").containsExactly("GALLERY_VIEW");
    }

    @Test
    void setPermissions_unknown_key_is_invalid_input() {
        Role role = Role.create("EDITOR", 500, "편집자");
        when(roleRepository.findById(1L)).thenReturn(Optional.of(role));
        when(permissionRepository.findByNameIn(any())).thenReturn(List.of(Permission.of("SERMON_WRITE", "설교")));

        assertThatThrownBy(() ->
                        service().setPermissions(1L, new RolePermissionsRequest(List.of("SERMON_WRITE", "NOPE")), 1000))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_INPUT_VALUE));
    }

    @Test
    void update_rename_to_existing_name_is_rejected() {
        Role role = Role.create("EDITOR", 500, "편집자");
        when(roleRepository.findById(1L)).thenReturn(Optional.of(role));
        when(roleRepository.existsByName("AUTHOR")).thenReturn(true);

        assertThatThrownBy(() -> service().update(1L, new RoleUpdateRequest("AUTHOR", null, null), 1000))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(ErrorCode.DUPLICATE_RESOURCE));
        verify(roleRepository, never()).saveAndFlush(any());
    }

    @Test
    void setPermissions_unknown_id_is_not_found() {
        when(roleRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(
                        () -> service().setPermissions(999L, new RolePermissionsRequest(List.of("SERMON_WRITE")), 1000))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }
}
