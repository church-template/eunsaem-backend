package com.elipair.church;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.elipair.church.global.config.JpaConfig;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/**
 * 멀티테넌시 마이그레이션(V18·V19, #86) 검증: 19개 테이블의 church_id, 교회 단위 유니크, 되돌리기용 DEFAULT.
 * {@link MigrationIndexTest}와 같은 방식으로 Flyway를 켜고 실제 스키마를 조회한다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TestcontainersConfiguration.class, JpaConfig.class}) // JpaConfig = 테넌트 리졸버(#86)
@TestPropertySource(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate"})
class MigrationChurchIdTest {

    private static final Set<String> TENANT_TABLES = Set.of(
            "positions",
            "roles",
            "members",
            "tags",
            "content_tags",
            "media",
            "sermons",
            "notices",
            "events",
            "departments",
            "gallery_albums",
            "gallery_photos",
            "bulletins",
            "bible_challenges",
            "challenge_participations",
            "challenge_reading_logs",
            "inquiries",
            "vehicle_runs",
            "vehicle_requests");

    @PersistenceContext
    private EntityManager em;

    private int exec(String sql) {
        return em.createNativeQuery(sql).executeUpdate();
    }

    private long secondChurch() {
        exec("insert into churches(name, slug, frontend_origin) values ('둘째', 'mig-second', 'https://second.example')");
        return ((Number) em.createNativeQuery("select id from churches where slug = 'mig-second'")
                        .getSingleResult())
                .longValue();
    }

    @Test
    void 열아홉_테이블에_church_id_NOT_NULL이_있다() {
        List<?> rows = em.createNativeQuery("select table_name from information_schema.columns"
                        + " where table_schema = current_schema() and column_name = 'church_id' and is_nullable = 'NO'")
                .getResultList();
        assertThat(rows.stream().map(Object::toString).collect(Collectors.toSet()))
                .isEqualTo(TENANT_TABLES);
    }

    @Test
    void permissions와_조인테이블에는_없다() {
        List<?> rows = em.createNativeQuery("select table_name from information_schema.columns"
                        + " where table_schema = current_schema() and column_name = 'church_id'"
                        + " and table_name in ('permissions', 'role_permissions', 'member_roles')")
                .getResultList();
        assertThat(rows).isEmpty();
    }

    @Test
    void 같은_전화번호가_교회가_다르면_공존한다() {
        long second = secondChurch();
        String insert = "insert into members(uuid, phone, name, password, created_at, church_id)"
                + " values (gen_random_uuid(), '01077770000', 'x', 'p', now(), %d)";
        exec(insert.formatted(1));
        exec(insert.formatted(second));
        assertThatThrownBy(() -> exec(insert.formatted(second))).hasMessageContaining("uq_members_church_phone_active");
    }

    @Test
    void 같은_이름의_역할_직분_태그가_교회가_다르면_공존한다() {
        long second = secondChurch();
        exec("insert into roles(name, priority, created_at, church_id) values ('SUPER_ADMIN', 1000, now(), %d)"
                .formatted(second));
        exec("insert into positions(name, sort_order, created_at, church_id) values ('목사', 1, now(), 1)");
        exec("insert into positions(name, sort_order, created_at, church_id) values ('목사', 1, now(), %d)"
                .formatted(second));
        exec("insert into tags(name, created_at, church_id) values ('주일예배', now(), 1)");
        exec("insert into tags(name, created_at, church_id) values ('주일예배', now(), %d)".formatted(second));
        assertThatThrownBy(() -> exec("insert into tags(name, created_at, church_id) values ('주일예배', now(), 1)"))
                .hasMessageContaining("uq_tags_church_name");
    }

    @Test
    void 기존_시드_역할은_첫_교회에_속한다() {
        Object count = em.createNativeQuery("select count(*) from roles where church_id = 1")
                .getSingleResult();
        assertThat(((Number) count).intValue()).isEqualTo(4);
    }

    @Test
    void church_id를_생략한_INSERT는_첫_교회로_들어간다() {
        // V20 전까지의 되돌리기 전제: 옛 이미지가 church_id 없이 INSERT해도 첫 교회로 들어간다.
        exec("insert into tags(name, created_at) values ('default-check', now())");
        Object churchId = em.createNativeQuery("select church_id from tags where name = 'default-check'")
                .getSingleResult();
        assertThat(((Number) churchId).longValue()).isEqualTo(1L);
    }

    @Test
    void 목록_인덱스는_church_id가_선두다() {
        for (String index : List.of(
                "idx_positions_sort_order",
                "idx_roles_priority",
                "idx_media_mime_created",
                "idx_sermons_preached_at",
                "idx_notices_pinned_created",
                "idx_events_start_at",
                "idx_departments_sort_order",
                "idx_gallery_albums_created_at",
                "idx_bulletins_service_date",
                "idx_bible_challenges_start_date",
                "idx_inquiries_created",
                "idx_inquiries_pending",
                "idx_vehicle_runs_departs_at")) {
            Object def = em.createNativeQuery("select indexdef from pg_indexes where indexname = :name")
                    .setParameter("name", index)
                    .getSingleResult();
            assertThat(def.toString()).as(index).contains("(church_id, ");
        }
    }
}
