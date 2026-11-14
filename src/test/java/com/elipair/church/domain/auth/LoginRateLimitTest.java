package com.elipair.church.domain.auth;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.elipair.church.TestcontainersConfiguration;
import com.elipair.testfixture.TwoChurches;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 로그인 시도 제한(#86). 비밀번호 정책이 최소 길이뿐이라 무제한 대입에 약하고, 서버 하나에 전 교회 계정이 모인다.
 * 잠금 단위는 (교회, 전화번호, IP) — 번호만 아는 사람이 남을 잠글 수 없게 IP를 섞는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TwoChurches.class})
class LoginRateLimitTest {

    private static final String PASSWORD = "pw-12345678";
    private static final String PHONE_PREFIX = "0102222";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private TwoChurches churches;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private StringRedisTemplate redis;

    @AfterEach
    void cleanup() {
        jdbc.sql("delete from member_roles where member_id in (select id from members where phone like ?)")
                .param(PHONE_PREFIX + "%")
                .update();
        jdbc.sql("delete from members where phone like ?")
                .param(PHONE_PREFIX + "%")
                .update();
        Set<String> keys = redis.keys("auth:login:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    private void signup(String prefix, String phone) throws Exception {
        mvc.perform(post(prefix + "/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                "{\"phone\":\"%s\",\"name\":\"홍길동\",\"password\":\"%s\",\"termsAgreed\":true,\"privacyAgreed\":true}"
                                        .formatted(phone, PASSWORD)))
                .andExpect(status().isCreated());
    }

    private ResultActions login(String prefix, String phone, String password, String ip) throws Exception {
        return mvc.perform(post(prefix + "/api/auth/login")
                .with(request -> {
                    request.setRemoteAddr(ip);
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\":\"%s\",\"password\":\"%s\"}".formatted(phone, password)));
    }

    private void failTimes(String prefix, String phone, String ip, int times) throws Exception {
        for (int i = 0; i < times; i++) {
            login(prefix, phone, "wrong-password", ip).andExpect(status().isUnauthorized());
        }
    }

    @Test
    void 같은_IP에서_같은_번호로_10번_틀리면_11번째는_맞는_비밀번호도_429() throws Exception {
        String phone = PHONE_PREFIX + "0001";
        signup("", phone);
        failTimes("", phone, "1.1.1.1", 10);

        login("", phone, "wrong-password", "1.1.1.1")
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.errorCode").value("RATE_LIMIT_EXCEEDED"));
        login("", phone, PASSWORD, "1.1.1.1").andExpect(status().isTooManyRequests());
    }

    @Test
    void 다른_IP에서는_잠기지_않는다() throws Exception {
        String phone = PHONE_PREFIX + "0002";
        signup("", phone);
        failTimes("", phone, "1.1.1.1", 10);

        // 번호만 아는 사람이 남을 잠글 수 없다 — 본인은 자기 IP에서 로그인된다.
        login("", phone, PASSWORD, "2.2.2.2").andExpect(status().isOk());
    }

    @Test
    void 성공하면_실패_횟수가_지워진다() throws Exception {
        String phone = PHONE_PREFIX + "0003";
        signup("", phone);
        failTimes("", phone, "1.1.1.1", 9);
        login("", phone, PASSWORD, "1.1.1.1").andExpect(status().isOk());

        failTimes("", phone, "1.1.1.1", 9); // 429가 아니라 401
    }

    @Test
    void 다른_교회의_같은_번호는_영향받지_않는다() throws Exception {
        String phone = PHONE_PREFIX + "0004";
        signup(churches.aPrefix(), phone);
        signup(churches.bPrefix(), phone);
        failTimes(churches.aPrefix(), phone, "1.1.1.1", 10);

        login(churches.aPrefix(), phone, PASSWORD, "1.1.1.1").andExpect(status().isTooManyRequests());
        login(churches.bPrefix(), phone, PASSWORD, "1.1.1.1").andExpect(status().isOk());
    }

    @Test
    void 없는_번호로_틀려도_횟수에_잡힌다() throws Exception {
        // 회원 존재 여부와 무관하게 같은 응답·같은 제한 — 번호 존재를 드러내지 않는다.
        String phone = PHONE_PREFIX + "0005";
        failTimes("", phone, "1.1.1.1", 10);
        login("", phone, "whatever-pw", "1.1.1.1").andExpect(status().isTooManyRequests());
    }

    @Test
    @SuppressWarnings("unchecked")
    void Redis가_죽어도_로그인_흐름을_막지_않는다() {
        // 가용성 우선(fail-open) — InquiryRateLimiter와 같은 정책.
        StringRedisTemplate broken = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(broken.opsForValue()).thenReturn(ops);
        when(ops.get(anyString())).thenThrow(new QueryTimeoutException("down"));
        when(broken.execute(any(RedisScript.class), any(List.class), any(Object[].class)))
                .thenThrow(new QueryTimeoutException("down"));
        when(broken.delete(anyString())).thenThrow(new QueryTimeoutException("down"));
        LoginRateLimiter limiter = new LoginRateLimiter(broken);

        assertThatCode(() -> {
                    limiter.checkAllowed("01000000000", "1.1.1.1");
                    limiter.recordFailure("01000000000", "1.1.1.1");
                    limiter.reset("01000000000", "1.1.1.1");
                })
                .doesNotThrowAnyException();
    }
}
