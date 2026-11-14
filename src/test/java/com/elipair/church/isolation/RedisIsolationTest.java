package com.elipair.church.isolation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.elipair.church.TestcontainersConfiguration;
import com.elipair.church.global.security.JwtTokenProvider;
import com.elipair.church.global.security.MemberPrincipal;
import com.elipair.church.global.tenant.TenantContext;
import com.elipair.church.global.viewcount.ViewCountFlushScheduler;
import com.elipair.testfixture.TenantTestSupport;
import com.elipair.testfixture.TwoChurches;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Redis의 교회 격리(#86): 메인 캐시와 조회수 버퍼가 교회별 키를 쓰고,
 * 요청 밖에서 도는 조회수 반영(스케줄러)이 올바른 교회의 행에만 더해지는지 확인한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TwoChurches.class})
class RedisIsolationTest {

    private static final String A_TITLE = "격리A공지-redis";
    private static final String B_TITLE = "격리B공지-redis";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private TwoChurches churches;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private ViewCountFlushScheduler scheduler;

    @Autowired
    private JwtTokenProvider provider;

    private long sermonA;
    private long sermonB;

    private long insert(String sql, Object... params) {
        return jdbc.sql(sql + " returning id").params(params).query(Long.class).single();
    }

    @BeforeEach
    void seed() {
        clearRedis();
        insert(
                "insert into notices(title, content, created_at, church_id) values (?, 'x', now(), ?)",
                A_TITLE,
                churches.aId());
        insert(
                "insert into notices(title, content, created_at, church_id) values (?, 'x', now(), ?)",
                B_TITLE,
                churches.bId());
        String sermon = "insert into sermons(title, preacher, preached_at, content, created_at, church_id)"
                + " values (?, 'p', current_date, 'x', now(), ?)";
        sermonA = insert(sermon, "격리A설교", churches.aId());
        sermonB = insert(sermon, "격리B설교", churches.bId());
    }

    @AfterEach
    void cleanup() {
        for (String table : List.of("notices", "sermons")) {
            jdbc.sql("delete from " + table + " where church_id in (?, ?)")
                    .params(churches.aId(), churches.bId())
                    .update();
        }
        clearRedis();
    }

    private void clearRedis() {
        for (String pattern : List.of("main::*", "view:*")) {
            Set<String> keys = redis.keys(pattern);
            if (keys != null && !keys.isEmpty()) {
                redis.delete(keys);
            }
        }
    }

    private void awaitNoKeys(String pattern) throws InterruptedException {
        for (int i = 0; i < 50 && !redis.keys(pattern).isEmpty(); i++) {
            Thread.sleep(100);
        }
    }

    private String main(String prefix) throws Exception {
        return mvc.perform(get(prefix + "/api/main"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
    }

    /** 스케줄러 스레드처럼 교회가 지정되지 않은 상태에서 flush를 돌린다. */
    private void flushLikeScheduler() {
        TenantContext.clear();
        try {
            scheduler.flush();
        } finally {
            TenantContext.set(TenantTestSupport.DEFAULT_CHURCH_ID);
        }
    }

    private long viewCount(long sermonId) {
        return jdbc.sql("select view_count from sermons where id = ?")
                .param(sermonId)
                .query(Long.class)
                .single();
    }

    private String viewerToken(long churchId) {
        return "Bearer "
                + TenantContext.callAs(
                        churchId,
                        () -> provider.issueAccess(
                                new MemberPrincipal(999_999L, UUID.randomUUID().toString(), "뷰어", 100),
                                null,
                                List.of("SERMON_VIEW")));
    }

    @Test
    void 메인_캐시는_교회별로_분리된다() throws Exception {
        String a1 = main(churches.aPrefix());
        String b1 = main(churches.bPrefix());
        String a2 = main(churches.aPrefix()); // 두 번째는 캐시
        String b2 = main(churches.bPrefix());

        assertThat(a1).contains(A_TITLE).doesNotContain(B_TITLE);
        assertThat(a2).contains(A_TITLE).doesNotContain(B_TITLE);
        assertThat(b1).contains(B_TITLE).doesNotContain(A_TITLE);
        assertThat(b2).contains(B_TITLE).doesNotContain(A_TITLE);
    }

    @Test
    void A의_캐시_무효화는_B의_메인_캐시를_지우지_않는다() throws Exception {
        main(churches.aPrefix());
        main(churches.bPrefix());
        assertThat(redis.keys("main::" + churches.aId() + "::*")).isNotEmpty();
        assertThat(redis.keys("main::" + churches.bId() + "::*")).isNotEmpty();

        // 콘텐츠 CUD의 @CacheEvict(allEntries = true)가 하는 일 — A 교회에서 캐시 전체 비우기
        churches.runInA(() -> cacheManager.getCache("main").clear());

        // Lettuce에서는 clear가 비동기로 실행된다(spring-data-redis 4). 지울 키 패턴은 호출 시점에 계산된다.
        awaitNoKeys("main::" + churches.aId() + "::*");
        assertThat(redis.keys("main::" + churches.aId() + "::*")).isEmpty();
        assertThat(redis.keys("main::" + churches.bId() + "::*")).isNotEmpty();
    }

    @Test
    void 조회수는_올바른_교회_행에만_반영된다() throws Exception {
        for (int i = 0; i < 3; i++) {
            mvc.perform(get(churches.aPrefix() + "/api/sermons/" + sermonA)
                            .header("Authorization", viewerToken(churches.aId())))
                    .andExpect(status().isOk());
        }
        for (int i = 0; i < 2; i++) {
            mvc.perform(get(churches.bPrefix() + "/api/sermons/" + sermonB)
                            .header("Authorization", viewerToken(churches.bId())))
                    .andExpect(status().isOk());
        }

        flushLikeScheduler();

        assertThat(viewCount(sermonA)).isEqualTo(3);
        assertThat(viewCount(sermonB)).isEqualTo(2);
    }

    @Test
    void 옛_형식_키가_있어도_flush가_실패하지_않고_새_키는_반영된다() throws Exception {
        redis.opsForValue().set("view:sermon:12", "5"); // 전환 전 형식 view:{ns}:{id}
        mvc.perform(get(churches.aPrefix() + "/api/sermons/" + sermonA)
                        .header("Authorization", viewerToken(churches.aId())))
                .andExpect(status().isOk());

        assertThatCode(this::flushLikeScheduler).doesNotThrowAnyException();

        assertThat(viewCount(sermonA)).isEqualTo(1);
        assertThat(redis.hasKey("view:sermon:12")).isTrue(); // 건드리지 않는다 — 운영 절차서에서 수동 삭제
    }
}
