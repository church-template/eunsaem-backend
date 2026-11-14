package com.elipair.church.domain.position;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.elipair.church.TestcontainersConfiguration;
import com.elipair.church.global.config.JpaConfig;
import com.elipair.church.global.tenant.TenantContext;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TestcontainersConfiguration.class, JpaConfig.class})
@TestPropertySource(properties = {"spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop"})
class PositionRepositoryTest {

    @Autowired
    private PositionRepository repository;

    @Test
    void findAll_orders_by_sort_order_asc() {
        repository.save(Position.of("장로", 20));
        repository.save(Position.of("목사", 10));

        List<Position> result = repository.findAllByOrderBySortOrderAsc();

        assertThat(result).extracting(Position::getName).containsExactly("목사", "장로");
    }

    @Test
    void existsByName_true_and_false() {
        repository.save(Position.of("권사", 10));

        assertThat(repository.existsByName("권사")).isTrue();
        assertThat(repository.existsByName("없는직분")).isFalse();
    }

    @Test
    void findMaxSortOrder_returns_max_or_empty() {
        assertThat(repository.findMaxSortOrder()).isEqualTo(Optional.empty());

        repository.save(Position.of("목사", 10));
        repository.save(Position.of("장로", 30));

        assertThat(repository.findMaxSortOrder()).contains(30);
    }

    @Test
    void duplicate_name_violates_unique_constraint() {
        repository.saveAndFlush(Position.of("목사", 10));

        assertThatThrownBy(() -> repository.saveAndFlush(Position.of("목사", 20)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void auditing_populates_created_at() {
        Position saved = repository.saveAndFlush(Position.of("집사", 10));
        assertThat(saved.getCreatedAt()).isNotNull();
    }

    /** 유니크는 교회 단위다(#86). 테넌트는 트랜잭션을 열 때 고정되므로 테스트 트랜잭션 밖에서 교회별로 저장한다. */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void same_name_coexists_across_churches() {
        try {
            TenantContext.runAs(1L, () -> repository.saveAndFlush(Position.of("목사", 1)));
            TenantContext.runAs(2L, () -> repository.saveAndFlush(Position.of("목사", 1)));

            assertThat(TenantContext.callAs(1L, () -> repository.findAll())).hasSize(1);
            assertThat(TenantContext.callAs(2L, () -> repository.findAll())).hasSize(1);
        } finally {
            TenantContext.runAs(1L, () -> repository.deleteAll());
            TenantContext.runAs(2L, () -> repository.deleteAll());
        }
    }
}
