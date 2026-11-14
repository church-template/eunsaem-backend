package com.elipair.church.isolation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.elipair.church.TestcontainersConfiguration;
import com.elipair.church.global.security.JwtTokenProvider;
import com.elipair.church.global.security.MemberPrincipal;
import com.elipair.testfixture.TwoChurches;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 미디어의 교회 격리(#86). 파일 로드는 키만 받으므로 교회 검사를 하지 않는다 — 격리는 media 행 조회가
 * 테넌트 필터를 거치는 것으로 보장된다. 공개 주소(GET /api/media/{id})는 id가 순차라 열거 대상이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TwoChurches.class})
class MediaIsolationTest {

    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 1, 2, 3, 4, 5, 6, 7};

    @TempDir
    static Path uploadDir;

    @DynamicPropertySource
    static void fileProps(DynamicPropertyRegistry registry) {
        registry.add("file.upload-dir", () -> uploadDir.toString());
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private TwoChurches churches;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private JwtTokenProvider provider;

    @AfterEach
    void cleanup() {
        for (String table : List.of("media", "members")) {
            jdbc.sql("delete from " + table + " where church_id in (?, ?)")
                    .params(churches.aId(), churches.bId())
                    .update();
        }
    }

    @Test
    void A의_미디어를_B주소로_요청하면_404이고_저장_키는_A의_접두사다() throws Exception {
        long uploader = jdbc.sql("insert into members(uuid, phone, name, password, created_at, church_id)"
                        + " values (gen_random_uuid(), '01044440001', '업로더', 'p', now(), ?) returning id")
                .param(churches.aId())
                .query(Long.class)
                .single();
        String token = "Bearer "
                + churches.inA(() -> provider.issueAccess(
                        new MemberPrincipal(uploader, "uuid-uploader", "업로더", 900), null, List.of("MEDIA_MANAGE")));

        String json = mvc.perform(multipart(churches.aPrefix() + "/api/admin/media")
                        .file(new MockMultipartFile("file", "photo.jpg", "image/jpeg", JPEG))
                        .header("Authorization", token))
                .andExpect(status().is2xxSuccessful())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        long mediaId = ((Number) JsonPath.read(json, "$.id")).longValue();

        mvc.perform(get(churches.aPrefix() + "/api/media/" + mediaId)).andExpect(status().isOk());
        mvc.perform(get(churches.bPrefix() + "/api/media/" + mediaId)).andExpect(status().isNotFound());
        mvc.perform(get("/api/media/" + mediaId)).andExpect(status().isNotFound()); // 기본 교회에서도 안 보인다

        String storedPath = jdbc.sql("select stored_path from media where id = ?")
                .param(mediaId)
                .query(String.class)
                .single();
        assertThat(storedPath).startsWith("churches/" + churches.aId() + "/");
    }
}
