package com.elipair.church.isolation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import com.elipair.church.TestcontainersConfiguration;
import com.elipair.church.global.security.JwtTokenProvider;
import com.elipair.church.global.security.MemberPrincipal;
import com.elipair.testfixture.ChurchRows;
import com.elipair.testfixture.TwoChurches;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 다른 교회의 id를 직접 넣는 요청의 격리(#86) — 이 작업의 합격 기준.
 *
 * <p>교회 B에 만든 행의 id를, 교회 A의 최고 관리자 토큰으로 A 주소에서 쓴다. 조회·삭제는 404여야 하고,
 * 수정은 4xx(본문 검증이 먼저 걸리면 400)여야 하며, 어떤 경우에도 B의 데이터가 바뀌면 안 된다.
 * id는 전 교회에 걸쳐 순차라, 이게 뚫리면 다른 교회의 역할 권한을 바꾸거나 파일을 열거할 수 있다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TwoChurches.class})
class PrimaryKeyIsolationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private TwoChurches churches;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private JwtTokenProvider provider;

    private ChurchRows a;
    private ChurchRows b;
    private String token;

    /** A의 다른 회원(요청자가 아님). 본인 역할 변경 금지 규칙에 걸리지 않고 교회 격리만 검증하려는 대상. */
    private java.util.UUID targetUuid;

    private long targetId;

    @BeforeEach
    void seed() {
        ChurchRows.deleteAll(jdbc, churches.aId(), churches.bId());
        a = ChurchRows.insertAll(jdbc, churches.aId(), "ISO-A");
        b = ChurchRows.insertAll(jdbc, churches.bId(), "ISO-B");
        targetUuid = java.util.UUID.randomUUID();
        targetId = jdbc.sql("insert into members(uuid, phone, name, password, created_at, church_id)"
                        + " values (?, '01078880001', 'ISO-A-대상', 'hash', now(), ?) returning id")
                .params(targetUuid, churches.aId())
                .query(Long.class)
                .single();
        List<String> allPermissions =
                jdbc.sql("select name from permissions").query(String.class).list();
        token = "Bearer "
                + churches.inA(() -> provider.issueAccess(
                        new MemberPrincipal(a.memberId(), a.memberUuid().toString(), "A최고관리자", 1000),
                        null,
                        allPermissions));
    }

    @AfterEach
    void cleanup() {
        ChurchRows.deleteAll(jdbc, churches.aId(), churches.bId());
    }

    private record Call(HttpMethod method, String path, String body) {
        static Call of(HttpMethod method, String path) {
            return new Call(method, path, method == HttpMethod.GET || method == HttpMethod.DELETE ? null : "{}");
        }
    }

    private int perform(Call call) throws Exception {
        MockHttpServletRequestBuilder builder =
                request(call.method(), churches.aPrefix() + call.path()).header("Authorization", token);
        if (call.body() != null) {
            builder.contentType(MediaType.APPLICATION_JSON).content(call.body());
        }
        return mvc.perform(builder).andReturn().getResponse().getStatus();
    }

    @Test
    void B의_id로_조회하거나_삭제하면_404이고_B는_그대로다() throws Exception {
        List<Call> calls = new ArrayList<>();
        for (String path : List.of(
                "/api/sermons/" + b.sermonId(),
                "/api/notices/" + b.noticeId(),
                "/api/events/" + b.eventId(),
                "/api/departments/" + b.departmentId(),
                "/api/gallery/albums/" + b.albumId(),
                "/api/bulletins/" + b.bulletinId(),
                "/api/bible-challenges/" + b.challengeId(),
                "/api/bible-challenges/" + b.challengeId() + "/participants",
                "/api/media/" + b.mediaId(),
                "/api/admin/media/" + b.mediaId(),
                "/api/admin/inquiries/" + b.inquiryId(),
                "/api/admin/vehicle-runs/" + b.vehicleRunId() + "/requests",
                "/api/members/" + b.memberUuid())) {
            calls.add(Call.of(HttpMethod.GET, path));
        }
        for (String path : List.of(
                "/api/admin/sermons/" + b.sermonId(),
                "/api/admin/notices/" + b.noticeId(),
                "/api/admin/events/" + b.eventId(),
                "/api/admin/departments/" + b.departmentId(),
                "/api/admin/gallery/albums/" + b.albumId(),
                "/api/admin/gallery/photos/" + b.photoId(),
                "/api/admin/bulletins/" + b.bulletinId(),
                "/api/admin/bible-challenges/" + b.challengeId(),
                "/api/admin/vehicle-runs/" + b.vehicleRunId(),
                "/api/admin/inquiries/" + b.inquiryId(),
                "/api/admin/media/" + b.mediaId(),
                "/api/admin/tags/" + b.tagId(),
                "/api/admin/positions/" + b.positionId(),
                "/api/admin/roles/" + b.roleId())) {
            calls.add(Call.of(HttpMethod.DELETE, path));
        }
        String before = ChurchRows.snapshot(jdbc, churches.bId());

        List<String> wrong = new ArrayList<>();
        for (Call call : calls) {
            int status = perform(call);
            if (status != 404) {
                wrong.add(call.method() + " " + call.path() + " → " + status);
            }
        }

        assertThat(wrong).as("404가 아닌 응답").isEmpty();
        assertThat(ChurchRows.snapshot(jdbc, churches.bId())).isEqualTo(before);
    }

    @Test
    void B의_id로_수정하면_4xx이고_B는_그대로다() throws Exception {
        List<Call> calls = new ArrayList<>();
        for (String path : List.of(
                "/api/admin/sermons/" + b.sermonId(),
                "/api/admin/notices/" + b.noticeId(),
                "/api/admin/events/" + b.eventId(),
                "/api/admin/departments/" + b.departmentId(),
                "/api/admin/gallery/albums/" + b.albumId(),
                "/api/admin/bible-challenges/" + b.challengeId(),
                "/api/admin/vehicle-runs/" + b.vehicleRunId(),
                "/api/admin/inquiries/" + b.inquiryId() + "/complete",
                "/api/admin/tags/" + b.tagId(),
                "/api/admin/positions/" + b.positionId(),
                "/api/admin/roles/" + b.roleId(),
                "/api/admin/members/" + b.memberUuid())) {
            calls.add(Call.of(HttpMethod.PATCH, path));
        }
        calls.add(new Call(HttpMethod.PATCH, "/api/admin/tags/" + b.tagId(), "{\"name\":\"탈취\"}"));
        calls.add(new Call(HttpMethod.PATCH, "/api/admin/positions/" + b.positionId(), "{\"name\":\"탈취\"}"));
        calls.add(new Call(HttpMethod.PATCH, "/api/admin/roles/" + b.roleId(), "{\"description\":\"탈취\"}"));
        calls.add(new Call(HttpMethod.PATCH, "/api/admin/members/" + b.memberUuid(), "{\"name\":\"탈취\"}"));
        calls.add(new Call(HttpMethod.PUT, "/api/admin/roles/" + b.roleId() + "/permissions", "{\"permissions\":[]}"));
        calls.add(Call.of(HttpMethod.POST, "/api/admin/members/" + b.memberUuid() + "/reset-password"));
        calls.add(new Call(
                HttpMethod.PUT, "/api/admin/members/" + b.memberUuid() + "/position", "{\"positionId\":null}"));
        calls.add(new Call(
                HttpMethod.POST, "/api/admin/gallery/albums/" + b.albumId() + "/photos?mediaIds=" + a.mediaId(), null));
        String before = ChurchRows.snapshot(jdbc, churches.bId());

        List<String> wrong = new ArrayList<>();
        for (Call call : calls) {
            int status = perform(call);
            if (status < 400 || status >= 500) {
                wrong.add(call.method() + " " + call.path() + " → " + status);
            }
        }

        assertThat(wrong).as("4xx가 아닌 응답").isEmpty();
        assertThat(ChurchRows.snapshot(jdbc, churches.bId())).isEqualTo(before);
    }

    @Test
    void A의_행에_B의_id를_연결할_수_없다() throws Exception {
        List<Call> calls = List.of(
                // A 회원에 B의 직분·역할
                new Call(
                        HttpMethod.PUT,
                        "/api/admin/members/" + targetUuid + "/position",
                        "{\"positionId\":" + b.positionId() + "}"),
                new Call(
                        HttpMethod.POST,
                        "/api/admin/members/" + targetUuid + "/roles",
                        "{\"roleId\":" + b.roleId() + "}"),
                // A 앨범에 B의 미디어
                new Call(
                        HttpMethod.POST,
                        "/api/admin/gallery/albums/" + a.albumId() + "/photos?mediaIds=" + b.mediaId(),
                        null),
                // A 설교에 B의 태그
                new Call(
                        HttpMethod.POST,
                        "/api/admin/sermons",
                        "{\"title\":\"연결시도\",\"preacher\":\"p\",\"preachedAt\":\"2026-01-01\",\"tagIds\":[" + b.tagId()
                                + "]}"));

        List<String> wrong = new ArrayList<>();
        for (Call call : calls) {
            int status = perform(call);
            if (status < 400 || status >= 500) {
                wrong.add(call.method() + " " + call.path() + " → " + status);
            }
        }

        assertThat(wrong).as("4xx가 아닌 응답").isEmpty();
        assertThat(count("select count(*) from members where id = ? and position_id = ?", targetId, b.positionId()))
                .isZero();
        assertThat(count("select count(*) from member_roles where member_id = ? and role_id = ?", targetId, b.roleId()))
                .isZero();
        assertThat(count(
                        "select count(*) from gallery_photos where album_id = ? and media_id = ?",
                        a.albumId(),
                        b.mediaId()))
                .isZero();
        assertThat(count("select count(*) from content_tags where tag_id = ?", b.tagId()))
                .isZero();
    }

    /**
     * 대조군: 같은 토큰·같은 경로에 A 자신의 id를 넣으면 성공한다. 위 테스트의 404·4xx가 경로 오타나
     * 토큰 문제 때문이 아니라 교회 격리 때문임을 보증한다.
     */
    @Test
    void 대조군_A_자신의_id로는_같은_요청이_성공한다() throws Exception {
        List<Call> calls = new ArrayList<>();
        for (String path : List.of(
                "/api/sermons/" + a.sermonId(),
                "/api/notices/" + a.noticeId(),
                "/api/events/" + a.eventId(),
                "/api/departments/" + a.departmentId(),
                "/api/gallery/albums/" + a.albumId(),
                "/api/bulletins/" + a.bulletinId(),
                "/api/bible-challenges/" + a.challengeId(),
                "/api/admin/media/" + a.mediaId(),
                "/api/admin/inquiries/" + a.inquiryId(),
                "/api/admin/vehicle-runs/" + a.vehicleRunId() + "/requests",
                "/api/members/" + a.memberUuid())) {
            calls.add(Call.of(HttpMethod.GET, path));
        }
        calls.add(new Call(HttpMethod.PATCH, "/api/admin/tags/" + a.tagId(), "{\"name\":\"ISO-A-바꿈\"}"));
        calls.add(new Call(HttpMethod.PATCH, "/api/admin/positions/" + a.positionId(), "{\"name\":\"ISO-A-바꿈\"}"));
        calls.add(new Call(HttpMethod.PATCH, "/api/admin/members/" + a.memberUuid(), "{\"name\":\"바꿈\"}"));
        calls.add(Call.of(HttpMethod.POST, "/api/admin/members/" + a.memberUuid() + "/reset-password"));
        calls.add(new Call(
                HttpMethod.PUT,
                "/api/admin/members/" + targetUuid + "/position",
                "{\"positionId\":" + a.positionId() + "}"));
        calls.add(new Call(
                HttpMethod.POST, "/api/admin/members/" + targetUuid + "/roles", "{\"roleId\":" + a.roleId() + "}"));
        calls.add(Call.of(HttpMethod.DELETE, "/api/admin/members/" + targetUuid + "/roles/" + a.roleId()));
        calls.add(Call.of(HttpMethod.DELETE, "/api/admin/sermons/" + a.sermonId()));
        calls.add(Call.of(HttpMethod.DELETE, "/api/admin/roles/" + a.roleId()));

        List<String> wrong = new ArrayList<>();
        for (Call call : calls) {
            int status = perform(call);
            if (status < 200 || status >= 300) {
                wrong.add(call.method() + " " + call.path() + " → " + status);
            }
        }

        assertThat(wrong).as("2xx가 아닌 응답").isEmpty();
    }

    private long count(String sql, Object... params) {
        return jdbc.sql(sql).params(params).query(Long.class).single();
    }
}
