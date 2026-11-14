package com.elipair.church.global.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import com.elipair.church.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ChurchResolverTest {

    @Autowired
    private ChurchResolver resolver;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void 등록된_slug를_찾는다() {
        assertThat(resolver.findBySlug("dev"))
                .get()
                .extracting(ChurchInfo::frontendOrigin)
                .isEqualTo("http://localhost:3000");
    }

    @Test
    void 없는_slug는_empty() {
        assertThat(resolver.findBySlug("nope")).isEmpty();
    }

    @Test
    void 형식이_틀리면_empty() {
        assertThat(resolver.findBySlug("A/../b")).isEmpty();
        assertThat(resolver.findBySlug("")).isEmpty();
        assertThat(resolver.findBySlug(null)).isEmpty();
    }

    @Test
    void 새로_넣은_교회를_재시작_없이_찾는다() {
        jdbc.sql("insert into churches(name,slug,frontend_origin) values('R','resolver-late','https://r.example')"
                        + " on conflict (slug) do nothing")
                .update();
        assertThat(resolver.findBySlug("resolver-late")).isPresent();
    }
}
