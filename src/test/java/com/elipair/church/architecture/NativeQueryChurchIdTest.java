package com.elipair.church.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

/**
 * 네이티브 쿼리는 Hibernate 테넌트 필터가 걸리지 않는다(#86). 그래서 모든 네이티브 쿼리는
 * 직접 {@code church_id} 조건을 가져야 한다 — 빠지면 다른 교회 데이터가 그대로 읽힌다.
 * 예외는 데이터를 읽지 않는 {@code pg_advisory_xact_lock} 하나뿐이다.
 */
class NativeQueryChurchIdTest {

    /** 한 메서드의 위반 사유 목록(없으면 빈 목록). */
    static List<String> violations(Method method) {
        Query query = method.getAnnotation(Query.class);
        if (query == null || !query.nativeQuery() || query.value().contains("pg_advisory_xact_lock")) {
            return List.of();
        }
        List<String> found = new ArrayList<>();
        String name = method.getDeclaringClass().getSimpleName() + "." + method.getName();
        if (!query.value().contains("church_id")) {
            found.add(name + ": value에 church_id 조건이 없다");
        }
        if (!query.countQuery().isEmpty() && !query.countQuery().contains("church_id")) {
            found.add(name + ": countQuery에 church_id 조건이 없다");
        }
        return found;
    }

    private static List<Method> nativeQueryMethods() throws ClassNotFoundException {
        List<Method> methods = new ArrayList<>();
        for (JavaClass type : new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("com.elipair.church.domain")) {
            if (!type.isInterface()) {
                continue;
            }
            for (Method method : Class.forName(type.getName()).getDeclaredMethods()) {
                Query query = method.getAnnotation(Query.class);
                if (query != null && query.nativeQuery()) {
                    methods.add(method);
                }
            }
        }
        return methods;
    }

    @Test
    void 모든_네이티브_쿼리는_church_id_조건을_가진다() throws Exception {
        List<Method> methods = nativeQueryMethods();
        // 검사 대상이 0개면 무의미하게 통과한다 — 현재 개수를 고정한다(데이터 쿼리 9 + advisory lock 1).
        // 네이티브 쿼리를 추가·삭제하면 이 숫자도 함께 고친다.
        assertThat(methods).hasSize(10);
        assertThat(methods.stream().flatMap(m -> violations(m).stream()).toList())
                .isEmpty();
    }

    @Test
    void 검사_함수는_church_id가_없는_쿼리를_위반으로_잡는다() throws Exception {
        assertThat(violations(Bad.class.getDeclaredMethod("noChurch"))).hasSize(1);
        assertThat(violations(Bad.class.getDeclaredMethod("countWithoutChurch")))
                .hasSize(1);
        assertThat(violations(Bad.class.getDeclaredMethod("ok"))).isEmpty();
        assertThat(violations(Bad.class.getDeclaredMethod("lock"))).isEmpty();
    }

    interface Bad {
        @Query(value = "select id from sermons where deleted_at is null", nativeQuery = true)
        List<Long> noChurch();

        @Query(
                value = "select id from sermons where church_id = :churchId",
                countQuery = "select count(*) from sermons",
                nativeQuery = true)
        List<Long> countWithoutChurch();

        @Query(value = "select id from sermons where church_id = :churchId", nativeQuery = true)
        List<Long> ok();

        @Query(value = "select pg_advisory_xact_lock(:key)", nativeQuery = true)
        void lock();
    }
}
