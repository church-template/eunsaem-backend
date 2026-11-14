package com.elipair.church.isolation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.elipair.church.TestcontainersConfiguration;
import com.elipair.church.domain.sermon.SermonRepository;
import com.elipair.church.global.security.JwtTokenProvider;
import com.elipair.church.global.security.MemberPrincipal;
import com.elipair.church.global.tenant.TenantContext;
import com.elipair.testfixture.ChurchRows;
import com.elipair.testfixture.TenantTestSupport;
import com.elipair.testfixture.TwoChurches;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 일괄 수정·집계의 교회 격리(#86): 조건 없이 "전 회원"을 건드리던 쿼리와 "전체에서 세던" 집계가
 * 교회 범위로 좁혀졌는지, 그리고 교회가 지정되지 않은 DB 접근이 예외인지 확인한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TwoChurches.class})
class BulkUpdateIsolationTest {

    private static final String PASSWORD = "pw-12345678";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private TwoChurches churches;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private JwtTokenProvider provider;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private SermonRepository sermonRepository;

    private ChurchRows a;
    private ChurchRows b;

    @BeforeEach
    void seed() {
        ChurchRows.deleteAll(jdbc, churches.aId(), churches.bId());
        a = ChurchRows.insertAll(jdbc, churches.aId(), "ISO-A");
        b = ChurchRows.insertAll(jdbc, churches.bId(), "ISO-B");
    }

    @AfterEach
    void cleanup() {
        ChurchRows.deleteAll(jdbc, churches.aId(), churches.bId());
    }

    private String token(long churchId, long memberId, UUID uuid, List<String> permissions) {
        return "Bearer "
                + TenantContext.callAs(
                        churchId,
                        () -> provider.issueAccess(
                                new MemberPrincipal(memberId, uuid.toString(), "관리자", 1000), null, permissions));
    }

    private boolean termsAgreed(long memberId) {
        return jdbc.sql("select terms_agreed from members where id = ?")
                .param(memberId)
                .query(Boolean.class)
                .single();
    }

    /** 그 교회의 SUPER_ADMIN 역할을 가진 회원을 만든다. [id, uuid] */
    private Object[] superAdmin(long churchId, String phone) {
        UUID uuid = UUID.randomUUID();
        long id = jdbc.sql(
                        "insert into members(uuid, phone, name, password, terms_agreed, privacy_agreed, created_at, church_id)"
                                + " values (?, ?, '대표자', ?, true, true, now(), ?) returning id")
                .params(uuid, phone, passwordEncoder.encode(PASSWORD), churchId)
                .query(Long.class)
                .single();
        jdbc.sql("insert into member_roles(member_id, role_id)"
                        + " select ?, id from roles where church_id = ? and name = 'SUPER_ADMIN'")
                .params(id, churchId)
                .update();
        return new Object[] {id, uuid};
    }

    @Test
    void 동의_초기화는_다른_교회_회원을_건드리지_않는다() throws Exception {
        mvc.perform(post(churches.aPrefix() + "/api/admin/agreements/reset")
                        .header("Authorization", token(churches.aId(), a.memberId(), a.memberUuid(), allPermissions()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"target\":\"terms\"}"))
                .andExpect(status().is2xxSuccessful());

        assertThat(termsAgreed(a.memberId())).isFalse();
        assertThat(termsAgreed(b.memberId())).isTrue();
    }

    @Test
    void 마지막_SUPER_ADMIN_보호는_교회별로_센다() throws Exception {
        Object[] superA = superAdmin(churches.aId(), "01079990001");
        superAdmin(churches.bId(), "01079990002"); // 다른 교회에 한 명 더 있어도 A의 "마지막"이다

        mvc.perform(delete(churches.aPrefix() + "/api/members/me")
                        .header("Authorization", token(churches.aId(), (long) superA[0], (UUID) superA[1], List.of()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().is4xxClientError());

        assertThat(jdbc.sql("select count(*) from members where id = ? and deleted_at is null")
                        .param(superA[0])
                        .query(Long.class)
                        .single())
                .isEqualTo(1);

        // 대조군: A에 SUPER_ADMIN이 한 명 더 생기면 같은 요청이 성공한다 — 위의 거부가 "마지막"이라서였음을 보증한다.
        superAdmin(churches.aId(), "01079990003");
        mvc.perform(delete(churches.aPrefix() + "/api/members/me")
                        .header("Authorization", token(churches.aId(), (long) superA[0], (UUID) superA[1], List.of()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().is2xxSuccessful());
    }

    @Test
    void 테넌트_없이_리포지토리를_부르면_예외() {
        TenantContext.clear();
        try {
            assertThatThrownBy(() -> sermonRepository.count()).hasRootCauseInstanceOf(IllegalStateException.class);
        } finally {
            TenantContext.set(TenantTestSupport.DEFAULT_CHURCH_ID);
        }
    }

    private List<String> allPermissions() {
        return jdbc.sql("select name from permissions").query(String.class).list();
    }
}
