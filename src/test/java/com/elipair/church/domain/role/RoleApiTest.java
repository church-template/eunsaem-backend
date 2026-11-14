package com.elipair.church.domain.role;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.elipair.church.TestcontainersConfiguration;
import com.elipair.church.global.security.JwtTokenProvider;
import com.elipair.church.global.security.MemberPrincipal;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class RoleApiTest {

    private static final Set<String> SEED_ROLES = Set.of("SUPER_ADMIN", "ADMIN", "MEMBER", "USER");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider provider;

    @Autowired
    private RoleRepository roleRepository;

    /** 시드 역할은 보존하고 테스트가 만든 역할만 제거. */
    @AfterEach
    void cleanup() {
        roleRepository.deleteAll(roleRepository.findAll().stream()
                .filter(r -> !SEED_ROLES.contains(r.getName()))
                .toList());
    }

    /** maxPriority·permissions를 가진 요청자 토큰. */
    private String token(int maxPriority, String... permissions) {
        return "Bearer "
                + provider.issueAccess(
                        new MemberPrincipal(1L, "uuid-admin", "관리자", maxPriority), null, List.of(permissions));
    }

    private String roleManager() {
        return token(1000, "ROLE_MANAGE");
    }

    @Test
    void lists_seed_roles_priority_desc_with_permissions() throws Exception {
        mockMvc.perform(get("/api/admin/roles").header("Authorization", roleManager()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].name").value("SUPER_ADMIN")) // priority 1000 최상위
                .andExpect(jsonPath("$[0].permissions.length()")
                        .value(18)) // V13 2건 + V14 INQUIRY_MANAGE + V15 SERMON_VIEW + V16 VEHICLE_* 2건 포함
                .andExpect(jsonPath("$.page").doesNotExist());
    }

    @Test
    void anonymous_is_401() throws Exception {
        mockMvc.perform(get("/api/admin/roles"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("INVALID_TOKEN"));
    }

    @Test
    void without_role_manage_is_403() throws Exception {
        mockMvc.perform(get("/api/admin/roles").header("Authorization", token(100, "SERMON_WRITE")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"));
    }

    private long createRole(String name, int priority) throws Exception {
        String json = mockMvc.perform(post("/api/admin/roles")
                        .header("Authorization", roleManager())
                        .characterEncoding(StandardCharsets.UTF_8)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"priority\":" + priority + ",\"description\":\"d\"}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        return ((Number) JsonPath.read(json, "$.id")).longValue();
    }

    @Test
    void create_returns_201_non_system_empty_permissions() throws Exception {
        mockMvc.perform(post("/api/admin/roles")
                        .header("Authorization", roleManager())
                        .characterEncoding(StandardCharsets.UTF_8)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"EDITOR\",\"priority\":500,\"description\":\"편집자\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("EDITOR"))
                .andExpect(jsonPath("$.isSystem").value(false))
                .andExpect(jsonPath("$.permissions.length()").value(0));
    }

    @Test
    void create_priority_above_requester_is_403() throws Exception {
        mockMvc.perform(post("/api/admin/roles")
                        .header("Authorization", token(900, "ROLE_MANAGE"))
                        .characterEncoding(StandardCharsets.UTF_8)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"TOOHIGH\",\"priority\":901,\"description\":\"d\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"));
    }

    @Test
    void create_duplicate_name_is_409() throws Exception {
        createRole("EDITOR", 500);

        mockMvc.perform(post("/api/admin/roles")
                        .header("Authorization", roleManager())
                        .characterEncoding(StandardCharsets.UTF_8)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"EDITOR\",\"priority\":600,\"description\":\"d\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("DUPLICATE_RESOURCE"));
    }

    @Test
    void create_blank_name_is_400() throws Exception {
        mockMvc.perform(post("/api/admin/roles")
                        .header("Authorization", roleManager())
                        .characterEncoding(StandardCharsets.UTF_8)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"  \",\"priority\":500}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT_VALUE"));
    }

    /**
     * 역할의 priority를 정할 때(생성·변경)는 요청자보다 엄격히 낮아야 한다.
     * 같아도 되면, ADMIN이 낮은 역할을 누군가에게 준 뒤 그 역할을 자기 등급까지 올려 "동급은 위임 불가" 규칙을 우회한다.
     */
    @Test
    void create_priority_equal_to_requester_is_403() throws Exception {
        mockMvc.perform(post("/api/admin/roles")
                        .header("Authorization", token(900, "ROLE_MANAGE"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"PEER\",\"priority\":900,\"description\":\"d\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"));
    }

    @Test
    void patch_raising_priority_to_requester_level_is_403_but_just_below_is_allowed() throws Exception {
        long id = createRole("HELPER", 100);

        mockMvc.perform(patch("/api/admin/roles/" + id)
                        .header("Authorization", token(900, "ROLE_MANAGE"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"priority\":900}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(patch("/api/admin/roles/" + id)
                        .header("Authorization", token(900, "ROLE_MANAGE"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"priority\":899}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.priority").value(899));
    }

    @Test
    void patch_keeping_the_same_priority_on_an_equal_level_role_is_allowed() throws Exception {
        // 동급 역할의 이름·설명 수정은 계속 허용된다 — priority를 그대로 보내도(전체 폼 전송) 변경이 아니므로 통과.
        long id = createRole("PEERROLE", 900); // 최고 관리자(1000)가 만든 900 역할

        mockMvc.perform(patch("/api/admin/roles/" + id)
                        .header("Authorization", token(900, "ROLE_MANAGE"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"priority\":900,\"description\":\"설명 수정\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value("설명 수정"));
    }

    @Test
    void patch_updates_priority_within_level() throws Exception {
        long id = createRole("EDITOR", 500);

        mockMvc.perform(patch("/api/admin/roles/" + id)
                        .header("Authorization", roleManager())
                        .characterEncoding(StandardCharsets.UTF_8)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"priority\":700}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("EDITOR"))
                .andExpect(jsonPath("$.priority").value(700));
    }

    @Test
    void patch_system_role_is_403() throws Exception {
        long systemId = roleRepository.findAll().stream()
                .filter(r -> r.getName().equals("ADMIN"))
                .findFirst()
                .orElseThrow()
                .getId();

        mockMvc.perform(patch("/api/admin/roles/" + systemId)
                        .header("Authorization", roleManager())
                        .characterEncoding(StandardCharsets.UTF_8)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"변경시도\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"));
    }

    @Test
    void patch_unknown_id_is_404() throws Exception {
        mockMvc.perform(patch("/api/admin/roles/999999")
                        .header("Authorization", roleManager())
                        .characterEncoding(StandardCharsets.UTF_8)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"x\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void delete_returns_204_and_removes() throws Exception {
        long id = createRole("EDITOR", 500);

        mockMvc.perform(delete("/api/admin/roles/" + id).header("Authorization", roleManager()))
                .andExpect(status().isNoContent());

        assertThat(roleRepository.existsById(id)).isFalse();
    }

    @Test
    void delete_system_role_is_403() throws Exception {
        long systemId = roleRepository.findAll().stream()
                .filter(r -> r.getName().equals("USER"))
                .findFirst()
                .orElseThrow()
                .getId();

        mockMvc.perform(delete("/api/admin/roles/" + systemId).header("Authorization", roleManager()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"));
    }

    @Test
    void put_permissions_replaces_set() throws Exception {
        long id = createRole("EDITOR", 500);

        mockMvc.perform(put("/api/admin/roles/" + id + "/permissions")
                        .header("Authorization", roleManager())
                        .characterEncoding(StandardCharsets.UTF_8)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"permissions\":[\"SERMON_WRITE\",\"NOTICE_WRITE\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions.length()").value(2));

        // 재설정으로 전체 교체 확인
        mockMvc.perform(put("/api/admin/roles/" + id + "/permissions")
                        .header("Authorization", roleManager())
                        .characterEncoding(StandardCharsets.UTF_8)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"permissions\":[\"GALLERY_VIEW\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions.length()").value(1))
                .andExpect(jsonPath("$.permissions[0].name").value("GALLERY_VIEW"));
    }

    @Test
    void put_unknown_permission_is_400() throws Exception {
        long id = createRole("EDITOR", 500);

        mockMvc.perform(put("/api/admin/roles/" + id + "/permissions")
                        .header("Authorization", roleManager())
                        .characterEncoding(StandardCharsets.UTF_8)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"permissions\":[\"NOPE\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT_VALUE"));
    }

    @Test
    void put_permissions_on_system_role_is_403() throws Exception {
        long systemId = roleRepository.findAll().stream()
                .filter(r -> r.getName().equals("USER"))
                .findFirst()
                .orElseThrow()
                .getId();

        mockMvc.perform(put("/api/admin/roles/" + systemId + "/permissions")
                        .header("Authorization", roleManager())
                        .characterEncoding(StandardCharsets.UTF_8)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"permissions\":[\"GALLERY_VIEW\"]}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"));
    }

    @Test
    void put_empty_permissions_clears_all() throws Exception {
        long id = createRole("EDITOR", 500);

        mockMvc.perform(put("/api/admin/roles/" + id + "/permissions")
                        .header("Authorization", roleManager())
                        .characterEncoding(StandardCharsets.UTF_8)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"permissions\":[\"SERMON_WRITE\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions.length()").value(1));

        mockMvc.perform(put("/api/admin/roles/" + id + "/permissions")
                        .header("Authorization", roleManager())
                        .characterEncoding(StandardCharsets.UTF_8)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"permissions\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions.length()").value(0));
    }
}
