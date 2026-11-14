package com.elipair.church.global.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.elipair.church.global.exception.BusinessException;
import com.elipair.church.global.exception.ErrorCode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.Resource;
import org.springframework.mock.web.MockMultipartFile;

/**
 * @TempDir 기반 단위 테스트(Spring 컨텍스트 불필요). maxSize=1024로 설정.
 */
class LocalFileStorageTest {

    @TempDir
    Path tempDir;

    private LocalFileStorage storage;

    @BeforeEach
    void setUp() {
        storage = new LocalFileStorage(new FileProperties(tempDir.toString(), "http://localhost/api/media", 1024));
    }

    private MockMultipartFile file(String name, byte[] content) {
        return new MockMultipartFile("file", name, "image/jpeg", content);
    }

    @Test
    void store_writes_file_and_returns_sharded_key() throws IOException {
        byte[] content = "hello".getBytes(StandardCharsets.UTF_8);
        String key = storage.store(file("photo.JPG", content));

        assertThat(key).matches("churches/1/\\d{4}/\\d{2}/[0-9a-f-]{36}\\.jpg");
        assertThat(Files.readAllBytes(tempDir.resolve(key))).isEqualTo(content);
    }

    @Test
    void store_rejects_oversized_file() {
        byte[] big = new byte[1025]; // maxSize=1024
        assertThatThrownBy(() -> storage.store(file("big.jpg", big)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.FILE_SIZE_EXCEEDED);
    }

    @Test
    void store_rejects_empty_file() {
        assertThatThrownBy(() -> storage.store(file("empty.jpg", new byte[0])))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_INPUT_VALUE);
    }

    @Test
    void store_without_extension_succeeds() {
        String key = storage.store(file("noext", "x".getBytes(StandardCharsets.UTF_8)));
        assertThat(key).matches("churches/1/\\d{4}/\\d{2}/[0-9a-f-]{36}");
    }

    @Test
    void store_sanitizes_malicious_filename_extension() {
        for (String name : new String[] {"a.jpg/../evil", "weird.<script>", "a."}) {
            String key = storage.store(file(name, "x".getBytes(StandardCharsets.UTF_8)));
            assertThat(key).matches("churches/1/\\d{4}/\\d{2}/[0-9a-f-]{36}(\\.[a-z0-9]{1,10})?");
            assertThat(key).doesNotContain("..", "<", ">");
            assertThat(tempDir.resolve(key).normalize()).startsWith(tempDir);
        }
    }

    @Test
    void load_returns_readable_resource() throws IOException {
        byte[] content = "data".getBytes(StandardCharsets.UTF_8);
        String key = storage.store(file("a.png", content));

        Resource resource = storage.load(key);

        assertThat(resource.getContentAsByteArray()).isEqualTo(content);
    }

