package com.elipair.church.domain.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.elipair.church.TestcontainersConfiguration;
import com.elipair.church.global.security.JwtTokenProvider;
import com.elipair.church.global.security.MemberPrincipal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 관리자가 <b>다른 회원</b>을 바꾸는 작업(비밀번호 초기화·정보 수정·직분 지정)의 위계 검사(#86).
 * MEMBER_MANAGE 권한만 보면 교회 ADMIN이 교회 대표자(SUPER_ADMIN)의 비밀번호를 초기화해 그 계정을 가져갈 수 있다.
 * 대상의 최고 priority가 요청자의 maxPriority보다 높으면 403. 동급은 허용한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class MemberAdminHierarchyTest {

    private static final String PHONE_PREFIX = "0103333";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private JwtTokenProvider provider;

    private long requesterId;
    private UUID superAdmin;
    private UUID otherAdmin;
    private UUID user;

    private UUID member(String phoneSuffix, String roleName) {
        UUID uuid = UUID.randomUUID();
        long id = jdbc.sql("insert into members(uuid, phone, name, password, created_at, church_id)"
                        + " values (?, ?, '대상', 'old-hash', now(), 1) returning id")
                .params(uuid, PHONE_PREFIX + phoneSuffix)
                .query(Long.class)
                .single();
        jdbc.sql("insert into member_roles(member_id, role_id)"
                        + " select ?, id from roles where church_id = 1 and name = ?")
                .params(id, roleName)
                .update();
        if (phoneSuffix.equals("0000")) {
            requesterId = id;
        }
        return uuid;
    }

    @BeforeEach
    void seed() {
        member("0000", "ADMIN"); // 요청자
        superAdmin = member("0001", "SUPER_ADMIN");
        otherAdmin = member("0002", "ADMIN");
        user = member("0003", "USER");
    }

    @AfterEach
    void cleanup() {
        jdbc.sql("delete from member_roles where member_id in (select id from members where phone like ?)")
                .param(PHONE_PREFIX + "%")
                .update();
        jdbc.sql("delete from members where phone like ?")
                .param(PHONE_PREFIX + "%")
                .update();
    }

    /** 교회 ADMIN(maxPriority 900) + MEMBER_MANAGE. */
    private String adminToken() {
        return "Bearer "
                + provider.issueAccess(
                        new MemberPrincipal(requesterId, "uuid-requester", "교회관리자", 900),
                        null,
                        List.of("MEMBER_MANAGE"));
    }

    private String column(UUID uuid, String column) {
        return jdbc.sql("select " + column + " from members where uuid = ?")
                .param(uuid)
                .query(String.class)
                .single();
    }

    @Test
    void ADMIN이_SUPER_ADMIN_비밀번호를_초기화하면_403() throws Exception {
        mvc.perform(post("/api/admin/members/" + superAdmin + "/reset-password").header("Authorization", adminToken()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"));
        assertThat(column(superAdmin, "password")).isEqualTo("old-hash");
    }

    @Test
    void ADMIN이_SUPER_ADMIN_정보를_수정하면_403() throws Exception {
        mvc.perform(patch("/api/admin/members/" + superAdmin)
                        .header("Authorization", adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"01033339999\"}"))
                .andExpect(status().isForbidden());
        assertThat(column(superAdmin, "phone")).isEqualTo(PHONE_PREFIX + "0001");
    }

    @Test
    void ADMIN이_SUPER_ADMIN_직분을_바꾸면_403() throws Exception {
        mvc.perform(put("/api/admin/members/" + superAdmin + "/position")
                        .header("Authorization", adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"positionId\":null}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void ADMIN은_동급_ADMIN과_하위_회원을_초기화할_수_있다() throws Exception {
        mvc.perform(post("/api/admin/members/" + otherAdmin + "/reset-password").header("Authorization", adminToken()))
                .andExpect(status().isOk());
        mvc.perform(post("/api/admin/members/" + user + "/reset-password").header("Authorization", adminToken()))
                .andExpect(status().isOk());
        assertThat(column(otherAdmin, "password")).isNotEqualTo("old-hash");
    }
}
