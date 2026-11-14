package com.elipair.church;

import static org.assertj.core.api.Assertions.assertThat;

import com.elipair.church.global.config.JpaConfig;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/**
 * 로컬 개발 시드(db/dev/afterMigrate__seed.sql)가 현재 스키마에서 실제로 적용되는지 확인한다(#86).
 * 시드는 dev 프로필에서만 돌아 다른 테스트가 건드리지 않으므로, 스키마가 바뀌면 조용히 깨진다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TestcontainersConfiguration.class, JpaConfig.class})
@TestPropertySource(
        properties = {
            "spring.flyway.enabled=true",
            "spring.flyway.locations=classpath:db/migration,classpath:db/dev",
            "spring.jpa.hibernate.ddl-auto=validate"
        })
class DevSeedTest {

    @PersistenceContext
    private EntityManager em;

    private long count(String sql) {
        return ((Number) em.createNativeQuery(sql).getSingleResult()).longValue();
    }

    @Test
    void 시드_행은_전부_첫_교회에_속한다() {
        assertThat(count("select count(*) from members where id between 9000 and 9999"))
                .isEqualTo(19);
        assertThat(count("select count(*) from members where id between 9000 and 9999 and church_id <> 1"))
                .isZero();
        assertThat(count("select count(*) from sermons where id between 9000 and 9999 and church_id = 1"))
                .isEqualTo(5);
    }

    @Test
    void 시드에_SUPER_ADMIN_회원이_한_명_있다() {
        assertThat(count("select count(*) from member_roles mr join roles r on r.id = mr.role_id"
                        + " where r.name = 'SUPER_ADMIN' and r.church_id = 1 and mr.member_id = 9019"))
                .isEqualTo(1);
    }
}
