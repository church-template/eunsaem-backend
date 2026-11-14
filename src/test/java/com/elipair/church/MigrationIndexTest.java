package com.elipair.church;

import static org.assertj.core.api.Assertions.assertThat;

import com.elipair.church.global.config.JpaConfig;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/**
 * 마이그레이션이 만든 **부분 인덱스**(WHERE deleted_at IS NULL)가 실제 DB에 존재하고 조건절이 살아있는지 검증.
 *
 * <p>일반 리포지토리 슬라이스는 {@code flyway.enabled=false} + {@code ddl-auto=create-drop}이라 마이그레이션 인덱스를
 * 만들지 않고, 운영의 {@code ddl-auto=validate}도 인덱스는 검사하지 않는다. 그래서 이 테스트만 Flyway를 켜서
 * 마이그레이션을 실제 적용하고 {@code pg_indexes.indexdef}를 조회해 인덱스 정의(컬럼·부분 조건)를 확인한다.
 * 신규 콘텐츠 도메인이 부분 인덱스를 추가하면 여기에 검증 1건을 추가한다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TestcontainersConfiguration.class, JpaConfig.class}) // JpaConfig = 테넌트 리졸버(#86)
@TestPropertySource(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate"})
class MigrationIndexTest {

    @PersistenceContext
    private EntityManager em;

    private String indexDef(String indexName) {
        List<?> rows = em.createNativeQuery("select indexdef from pg_indexes where indexname = :name")
                .setParameter("name", indexName)
                .getResultList();
        return rows.isEmpty() ? null : (String) rows.get(0);
    }

    @Test
    void sermons_preached_at_is_partial_on_active_rows() {
        assertThat(indexDef("idx_sermons_preached_at"))
                .as("V7 설교 목록 인덱스")
                .isNotNull()
                .contains("preached_at")
                .contains("deleted_at IS NULL");
    }

    @Test
    void notices_pinned_created_is_partial_on_active_rows() {
        assertThat(indexDef("idx_notices_pinned_created"))
                .as("V8 공지 상단고정 정렬 인덱스")
                .isNotNull()
                .contains("is_pinned")
                .contains("created_at")
                .contains("deleted_at IS NULL");
    }

    @Test
    void members_phone_unique_is_partial_on_active_rows() {
        assertThat(indexDef("uq_members_church_phone_active"))
                .as("V3 전화번호 부분 유니크(번호 재활용 허용) — V19에서 교회 단위로 교체(#86)")
                .isNotNull()
                .contains("(church_id, phone)")
                .contains("deleted_at IS NULL");
    }

    @Test
    void events_start_at_is_partial_on_active_rows() {
        assertThat(indexDef("idx_events_start_at"))
                .as("V9 일정 시작일 범위 인덱스")
                .isNotNull()
                .contains("start_at")
                .contains("deleted_at IS NULL");
    }

    @Test
    void departments_sort_order_is_partial_on_active_rows() {
        assertThat(indexDef("idx_departments_sort_order"))
                .as("V10 부서 정렬 인덱스")
                .isNotNull()
                .contains("sort_order")
                .contains("deleted_at IS NULL");
    }

    @Test
    void gallery_albums_created_at_is_partial_on_active_rows() {
        assertThat(indexDef("idx_gallery_albums_created_at"))
                .as("V11 갤러리 앨범 목록 인덱스")
                .isNotNull()
                .contains("created_at")
                .contains("deleted_at IS NULL");
    }

    @Test
    void bulletins_service_date_is_partial_on_active_rows() {
        assertThat(indexDef("idx_bulletins_service_date"))
                .as("V12 주보 목록 인덱스")
                .isNotNull()
                .contains("service_date")
                .contains("deleted_at IS NULL");
    }

    @Test
    void bulletins_media_id_fk_is_on_delete_set_null() {
        List<?> rules = em.createNativeQuery("select rc.delete_rule from information_schema.referential_constraints rc "
                        + "join information_schema.key_column_usage kcu "
                        + "  on kcu.constraint_name = rc.constraint_name "
                        + "  and kcu.constraint_schema = rc.constraint_schema "
                        + "where kcu.table_name = 'bulletins' and kcu.column_name = 'media_id'")
                .getResultList();
        assertThat(rules).as("V12 주보 media_id FK 존재").hasSize(1);
        assertThat((String) rules.get(0)).as("ON DELETE SET NULL").isEqualTo("SET NULL");
    }

    @Test
    void bible_challenges_start_date_is_partial_on_active_rows() {
        assertThat(indexDef("idx_bible_challenges_start_date"))
                .as("V13 챌린지 목록 인덱스")
                .isNotNull()
                .contains("start_date")
                .contains("deleted_at IS NULL");
    }

    @Test
    void inquiries_created_at_is_partial_on_active_rows() {
        assertThat(indexDef("idx_inquiries_created"))
                .as("V14 문의 목록 인덱스")
                .isNotNull()
                .contains("created_at")
                .contains("deleted_at IS NULL");
    }

    @Test
    void inquiries_pending_is_partial_on_active_uncompleted_rows() {
        assertThat(indexDef("idx_inquiries_pending"))
                .as("V14 미처리 문의 전용 부분 인덱스")
                .isNotNull()
                .contains("created_at")
                .contains("deleted_at IS NULL")
                .contains("completed_at IS NULL");
    }

    @Test
    void challenge_participations_unique_is_partial_on_active_rows() {
        assertThat(indexDef("uq_challenge_participations_active"))
                .as("V13 참여 부분 유니크(취소 후 재참여 허용)")
                .isNotNull()
                .contains("challenge_id")
                .contains("member_id")
                .contains("deleted_at IS NULL");
    }

    @Test
    void vehicle_runs_departs_at_is_partial_on_active_rows() {
        assertThat(indexDef("idx_vehicle_runs_departs_at"))
                .as("V16 운행일 목록 인덱스")
                .isNotNull()
                .contains("departs_at")
                .contains("deleted_at IS NULL");
    }

    @Test
    void vehicle_requests_unique_is_partial_on_active_rows() {
        assertThat(indexDef("uq_vehicle_requests_active"))
                .as("V16 신청 부분 유니크(취소 후 재신청 허용)")
                .isNotNull()
                .contains("run_id")
                .contains("member_id")
                .contains("deleted_at IS NULL");
    }
}
