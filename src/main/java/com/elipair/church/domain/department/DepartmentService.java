package com.elipair.church.domain.department;

import com.elipair.church.domain.department.dto.DepartmentCardResponse;
import com.elipair.church.domain.department.dto.DepartmentCreateRequest;
import com.elipair.church.domain.department.dto.DepartmentDetailResponse;
import com.elipair.church.domain.department.dto.DepartmentPatchRequest;
import com.elipair.church.domain.department.dto.DepartmentUpdateRequest;
import com.elipair.church.global.exception.BusinessException;
import com.elipair.church.global.exception.ErrorCode;
import com.elipair.church.global.tenant.TenantContext;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 부서 서비스(스펙 §5.8). 계층(parentId 자기참조)·차단형 삭제·낙관락. 태그/작성자/조회수 없음(설계 §1).
 * 계층 변경(create-with-parent/update/patch-with-parent/delete)은 advisory lock으로 직렬화해
 * 동시 write-skew(사이클·삭제부모-자식)를 차단한다(설계 §5.3). 낙관락은 명시적 version 비교 + flush로 응답 version 정합.
 */
@Service
@Transactional(readOnly = true)
public class DepartmentService {

    /** 계층 변경 직렬화용 고정 advisory lock 키(설계 §5.3). 부서(#15) 전용 임의 상수. */
    private static final long HIERARCHY_LOCK_KEY = 100_015L;
    /** sort_order 미지정 시 append 간격(positions 선례). */
    private static final int SORT_ORDER_GAP = 10;

    private final DepartmentRepository repository;

    public DepartmentService(DepartmentRepository repository) {
        this.repository = repository;
    }

    /** 공개 목록 — 비페이징 평배열(positions/tags 동급, 설계 §1). 프론트가 parentId로 트리를 조립한다. */
    public List<DepartmentCardResponse> list() {
        return repository.findByDeletedAtIsNullOrderBySortOrderAscIdAsc().stream()
                .map(d -> new DepartmentCardResponse(
                        d.getId(), d.getName(), d.getLeader(), d.getParentId(), d.getSortOrder()))
                .toList();
    }

    public DepartmentDetailResponse get(Long id) {
        return detail(load(id));
    }

    @Transactional
    public DepartmentDetailResponse create(DepartmentCreateRequest req) {
        if (req.parentId() != null) {
            repository.lockHierarchy(hierarchyLockKey()); // 부모 검증/삭제 경합 직렬화(설계 §5.3)
        }
        validateParent(null, req.parentId());
        int sortOrder = req.sortOrder() != null
                ? req.sortOrder()
                : repository.findMaxSortOrder().map(max -> max + SORT_ORDER_GAP).orElse(SORT_ORDER_GAP);
        Department saved = repository.save(
                Department.create(req.name(), req.description(), req.leader(), req.parentId(), sortOrder));
        return detail(saved);
    }

    @Transactional
    public DepartmentDetailResponse update(Long id, DepartmentUpdateRequest req) {
        repository.lockHierarchy(hierarchyLockKey()); // PUT은 항상 parentId 세팅 → reparent 가능(설계 §5.3)
        Department dept = load(id);
        checkVersion(dept, req.version());
        validateParent(id, req.parentId());
        dept.update(req.name(), req.description(), req.leader(), req.parentId(), req.sortOrder());
        repository.flush(); // 엔티티 필드 변경분의 버전 UPDATE 즉시 반영(응답 version 정합)
        return detail(dept);
    }

    @Transactional
    public DepartmentDetailResponse patch(Long id, DepartmentPatchRequest req) {
        if (req.parentId() != null) {
            repository.lockHierarchy(hierarchyLockKey()); // 부모 변경 시에만 직렬화(설계 §5.3)
        }
        Department dept = load(id);
        checkVersion(dept, req.version());
        if (req.parentId() != null) {
            validateParent(id, req.parentId());
        }
        dept.applyPatch(req.name(), req.description(), req.leader(), req.parentId(), req.sortOrder());
        repository.flush();
        return detail(dept);
    }

    @Transactional
    public void delete(Long id) {
        repository.lockHierarchy(hierarchyLockKey()); // 자식 존재 검사 vs 동시 create-child 경합 직렬화(설계 §5.3)
        Department dept = load(id);
        if (repository.existsByParentIdAndDeletedAtIsNull(id)) {
            throw new BusinessException(ErrorCode.DEPARTMENT_HAS_CHILDREN);
        }
        dept.softDelete();
    }

    private Department load(Long id) {
        return repository
                .findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    private void checkVersion(Department dept, Long expected) {
        if (!dept.getVersion().equals(expected)) {
            throw new BusinessException(ErrorCode.OPTIMISTIC_LOCK_CONFLICT);
        }
    }

    /**
     * parent 존재·미삭제·자기참조·순환 검증(설계 §5.1). parentId=null이면 즉시 통과.
     * 순환: newParent의 parent_id 체인을 루트까지 상향 탐색하다 selfId를 만나면 거부(트리라 부모 1개, O(depth)).
     */
    private void validateParent(Long selfId, Long parentId) {
        if (parentId == null) {
            return;
        }
        if (selfId != null && parentId.equals(selfId)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE, "자기 자신을 상위 부서로 지정할 수 없습니다");
        }
        Department parent = repository
                .findByIdAndDeletedAtIsNull(parentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_INPUT_VALUE, "존재하지 않는 상위 부서입니다"));
        if (selfId != null) {
            Set<Long> visited = new HashSet<>();
            Long ancestor = parent.getParentId();
            while (ancestor != null && visited.add(ancestor)) { // visited는 방어적 무한루프 차단
                if (ancestor.equals(selfId)) {
                    throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE, "하위 부서를 상위 부서로 지정할 수 없습니다");
                }
                ancestor = repository
                        .findByIdAndDeletedAtIsNull(ancestor)
                        .map(Department::getParentId)
                        .orElse(null);
            }
        }
    }

    private DepartmentDetailResponse detail(Department d) {
        return new DepartmentDetailResponse(
                d.getId(),
                d.getName(),
                d.getDescription(),
                d.getLeader(),
                d.getParentId(),
                d.getSortOrder(),
                d.getCreatedAt(),
                d.getUpdatedAt(),
                d.getVersion());
    }

    /** 계층 락 키에 교회 id를 섞는다(#86) — 교회끼리 서로의 부서 변경을 기다리지 않게 한다. */
    private static long hierarchyLockKey() {
        return HIERARCHY_LOCK_KEY * 1_000_003L + TenantContext.require();
    }
}
