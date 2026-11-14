package com.elipair.church.isolation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.elipair.church.TestcontainersConfiguration;
import com.elipair.church.global.security.JwtTokenProvider;
import com.elipair.church.global.security.MemberPrincipal;
import com.elipair.testfixture.ChurchRows;
import com.elipair.testfixture.TwoChurches;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 목록 응답의 교회 격리(#86): A·B에 같은 종류의 행을 하나씩 넣고, A 주소의 목록에 A의 행은 있고 B의 행은 없는지 본다.
 * 행의 제목·이름에 교회 표식(ISO-A / ISO-B)이 들어 있다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TwoChurches.class})
class ListIsolationTest {

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

    @BeforeEach
    void seed() {
        ChurchRows.deleteAll(jdbc, churches.aId(), churches.bId());
        a = ChurchRows.insertAll(jdbc, churches.aId(), "ISO-A");
        b = ChurchRows.insertAll(jdbc, churches.bId(), "ISO-B");
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

    private MvcResult fetch(String path) throws Exception {
        return mvc.perform(get(churches.aPrefix() + path).header("Authorization", token))
                .andReturn();
    }

    @Test
    void A의_목록에는_A의_행만_있다() throws Exception {
        List<String> wrong = new ArrayList<>();
        for (String path : List.of(
                "/api/sermons",
                "/api/notices",
                "/api/events",
                "/api/departments",
                "/api/gallery/albums",
                "/api/bulletins",
                "/api/bible-challenges",
                "/api/admin/inquiries",
                "/api/members?q=ISO",
                "/api/admin/roles",
                "/api/positions",
                "/api/tags",
                "/api/admin/media",
                "/api/main")) {
            MvcResult result = fetch(path);
            String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
            if (result.getResponse().getStatus() != 200) {
                wrong.add(path + " → status " + result.getResponse().getStatus());
            } else if (!body.contains("ISO-A")) {
                wrong.add(path + " → A의 행이 없다(검증이 헛돈다)");
            } else if (body.contains("ISO-B")) {
                wrong.add(path + " → B의 행이 섞였다");
            }
        }
        assertThat(wrong).isEmpty();
    }

    @Test
    void 차량운행_목록에는_A의_운행만_있다() throws Exception {
        // 차량운행에는 제목이 없어 id로 확인한다.
        for (String path : List.of("/api/admin/vehicle-runs", "/api/vehicle-runs")) {
            MvcResult result = fetch(path);
            assertThat(result.getResponse().getStatus()).as(path).isEqualTo(200);
            List<Number> ids = JsonPath.read(result.getResponse().getContentAsString(StandardCharsets.UTF_8), "$..id");
            List<Long> runIds = ids.stream().map(Number::longValue).toList();
            assertThat(runIds).as(path).contains(a.vehicleRunId()).doesNotContain(b.vehicleRunId());
        }
    }
}
