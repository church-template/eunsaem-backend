package com.elipair.church.isolation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.elipair.church.TestcontainersConfiguration;
import com.elipair.church.global.security.JwtProperties;
import com.elipair.church.global.security.JwtTokenProvider;
import com.elipair.testfixture.TwoChurches;
import com.jayway.jsonpath.JsonPath;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.UUID;
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
 * 인증·CORS의 교회 격리(#86): 한 교회에서 받은 토큰은 다른 교회 주소에서 쓸 수 없고,
 * 같은 전화번호로 교회마다 따로 가입할 수 있으며, CORS는 그 교회의 프론트 주소만 허용한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TwoChurches.class})
class AuthIsolationTest {

    private static final String PHONE = "01066660001";
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
    private JwtProperties jwtProperties;

    @AfterEach
    void cleanup() {
        jdbc.sql("delete from member_roles where member_id in (select id from members where church_id in (?, ?))")
                .params(churches.aId(), churches.bId())
                .update();
        jdbc.sql("delete from members where church_id in (?, ?)")
                .params(churches.aId(), churches.bId())
                .update();
    }

    private void signup(String prefix) throws Exception {
        mvc.perform(post(prefix + "/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                "{\"phone\":\"%s\",\"name\":\"홍길동\",\"password\":\"%s\",\"termsAgreed\":true,\"privacyAgreed\":true}"
                                        .formatted(PHONE, PASSWORD)))
                .andExpect(status().is2xxSuccessful());
    }

    /** [access, refresh] */
    private String[] login(String prefix) throws Exception {
        String body = mvc.perform(post(prefix + "/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"%s\",\"password\":\"%s\"}".formatted(PHONE, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        return new String[] {JsonPath.read(body, "$.tokens.accessToken"), JsonPath.read(body, "$.tokens.refreshToken")};
    }

    private long cid(String accessToken) {
        return provider.parse(accessToken).get("cid", Long.class);
    }

    @Test
    void A에서_로그인한_access토큰을_B주소로_보내면_401() throws Exception {
        signup(churches.aPrefix());
        String access = login(churches.aPrefix())[0];

        // 같은 토큰이 A 주소에서는 통하고(200), B 주소에서는 인증 자체가 안 된다(401).
        mvc.perform(get(churches.aPrefix() + "/api/members/me").header("Authorization", "Bearer " + access))
                .andExpect(status().isOk());
        mvc.perform(get(churches.bPrefix() + "/api/members/me").header("Authorization", "Bearer " + access))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("INVALID_TOKEN"));
    }

    @Test
    void A의_refresh토큰으로_B에서_갱신하면_401() throws Exception {
        signup(churches.aPrefix());
        String refresh = login(churches.aPrefix())[1];

        mvc.perform(post(churches.bPrefix() + "/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refresh + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("INVALID_TOKEN"));
    }

    @Test
    void cid가_없는_옛_access토큰은_401이고_refresh는_성공() throws Exception {
        signup(churches.aPrefix());
        String[] tokens = login(churches.aPrefix());
        var real = provider.parse(tokens[0]);
        // 전환 전에 발급된 토큰을 흉내 낸다: 같은 시크릿·같은 클레임이되 cid만 없다.
        String legacy = Jwts.builder()
                .subject(real.getSubject())
                .id(UUID.randomUUID().toString())
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .claim("type", "access")
                .claim("mid", real.get("mid"))
                .claim("name", real.get("name"))
                .claim("permissions", List.of())
                .claim("maxPriority", 0)
                .signWith(Keys.hmacShaKeyFor(jwtProperties.secret().getBytes(StandardCharsets.UTF_8)))
                .compact();

        mvc.perform(get(churches.aPrefix() + "/api/members/me").header("Authorization", "Bearer " + legacy))
                .andExpect(status().isUnauthorized());

        String body = mvc.perform(post(churches.aPrefix() + "/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + tokens[1] + "\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(cid(JsonPath.read(body, "$.tokens.accessToken"))).isEqualTo(churches.aId());
    }

    @Test
    void 같은_전화번호로_A_B_각각_가입하고_각자_로그인된다() throws Exception {
        signup(churches.aPrefix());
        signup(churches.bPrefix());

        assertThat(cid(login(churches.aPrefix())[0])).isEqualTo(churches.aId());
        assertThat(cid(login(churches.bPrefix())[0])).isEqualTo(churches.bId());
    }

    @Test
    void CORS는_그_교회의_origin만_허용한다() throws Exception {
        mvc.perform(options(churches.bPrefix() + "/api/notices")
                        .header("Origin", TwoChurches.B_ORIGIN)
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", TwoChurches.B_ORIGIN));
        mvc.perform(options(churches.bPrefix() + "/api/notices")
                        .header("Origin", TwoChurches.A_ORIGIN)
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    void 접두사_없는_요청의_CORS는_기본_교회의_origin을_허용한다() throws Exception {
        mvc.perform(options("/api/notices")
                        .header("Origin", "http://localhost:3000")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"));
    }
}
