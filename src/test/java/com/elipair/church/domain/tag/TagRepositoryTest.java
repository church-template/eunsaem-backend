package com.elipair.church.domain.tag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.elipair.church.TestcontainersConfiguration;
import com.elipair.church.global.config.JpaConfig;
import com.elipair.church.global.tenant.TenantContext;
import java.util.List;
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
class TagRepositoryTest {

    @Autowired
    private TagRepository repository;

    @Test
    void findAll_orders_by_name_asc() {
        repository.save(Tag.create("선교"));
        repository.save(Tag.create("봉사"));
        repository.save(Tag.create("예배"));

        List<Tag> result = repository.findAllByOrderByNameAsc();

        assertThat(result).extracting(Tag::getName).containsExactly("봉사", "선교", "예배");
    }

    @Test
    void existsByName_true_and_false() {
        repository.save(Tag.create("예배"));

        assertThat(repository.existsByName("예배")).isTrue();
        assertThat(repository.existsByName("없는태그")).isFalse();
    }

    @Test
    void existsByNameAndIdNot_excludes_self() {
        Tag saved = repository.save(Tag.create("예배"));

        assertThat(repository.existsByNameAndIdNot("예배", saved.getId())).isFalse();
        assertThat(repository.existsByNameAndIdNot("예배", saved.getId() + 1)).isTrue();
    }

    @Test
    void duplicate_name_violates_unique_constraint() {
        repository.saveAndFlush(Tag.create("예배"));

        assertThatThrownBy(() -> repository.saveAndFlush(Tag.create("예배")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void auditing_populates_created_at() {
        Tag saved = repository.saveAndFlush(Tag.create("예배"));
        assertThat(saved.getCreatedAt()).isNotNull();
    }

    /** 유니크는 교회 단위다(#86). 테넌트는 트랜잭션을 열 때 고정되므로 테스트 트랜잭션 밖에서 교회별로 저장한다. */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void same_name_coexists_across_churches() {
        try {
            TenantContext.runAs(1L, () -> repository.saveAndFlush(Tag.create("주일예배")));
            TenantContext.runAs(2L, () -> repository.saveAndFlush(Tag.create("주일예배")));

            assertThat(TenantContext.callAs(1L, () -> repository.findAll())).hasSize(1);
            assertThat(TenantContext.callAs(2L, () -> repository.findAll())).hasSize(1);
        } finally {
            TenantContext.runAs(1L, () -> repository.deleteAll());
            TenantContext.runAs(2L, () -> repository.deleteAll());
        }
    }
}
