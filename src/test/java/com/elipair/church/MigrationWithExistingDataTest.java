package com.elipair.church;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * 데이터가 들어 있는 V17 스키마에 V18·V19를 적용한다(#86) — 운영 전환에서 실제로 일어나는 일이다.
 * 다른 마이그레이션 테스트는 빈 스키마에서 시작하므로, "기존 행이 보존되고 전부 첫 교회가 된다"는 여기서만 확인한다.
 */
@Testcontainers
class MigrationWithExistingDataTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    private static final List<String> TENANT_TABLES = List.of(
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

    private Flyway flyway(String target) {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .placeholders(Map.of(
                        "church_name", "은샘교회", "church_slug", "eunsaem", "church_origin", "https://www.eunsaem.com"))
                .target(target)
                .load();
    }

    private Map<String, Long> counts(Statement statement) throws Exception {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String table : TENANT_TABLES) {
            try (ResultSet rs = statement.executeQuery("select count(*) from " + table)) {
                rs.next();
                counts.put(table, rs.getLong(1));
            }
        }
        return counts;
    }

    @Test
    void 기존_행은_보존되고_전부_첫_교회가_된다() throws Exception {
        flyway("17").migrate();

        Map<String, Long> before;
        try (Connection connection = DriverManager.getConnection(
                        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                Statement statement = connection.createStatement()) {
            // 전환 전 운영 DB처럼: church_id 없이 테이블마다 행을 넣는다(같은 전화번호의 탈퇴 회원 포함).
            statement.execute("""
                    insert into positions(id, name, sort_order, created_at) values (1, '목사', 1, now());
                    insert into members(id, uuid, phone, name, password, position_id, created_at) values
                        (1, gen_random_uuid(), '01011112222', '회원', 'h', 1, now()),
                        (2, gen_random_uuid(), '01033334444', '탈퇴', 'h', null, now());
                    update members set deleted_at = now() where id = 2;
                    insert into members(id, uuid, phone, name, password, created_at)
                        values (3, gen_random_uuid(), '01033334444', '재가입', 'h', now());
                    insert into member_roles(member_id, role_id) select 1, id from roles where name = 'SUPER_ADMIN';
                    insert into tags(id, name, created_at) values (1, '주일예배', now());
                    insert into media(id, filename, stored_path, mime_type, size, uploaded_by, created_at)
                        values (1, 'a.jpg', '2026/06/a.jpg', 'image/jpeg', 1, 1, now());
                    insert into sermons(id, title, preacher, preached_at, content, created_at, created_by)
                        values (1, '설교', 'p', current_date, '![x](media:1)', now(), 1);
                    insert into notices(id, title, content, created_at) values (1, '공지', 'x', now());
                    insert into events(id, title, description, start_at, created_at) values (1, '일정', 'x', now(), now());
                    insert into departments(id, name, description, created_at) values (1, '부서', 'x', now());
                    insert into departments(id, name, description, parent_id, created_at) values (2, '하위', 'x', 1, now());
                    insert into gallery_albums(id, title, description, created_at) values (1, '앨범', 'x', now());
                    insert into gallery_photos(id, album_id, media_id, sort_order, created_at) values (1, 1, 1, 0, now());
                    insert into bulletins(id, title, service_date, media_id, created_at) values (1, '주보', current_date, 1, now());
                    insert into content_tags(tag_id, resource_type, resource_id) values (1, 'SERMON', 1);
                    insert into bible_challenges(id, title, start_book, end_book, start_date, target_days, created_at)
                        values (1, '통독', 1, 66, current_date, 100, now());
                    insert into challenge_participations(id, challenge_id, member_id, created_at) values (1, 1, 1, now());
                    insert into challenge_reading_logs(id, participation_id, read_date, chapters, created_at)
                        values (1, 1, current_date, 3, now());
                    insert into inquiries(id, name, phone, content, privacy_agreed_at, created_at)
                        values (1, '문의', '01000000000', 'x', now(), now());
                    insert into vehicle_runs(id, departs_at, created_at) values (1, now(), now());
                    insert into vehicle_requests(id, run_id, member_id, pickup_location, created_at)
                        values (1, 1, 1, '정문', now());
                    """);
            before = counts(statement);
            assertThat(before.values()).as("테이블마다 행이 있어야 검증이 의미 있다").allMatch(count -> count > 0);
        }

        flyway("19").migrate();

        try (Connection connection = DriverManager.getConnection(
                        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                Statement statement = connection.createStatement()) {
            assertThat(counts(statement)).as("행 수 보존").isEqualTo(before);
            for (String table : TENANT_TABLES) {
                try (ResultSet rs = statement.executeQuery(
                        "select count(*) from " + table + " where church_id is distinct from 1")) {
                    rs.next();
                    assertThat(rs.getLong(1)).as(table + ": 첫 교회가 아닌 행").isZero();
                }
            }
            try (ResultSet rs = statement.executeQuery("select name, slug, frontend_origin from churches")) {
                rs.next();
                assertThat(rs.getString(1) + "|" + rs.getString(2) + "|" + rs.getString(3))
                        .isEqualTo("은샘교회|eunsaem|https://www.eunsaem.com");
                assertThat(rs.next()).isFalse();
            }
            // 연결 관계와 내용이 그대로다.
            try (ResultSet rs = statement.executeQuery("select m.phone, r.name, me.stored_path, s.content"
                    + " from members m join member_roles mr on mr.member_id = m.id join roles r on r.id = mr.role_id"
                    + " join media me on me.uploaded_by = m.id join sermons s on s.created_by = m.id where m.id = 1")) {
                rs.next();
                assertThat(rs.getString(1) + "|" + rs.getString(2) + "|" + rs.getString(3) + "|" + rs.getString(4))
                        .isEqualTo("01011112222|SUPER_ADMIN|2026/06/a.jpg|![x](media:1)");
            }
            // 전환 이전 코드처럼 church_id 없이 넣어도 첫 교회로 들어간다(이미지 되돌리기의 전제).
            statement.execute("insert into notices(id, title, content, created_at) values (99, '옛 코드의 글', 'x', now())");
            try (ResultSet rs = statement.executeQuery("select church_id from notices where title = '옛 코드의 글'")) {
                rs.next();
                assertThat(rs.getLong(1)).isEqualTo(1L);
            }
        }
    }
}
