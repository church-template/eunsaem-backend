package com.elipair.church.domain.department;

import static org.assertj.core.api.Assertions.assertThat;

import com.elipair.church.TestcontainersConfiguration;
import com.elipair.church.global.config.JpaConfig;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TestcontainersConfiguration.class, JpaConfig.class})
@TestPropertySource(properties = {"spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop"})
class DepartmentRepositoryTest {

    @Autowired
    private DepartmentRepository repository;

    private Department dept(String name, Long parentId, Integer sortOrder) {
        return Department.create(name, "본문", "김목사", parentId, sortOrder);
    }

    @Test
    void save_populates_audit_columns() {
        Department saved = repository.saveAndFlush(dept("부서", null, 10));
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getVersion()).isZero();
        assertThat(saved.getSortOrder()).isEqualTo(10);
    }

    @Test
    void findByIdAndDeletedAtIsNull_excludes_soft_deleted() {
        Department active = repository.saveAndFlush(dept("활성", null, 10));
        Department deleted = dept("삭제", null, 20);
        deleted.softDelete();
        Department savedDeleted = repository.saveAndFlush(deleted);

        assertThat(repository.findByIdAndDeletedAtIsNull(active.getId())).isPresent();
        assertThat(repository.findByIdAndDeletedAtIsNull(savedDeleted.getId())).isEmpty();
    }

    @Test
    void list_ordered_by_sort_order_then_id_excludes_deleted() {
        repository.saveAndFlush(dept("A", null, 20));
        repository.saveAndFlush(dept("B", null, 10)); // 먼저 저장 → id 작음
        repository.saveAndFlush(dept("C", null, 10)); // B와 동률 → id tie-break로 뒤
        Department del = dept("D", null, 5);
        del.softDelete();
        repository.saveAndFlush(del);

        List<String> names = repository.findByDeletedAtIsNullOrderBySortOrderAscIdAsc().stream()
                .map(Department::getName)
                .toList();
        assertThat(names).containsExactly("B", "C", "A"); // 10(B,id<C), 10(C), 20(A); 삭제(5) 제외
    }

    @Test
    void findMaxSortOrder_empty_when_no_active_rows() {
        assertThat(repository.findMaxSortOrder()).isEmpty();
    }

    @Test
    void findMaxSortOrder_returns_max_over_active_rows() {
        repository.saveAndFlush(dept("A", null, 10));
        repository.saveAndFlush(dept("B", null, 30));
        Department del = dept("D", null, 99);
        del.softDelete();
        repository.saveAndFlush(del);

        assertThat(repository.findMaxSortOrder()).contains(30); // 삭제행(99) 제외
    }

    @Test
    void exists_children_true_and_excludes_deleted_child() {
        Department parent = repository.saveAndFlush(dept("P", null, 10));
        Department child = repository.saveAndFlush(dept("C", parent.getId(), 10));
        assertThat(repository.existsByParentIdAndDeletedAtIsNull(parent.getId()))
                .isTrue();

        child.softDelete();
        repository.saveAndFlush(child);
        assertThat(repository.existsByParentIdAndDeletedAtIsNull(parent.getId()))
                .isFalse();
    }

    @Test
    void findReferencesByMedia_is_boundary_safe_and_maps_name_to_title() {
        repository.saveAndFlush(Department.create("42참조", "본문 ![](media:42) 끝", "김", null, 10));
        repository.saveAndFlush(Department.create("420참조", "본문 ![](media:420) 끝", "김", null, 20));

        List<DepartmentRefRow> rows = repository.findReferencesByMedia("media:42($|[^0-9])", 1L);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getTitle()).isEqualTo("42참조"); // title = name 별칭
    }

    @Test
    void lockHierarchy_executes_without_error() {
        // pg_advisory_xact_lock 네이티브 호출(반환 void 매핑 포함)이 정상 실행되는지 검증.
        // @DataJpaTest는 트랜잭션이라 lock은 롤백 시 자동 해제된다.
        repository.lockHierarchy(100_015L);
        assertThat(repository.findMaxSortOrder()).isEmpty(); // 호출 후 정상 진행 확인
    }
}
