package com.elipair.church.global.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.elipair.church.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/** 경로 접두사 /church/{slug} 처리와, 접두사 아래에서도 기존 인가 규칙이 그대로인지 검증한다(#86). 기본 교회(dev)만 쓴다. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class TenantFilterTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void 접두사_공개경로는_200() throws Exception {
        mvc.perform(get("/church/dev/api/notices")).andExpect(status().isOk());
    }

    @Test
    void 접두사_관리자경로는_토큰없으면_401() throws Exception {
        mvc.perform(get("/church/dev/api/admin/members")).andExpect(status().isUnauthorized());
    }

    @Test
    void 접두사_회원전용경로는_토큰없으면_401() throws Exception {
        mvc.perform(get("/church/dev/api/sermons")).andExpect(status().isUnauthorized());
        mvc.perform(get("/church/dev/api/gallery/albums")).andExpect(status().isUnauthorized());
    }

    @Test
    void 없는_slug는_404() throws Exception {
        mvc.perform(get("/church/nope/api/notices"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void 형식이_틀린_slug는_404() throws Exception {
        mvc.perform(get("/church/DEV/api/notices")).andExpect(status().isNotFound());
        mvc.perform(get("/church/a%2Fb/api/notices")).andExpect(status().isNotFound());
    }

    @Test
    void 접두사_뒤가_api가_아니면_404() throws Exception {
        mvc.perform(get("/church/dev/actuator/health")).andExpect(status().isNotFound());
        mvc.perform(get("/church/dev/docs/swagger-ui.html")).andExpect(status().isNotFound());
        mvc.perform(get("/church/dev")).andExpect(status().isNotFound());
    }

    @Test
    void 접두사는_church만_인식한다() throws Exception {
        // 다른 접두사(예: 초기 설계의 /c/)는 교회 주소가 아니다 — 매핑이 없으므로 404.
        mvc.perform(get("/c/dev/api/notices")).andExpect(status().isNotFound());
        mvc.perform(get("/churches/dev/api/notices")).andExpect(status().isNotFound());
    }

    @Test
    void 접두사_없으면_기본_교회로_처리() throws Exception {
        mvc.perform(get("/api/notices")).andExpect(status().isOk());
    }

    @Test
    void 헬스는_교회_없이_통과() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void 요청이_끝나면_컨텍스트가_이전값으로() {
        TenantContext.runAs(99L, () -> {
            try {
                mvc.perform(get("/church/dev/api/notices"));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            assertThat(TenantContext.require()).isEqualTo(99L);
        });
    }
}
