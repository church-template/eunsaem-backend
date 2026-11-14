package com.elipair.church.global.tenant;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * slug → 교회 조회(#86).
 *
 * <p>JPA가 아니라 JdbcClient로 읽는다. 교회를 찾는 시점은 테넌트가 정해지기 전이라, JPA로 읽으면
 * 테넌트 리졸버의 fail-closed 예외에 걸린다. 이를 피하려고 "기본 테넌트"를 두는 것은 금지다.
 *
 * <p>찾은 교회는 메모리에 둔다(교회 수십 곳). 못 찾은 slug는 짧게 기억해 반복 조회가 매번 DB를 치지 않게 하고,
 * 그 시간이 지나면 다시 읽으므로 새 교회는 재시작 없이 붙는다. 교회의 frontend_origin을 DB에서 바꾸면 재시작해야 반영된다.
 */
@Component
public class ChurchResolver {

    private static final Pattern SLUG = Pattern.compile("^[a-z0-9-]{1,40}$");
    private static final long MISS_TTL_NANOS = 30_000_000_000L;
    private static final int MAX_MISSES = 1_000;

    private final JdbcClient jdbc;
    private final Map<String, ChurchInfo> found = new ConcurrentHashMap<>();
    private final Map<String, Long> missedAt = new ConcurrentHashMap<>();

    public ChurchResolver(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<ChurchInfo> findBySlug(String slug) {
        if (slug == null || !SLUG.matcher(slug).matches()) {
            return Optional.empty();
        }
        ChurchInfo cached = found.get(slug);
        if (cached != null) {
            return Optional.of(cached);
        }
        Long missed = missedAt.get(slug);
        if (missed != null && System.nanoTime() - missed < MISS_TTL_NANOS) {
            return Optional.empty();
        }
        Optional<ChurchInfo> loaded = jdbc.sql("select id, slug, name, frontend_origin from churches where slug = ?")
                .param(slug)
                .query((rs, rowNum) -> new ChurchInfo(
                        rs.getLong("id"), rs.getString("slug"), rs.getString("name"), rs.getString("frontend_origin")))
                .optional();
        if (loaded.isPresent()) {
            found.put(slug, loaded.get());
            missedAt.remove(slug);
        } else {
            if (missedAt.size() >= MAX_MISSES) {
                missedAt.clear(); // 없는 slug를 무작위로 두드려도 메모리가 무한히 늘지 않게 한다
            }
            missedAt.put(slug, System.nanoTime());
        }
        return loaded;
    }
}
