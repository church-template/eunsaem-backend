package com.elipair.church;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.elipair.church.global.security.JwtTokenProvider;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 새 교회 등록 스크립트(scripts/new-church.sql, #86)를 실제 DB에 실행해 본다.
 * 운영에서는 new-church.sh가 psql 변수(:'name' 등)로 값을 넘긴다 — 테스트는 같은 자리에 값을 끼워 넣어 실행한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class NewChurchScriptTest {

    private static final String SLUG = "script-church";
    private static final String PHONE = "010-8888-0001";
    private static final String PASSWORD = "temp-pw-1234";

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JwtTokenProvider provider;

    @AfterEach
    void cleanup() {
        Long churchId = churchId();
        if (churchId == null) {
            return;
        }
        jdbc.sql("delete from member_roles where member_id in (select id from members where church_id = ?)")
                .param(churchId)
                .update();
        jdbc.sql("delete from members where church_id = ?").param(churchId).update();
        jdbc.sql("delete from roles where church_id = ?").param(churchId).update(); // role_permissions는 CASCADE
        jdbc.sql("delete from churches where id = ?").param(churchId).update();
    }

    private Long churchId() {
        return jdbc.sql("select id from churches where slug = ?")
                .param(SLUG)
                .query(Long.class)
                .optional()
                .orElse(null);
    }

    /** psql의 :'변수'를 SQL 문자열 리터럴로 바꿔 실행한다(작은따옴표는 두 번 써서 이스케이프). */
    private void runScript(Map<String, String> vars) throws Exception {
        String sql = Files.readString(Path.of("scripts/new-church.sql"), StandardCharsets.UTF_8);
        for (Map.Entry<String, String> var : vars.entrySet()) {
            sql = sql.replace(":'" + var.getKey() + "'", "'" + var.getValue().replace("'", "''") + "'");
        }
        assertThat(sql).as("치환되지 않은 psql 변수").doesNotContainPattern(":'[a-z_]+'");
        jdbcTemplate.execute(sql); // 여러 문장 + SELECT 결과가 섞여 있어 Statement.execute로 실행한다
    }

    private void runScript() throws Exception {
        runScript(Map.of(
                "name",
                "스크립트교회",
                "slug",
                SLUG,
                "origin",
                "https://script.example",
                "phone",
                PHONE,
                "owner_name",
                "대표자",
                "pw",
                PASSWORD));
    }

    private List<String> permissionsOf(long churchId, String role) {
        return jdbc.sql(
                        "select p.name from role_permissions rp join roles r on r.id = rp.role_id"
                                + " join permissions p on p.id = rp.permission_id where r.church_id = ? and r.name = ? order by 1")
                .params(churchId, role)
                .query(String.class)
                .list();
    }

    @Test
    void 스크립트는_교회_역할_권한_계정을_만든다() throws Exception {
        runScript();
        long churchId = churchId();

        assertThat(jdbc.sql("select frontend_origin from churches where id = ?")
                        .param(churchId)
                        .query(String.class)
                        .single())
                .isEqualTo("https://script.example");
        assertThat(jdbc.sql(
                                "select name || ':' || priority || ':' || is_system from roles where church_id = ? order by priority desc")
                        .param(churchId)
                        .query(String.class)
                        .list())
                .containsExactly("SUPER_ADMIN:1000:true", "ADMIN:900:true", "MEMBER:100:false", "USER:0:true");
        long allPermissions =
                jdbc.sql("select count(*) from permissions").query(Long.class).single();
        assertThat(permissionsOf(churchId, "SUPER_ADMIN")).hasSize((int) allPermissions);
        assertThat(permissionsOf(churchId, "ADMIN")).hasSize((int) allPermissions);
        assertThat(permissionsOf(churchId, "USER")).isEmpty();
        assertThat(jdbc.sql(
                                "select m.phone || ':' || m.terms_agreed || ':' || m.privacy_agreed || ':' || (m.uuid is not null)"
                                        + " from members m join member_roles mr on mr.member_id = m.id"
                                        + " join roles r on r.id = mr.role_id"
                                        + " where m.church_id = ? and r.name = 'SUPER_ADMIN'")
                        .param(churchId)
                        .query(String.class)
                        .list())
                .containsExactly("01088880001:true:true:true"); // 전화번호는 숫자만
    }

    @Test
    void 역할별_권한_목록이_첫_교회와_같다() throws Exception {
        // 이후 마이그레이션이 권한을 추가하면 스크립트도 같이 고쳐야 한다 — 어긋나면 여기서 잡힌다.
        runScript();
        long churchId = churchId();
        for (String role : List.of("SUPER_ADMIN", "ADMIN", "MEMBER", "USER")) {
            assertThat(permissionsOf(churchId, role)).as(role).isEqualTo(permissionsOf(1L, role));
        }
        assertThat(permissionsOf(churchId, "MEMBER")).isNotEmpty();
    }

    @Test
    void 만든_계정으로_새_교회_주소에서_로그인된다() throws Exception {
        runScript();

        String body = mvc.perform(post("/church/" + SLUG + "/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"%s\",\"password\":\"%s\"}".formatted(PHONE, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        var claims = provider.parse(JsonPath.read(body, "$.tokens.accessToken"));
        assertThat(claims.get("maxPriority", Integer.class)).isEqualTo(1000);
        assertThat(claims.get("cid", Long.class)).isEqualTo(churchId());
    }

    @Test
    void 비밀번호_해시는_cost_10이다() throws Exception {
        runScript();
        assertThat(jdbc.sql("select password from members where church_id = ?")
                        .param(churchId())
                        .query(String.class)
                        .single())
                .matches("^\\$2[ab]\\$10\\$.{53}$");
    }

    @Test
    void 같은_slug로_두_번_실행하면_실패하고_아무것도_더_남지_않는다() throws Exception {
        runScript();
        long churchId = churchId();
        long rolesBefore =
                jdbc.sql("select count(*) from roles").query(Long.class).single();
        long membersBefore =
                jdbc.sql("select count(*) from members").query(Long.class).single();

        assertThatThrownBy(this::runScript).hasMessageContaining("uq_churches_slug");

        assertThat(jdbc.sql("select count(*) from churches where slug = ?")
                        .param(SLUG)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
        assertThat(churchId()).isEqualTo(churchId);
        assertThat(jdbc.sql("select count(*) from roles").query(Long.class).single())
                .isEqualTo(rolesBefore);
        assertThat(jdbc.sql("select count(*) from members").query(Long.class).single())
                .isEqualTo(membersBefore);
    }

    @Test
    void 형식이_틀린_값은_거부한다() {
        assertThatThrownBy(() -> runScript(Map.of(
                        "name",
                        "x",
                        "slug",
                        "Bad Slug",
                        "origin",
                        "https://x.example",
                        "phone",
                        PHONE,
                        "owner_name",
                        "대표자",
                        "pw",
                        PASSWORD)))
                .hasMessageContaining("slug");
        assertThatThrownBy(() -> runScript(Map.of(
                        "name",
                        "x",
                        "slug",
                        SLUG,
                        "origin",
                        "https://x.example",
                        "phone",
                        PHONE,
                        "owner_name",
                        "대표자",
                        "pw",
                        "short")))
                .hasMessageContaining("비밀번호");
        assertThat(churchId()).isNull();
    }
}
