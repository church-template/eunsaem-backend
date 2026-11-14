package com.elipair.church.domain.role;

import com.elipair.church.domain.role.dto.RoleCreateRequest;
import com.elipair.church.domain.role.dto.RolePermissionsRequest;
import com.elipair.church.domain.role.dto.RoleResponse;
import com.elipair.church.domain.role.dto.RoleUpdateRequest;
import com.elipair.church.global.exception.BusinessException;
import com.elipair.church.global.exception.ErrorCode;
import com.elipair.church.global.security.RoleHierarchyValidator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class RoleService {

    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;
    private final RoleHierarchyValidator hierarchyValidator;

    public RoleService(
            RoleRepository roleRepository,
            PermissionRepository permissionRepository,
            RoleHierarchyValidator hierarchyValidator) {
        this.roleRepository = roleRepository;
        this.permissionRepository = permissionRepository;
        this.hierarchyValidator = hierarchyValidator;
    }

    public List<RoleResponse> list() {
        return roleRepository.findAllByOrderByPriorityDesc().stream()
                .map(RoleResponse::from)
                .toList();
    }

    @Transactional
    public RoleResponse create(RoleCreateRequest request, int requesterMaxPriority) {
        String name = normalizeName(request.name());
        hierarchyValidator.validateNewPriority(requesterMaxPriority, request.priority());
        if (roleRepository.existsByName(name)) {
            throw new BusinessException(ErrorCode.DUPLICATE_RESOURCE);
        }
        return persist(Role.create(name, request.priority(), normalizeDescription(request.description())));
    }

    @Transactional
    public void delete(Long id, int requesterMaxPriority) {
        Role role = roleRepository.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        hierarchyValidator.validateMutable(requesterMaxPriority, role.getPriority(), role.isSystem());
        try {
            roleRepository.delete(role);
            roleRepository.flush(); // member_roles FK RESTRICT 위반을 지금 터뜨려 친절한 409로 번역.
            // NOTE: roles를 참조하는 RESTRICT FK는 member_roles뿐이라(role_permissions는 CASCADE) 여기서 잡히는
            //       무결성 위반은 곧 "할당된 역할"이다. roles에 다른 제약(CHECK·추가 FK 등) 추가 시 이 분기를 재검토할 것.
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.ROLE_IN_USE);
        }
    }

    @Transactional
    public RoleResponse setPermissions(Long id, RolePermissionsRequest request, int requesterMaxPriority) {
        Role role = roleRepository.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        hierarchyValidator.validateMutable(requesterMaxPriority, role.getPriority(), role.isSystem());

        // 중복은 흡수, 미지 키만 거부.
        Set<String> names = new LinkedHashSet<>(request.permissions());
        List<Permission> found = permissionRepository.findByNameIn(names);
        if (found.size() != names.size()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE, "존재하지 않는 권한이 포함되어 있습니다");
        }
        role.replacePermissions(found);
        return RoleResponse.from(role);
    }

    @Transactional
    public RoleResponse update(Long id, RoleUpdateRequest request, int requesterMaxPriority) {
        Role role = roleRepository.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        hierarchyValidator.validateMutable(requesterMaxPriority, role.getPriority(), role.isSystem());

        String name = null;
        if (request.name() != null) {
            name = normalizeName(request.name());
            if (!name.equals(role.getName()) && roleRepository.existsByName(name)) {
                throw new BusinessException(ErrorCode.DUPLICATE_RESOURCE);
            }
        }
        // priority를 실제로 바꿀 때만 검사한다 — 동급 역할의 이름·설명만 고치면서 같은 값을 그대로 보내는 것은 허용.
        if (request.priority() != null && request.priority() != role.getPriority()) {
            hierarchyValidator.validateNewPriority(requesterMaxPriority, request.priority());
        }
        role.update(name, request.priority(), normalizeDescription(request.description()));
        return persist(role);
    }

    // name UNIQUE 경합 백스톱(Position 패턴): 선검사를 빠져나간 동시 생성/수정을 saveAndFlush로 잡는다.
    private RoleResponse persist(Role role) {
        try {
            return RoleResponse.from(roleRepository.saveAndFlush(role));
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.DUPLICATE_RESOURCE);
        }
    }

    private String normalizeName(String raw) {
        if (raw == null) {
            throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE, "역할 이름은 필수입니다");
        }
        String trimmed = raw.trim();
        if (trimmed.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE, "역할 이름은 공백일 수 없습니다");
        }
        return trimmed;
    }

    private String normalizeDescription(String raw) {
        return raw == null ? null : raw.trim();
    }
}