    @Test
    void load_missing_throws_not_found() {
        assertThatThrownBy(() -> storage.load("2026/06/does-not-exist.jpg"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    void load_path_traversal_throws_not_found() {
        assertThatThrownBy(() -> storage.load("../../etc/passwd"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    void load_directory_throws_not_found() {
        String key = storage.store(file("a.jpg", "x".getBytes(StandardCharsets.UTF_8)));
        String dir = key.substring(0, key.indexOf('/', key.indexOf('/') + 1)); // "yyyy/MM"

        assertThatThrownBy(() -> storage.load(dir))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    void delete_removes_existing_file() {
        String key = storage.store(file("a.jpg", "x".getBytes(StandardCharsets.UTF_8)));

        storage.delete(key);

        assertThat(Files.exists(tempDir.resolve(key))).isFalse();
    }

    @Test
    void delete_missing_is_noop() {
        assertThatCode(() -> storage.delete("2026/06/missing.jpg")).doesNotThrowAnyException();
    }

    @Test
    void delete_path_traversal_is_noop_and_keeps_external_file() throws IOException {
        Path outside = tempDir.resolveSibling("external-" + tempDir.getFileName() + ".txt");
        Files.writeString(outside, "keep");
        try {
            String traversal = "../" + outside.getFileName();
            assertThatCode(() -> storage.delete(traversal)).doesNotThrowAnyException();
            assertThat(Files.exists(outside)).isTrue();
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void delete_directory_is_noop() {
        String key = storage.store(file("a.jpg", "x".getBytes(StandardCharsets.UTF_8)));
        String dir = key.substring(0, key.indexOf('/', key.indexOf('/') + 1));

        assertThatCode(() -> storage.delete(dir)).doesNotThrowAnyException();
        assertThat(Files.exists(tempDir.resolve(dir))).isTrue();
    }

    @Test
    void store_io_failure_throws_storage_error_and_leaves_no_partial_file() throws IOException {
        // getInputStream은 성공하되 read 도중 IOException → Files.copy 실패 경로를 유도한다.
        MockMultipartFile failing = new MockMultipartFile("file", "x.jpg", "image/jpeg", new byte[10]) {
            @Override
            public InputStream getInputStream() {
                return new InputStream() {
                    @Override
                    public int read() throws IOException {
                        throw new IOException("boom");
                    }
                };
            }
        };

        assertThatThrownBy(() -> storage.store(failing))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.FILE_STORAGE_ERROR);

        // best-effort 정리로 부분 저장 파일이 남지 않아야 한다(디렉터리는 남을 수 있음).
        try (Stream<Path> paths = Files.walk(tempDir)) {
            assertThat(paths.filter(Files::isRegularFile)).isEmpty();
        }
    }

    @Test
    void load_and_delete_ignore_symlink_within_root_pointing_outside() throws IOException {
        // 루트 내부에 심어진 심볼릭 링크가 루트 밖 파일을 가리켜도 추종하지 않아야 한다(NOFOLLOW_LINKS).
        Path external = tempDir.resolveSibling("ext-" + tempDir.getFileName() + ".txt");
        Files.writeString(external, "secret");
        Path linkDir = tempDir.resolve("2026/06");
        Files.createDirectories(linkDir);
        Path link = linkDir.resolve("link.txt");
        try {
            Files.createSymbolicLink(link, external);
        } catch (IOException | UnsupportedOperationException e) {
            Files.deleteIfExists(external);
            Assumptions.abort("심볼릭 링크 미지원 플랫폼");
        }
        try {
            // load: 심볼릭 링크는 일반 파일로 취급하지 않음 → 404, 외부 파일 미노출
            assertThatThrownBy(() -> storage.load("2026/06/link.txt"))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
            // delete: 심볼릭 링크는 no-op → 외부 파일 보존
            assertThatCode(() -> storage.delete("2026/06/link.txt")).doesNotThrowAnyException();
            assertThat(Files.exists(external)).isTrue();
        } finally {
            Files.deleteIfExists(link);
            Files.deleteIfExists(external);
        }
    }

    /** 멀티테넌시(#86): 새 파일의 키는 교회별 접두사로 시작한다 — S3로 옮길 때 그대로 객체 키가 된다. */
    @Test
    void store_key_starts_with_church_prefix() {
        String key = com.elipair.church.global.tenant.TenantContext.callAs(
                7L, () -> storage.store(file("a.jpg", "x".getBytes(StandardCharsets.UTF_8))));
        assertThat(key).matches("churches/7/\\d{4}/\\d{2}/[0-9a-f-]{36}\\.jpg");
    }

    /** 전환 전에 저장된 파일(접두사 없는 키)은 옮기지 않는다 — 키만으로 그대로 읽고 지울 수 있어야 한다. */
    @Test
    void legacy_key_without_church_prefix_still_loads_and_deletes() throws IOException {
        Path legacy = tempDir.resolve("2026/06/legacy.jpg");
        Files.createDirectories(legacy.getParent());
        Files.write(legacy, "old".getBytes(StandardCharsets.UTF_8));

        assertThat(storage.load("2026/06/legacy.jpg").getContentAsByteArray())
                .isEqualTo("old".getBytes(StandardCharsets.UTF_8));
        storage.delete("2026/06/legacy.jpg");
        assertThat(legacy).doesNotExist();
    }
}
