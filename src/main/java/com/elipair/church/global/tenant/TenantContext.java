package com.elipair.church.global.tenant;

import java.util.function.Supplier;

/**
 * 현재 스레드가 처리 중인 교회 id(멀티테넌시, #86).
 *
 * <p>요청은 {@link TenantFilter}가, 요청 밖 코드(스케줄러 등)는 {@link #runAs}/{@link #callAs}가 지정한다.
 * Hibernate는 세션(트랜잭션)을 열 때 이 값을 읽어 고정하므로, 반드시 트랜잭션 <b>바깥</b>에서 감싼다.
 * 기본 교회·전체를 보는 교회는 없다 — 값이 없으면 {@link #require()}가 예외를 던진다.
 */
public final class TenantContext {

    private static final ThreadLocal<Long> CURRENT = new ThreadLocal<>();

    private TenantContext() {}

    public static Long currentOrNull() {
        return CURRENT.get();
    }

    public static long require() {
        Long churchId = CURRENT.get();
        if (churchId == null) {
            throw new IllegalStateException("테넌트가 지정되지 않았습니다");
        }
        return churchId;
    }

    public static void runAs(long churchId, Runnable work) {
        try (Scope ignored = enter(churchId)) {
            work.run();
        }
    }

    public static <T> T callAs(long churchId, Supplier<T> work) {
        try (Scope ignored = enter(churchId)) {
            return work.get();
        }
    }

    /** 같은 패키지 전용 — 체크 예외를 던지는 블록(필터 체인)을 감쌀 때 try-with-resources로 쓴다. */
    static Scope enter(long churchId) {
        Long previous = CURRENT.get();
        CURRENT.set(churchId);
        return () -> restore(previous);
    }

    private static void restore(Long previous) {
        if (previous == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(previous);
        }
    }

    @FunctionalInterface
    interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
