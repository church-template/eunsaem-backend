package com.elipair.testfixture;

import com.elipair.church.global.tenant.ChurchResolver;
import com.elipair.church.global.tenant.TenantContext;
import java.util.function.Supplier;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 격리 테스트용 교회 A·B(#86). 둘 다 기본 교회(dev)가 <b>아니다</b>.
 *
 * <p>{@link TenantTestSupport}가 모든 테스트 스레드에 기본 교회를 깔아 두므로, A가 기본 교회면 runAs를 빠뜨린 코드도
 * A 쪽에서 통과해 버린다. 격리 테스트는 요청을 항상 {@link #aPrefix()}/{@link #bPrefix()}로 보내고,
 * 데이터 준비는 {@link #inA}/{@link #inB} 안에서만 한다. 쓰려면 테스트 클래스에 {@code @Import(TwoChurches.class)}.
 */
@TestComponent
public class TwoChurches {

    public static final String A_SLUG = "iso-a";
    public static final String B_SLUG = "iso-b";
    public static final String A_ORIGIN = "https://a.example";
    public static final String B_ORIGIN = "https://b.example";

    private final long aId;
    private final long bId;

    public TwoChurches(JdbcClient jdbc, ChurchResolver resolver) {
        insert(jdbc, "격리A", A_SLUG, A_ORIGIN);
        insert(jdbc, "격리B", B_SLUG, B_ORIGIN);
        this.aId = resolver.findBySlug(A_SLUG).orElseThrow().id();
        this.bId = resolver.findBySlug(B_SLUG).orElseThrow().id();
    }

    private static void insert(JdbcClient jdbc, String name, String slug, String origin) {
        jdbc.sql("insert into churches(name, slug, frontend_origin) values (?, ?, ?) on conflict (slug) do nothing")
                .params(name, slug, origin)
                .update();
    }

    public long aId() {
        return aId;
    }

    public long bId() {
        return bId;
    }

    public String aPrefix() {
        return "/c/" + A_SLUG;
    }

    public String bPrefix() {
        return "/c/" + B_SLUG;
    }

    public <T> T inA(Supplier<T> work) {
        return TenantContext.callAs(aId, work);
    }

    public <T> T inB(Supplier<T> work) {
        return TenantContext.callAs(bId, work);
    }

    public void runInA(Runnable work) {
        TenantContext.runAs(aId, work);
    }

    public void runInB(Runnable work) {
        TenantContext.runAs(bId, work);
    }
}
