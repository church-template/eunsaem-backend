package com.elipair.church.domain.department;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DepartmentRepository extends JpaRepository<Department, Long> {

    Optional<Department> findByIdAndDeletedAtIsNull(Long id);

    /** 공개 목록 — 미삭제만, sort_order ASC, 동률 시 id ASC(결정적 정렬). */
    List<Department> findByDeletedAtIsNullOrderBySortOrderAscIdAsc();

    /** 삭제 차단용 — 살아있는 자식 존재 여부(soft-deleted 자식 제외). */
    boolean existsByParentIdAndDeletedAtIsNull(Long parentId);

    /** create append 기준값(미삭제 행 최대 sort_order). 없으면 서비스가 10으로 시작. */
    @Query("select max(d.sortOrder) from Department d where d.deletedAt is null")
    Optional<Integer> findMaxSortOrder();

    /**
     * 본문(description)이 media:{id}를 참조하는 미삭제 부서(id·name→title). PG 정규식 ~ 로 경계 안전 매칭.
     * pattern 예: "media:42($|[^0-9])" — 42가 media:420/421에 매칭되지 않는다.
     */
    @Query(
            value =
                    "select id as id, name as title from departments where church_id = :churchId and deleted_at is null and description ~ :pattern",
            nativeQuery = true)
    List<DepartmentRefRow> findReferencesByMedia(@Param("pattern") String pattern, @Param("churchId") long churchId);

    /**
     * 계층 변경 트랜잭션 직렬화(설계 §5.3). pg_advisory_xact_lock은 트랜잭션 종료 시 자동 해제(수동 unlock 불필요).
     * 동시 reparent(write-skew 사이클)·delete-parent vs create-child 경합을 단일 락으로 막는다.
     */
    @Query(value = "select pg_advisory_xact_lock(:key)", nativeQuery = true)
    void lockHierarchy(@Param("key") long key);
}
