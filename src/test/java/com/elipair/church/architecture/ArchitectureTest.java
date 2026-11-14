package com.elipair.church.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * 패키지-by-feature 아키텍처 규칙을 강제한다.
 * global 은 도메인 횡단 공통이므로 domain 에 의존하면 안 된다(domain → global 단방향).
 * 도메인이 추가될수록 이 규칙이 의존 방향 역전을 조기에 잡아준다.
 */
@AnalyzeClasses(packages = "com.elipair.church", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule global_must_not_depend_on_domain = noClasses()
            .that()
            .resideInAPackage("com.elipair.church.global..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.elipair.church.domain..")
            .because("global은 도메인 횡단 공통이므로 domain에 의존하면 안 된다 (domain → global 단방향)")
            .allowEmptyShould(true);

    /**
     * TenantContext.set/clear는 복원이 보장되지 않는 테스트 지원 전용 API다(#86).
     * 메인 코드가 직접 부르면 다른 요청·작업에 교회가 새어 나갈 수 있으므로 runAs/callAs만 쓰게 한다.
     */
    @ArchTest
    static final ArchRule main_code_must_not_set_tenant_directly = noClasses()
            .that()
            .doNotHaveFullyQualifiedName("com.elipair.church.global.tenant.TenantContext")
            .should()
            .callMethod(com.elipair.church.global.tenant.TenantContext.class, "set", Long.class)
            .orShould()
            .callMethod(com.elipair.church.global.tenant.TenantContext.class, "clear")
            .because("메인 코드는 복원이 보장되는 TenantContext.runAs/callAs만 쓴다");
}
