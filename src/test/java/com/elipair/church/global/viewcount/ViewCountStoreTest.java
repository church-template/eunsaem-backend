package com.elipair.church.global.viewcount;

import static org.assertj.core.api.Assertions.assertThat;

import com.elipair.church.TestcontainersConfiguration;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ViewCountStoreTest {

    /** 테스트 스레드의 기본 교회(TenantTestSupport). */
    private static final long CHURCH = com.elipair.testfixture.TenantTestSupport.DEFAULT_CHURCH_ID;

    @Autowired
    private ViewCountStore store;

    @Autowired
    private StringRedisTemplate redis;

    @AfterEach
    void cleanup() {
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
    }

    @Test
    void increment_returns_running_buffer_value() {
        assertThat(store.increment("sermon", 7L)).isEqualTo(1L);
        assertThat(store.increment("sermon", 7L)).isEqualTo(2L);
    }

    @Test
    void drain_returns_deltas_and_clears_keys() {
        store.increment("sermon", 7L);
        store.increment("sermon", 7L);
        store.increment("sermon", 9L);

        Map<Long, Long> deltas = store.drain("sermon").get(CHURCH);

        assertThat(deltas).containsEntry(7L, 2L).containsEntry(9L, 1L);
        // 비운 뒤 재증가는 1부터
        assertThat(store.increment("sermon", 7L)).isEqualTo(1L);
        assertThat(store.drain("sermon").get(CHURCH)).containsExactly(Map.entry(7L, 1L));
    }

    @Test
    void drain_isolates_namespaces() {
        store.increment("sermon", 1L);
        store.increment("notice", 1L);

        assertThat(store.drain("sermon").get(CHURCH)).containsExactly(Map.entry(1L, 1L));
        assertThat(store.drain("notice").get(CHURCH)).containsExactly(Map.entry(1L, 1L));
    }

    @Test
    void drain_groups_by_church_and_skips_legacy_keys() {
        store.increment("sermon", 1L); // 기본 교회
        store.incrementBy(42L, "sermon", 1L, 5L);
        redis.opsForValue().set("view:sermon:9", "3"); // 전환 전 형식

        Map<Long, Map<Long, Long>> byChurch = store.drain("sermon");

        assertThat(byChurch).containsOnlyKeys(CHURCH, 42L);
        assertThat(byChurch.get(CHURCH)).containsExactly(Map.entry(1L, 1L));
        assertThat(byChurch.get(42L)).containsExactly(Map.entry(1L, 5L));
        assertThat(redis.hasKey("view:sermon:9")).isTrue();
    }
}
