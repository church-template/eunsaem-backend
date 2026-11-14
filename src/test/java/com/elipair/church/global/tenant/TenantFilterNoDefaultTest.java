package com.elipair.church.global.tenant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.elipair.church.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "tenant.default-slug=")
class TenantFilterNoDefaultTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void 기본교회_설정이_없으면_접두사없는_api는_404() throws Exception {
        mvc.perform(get("/api/notices")).andExpect(status().isNotFound());
    }

    @Test
    void 기본교회_설정이_없어도_접두사_경로와_헬스는_동작() throws Exception {
        mvc.perform(get("/c/dev/api/notices")).andExpect(status().isOk());
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }
}
