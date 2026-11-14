package com.elipair.church.global.storage;

import com.elipair.church.global.exception.BusinessException;
import com.elipair.church.global.exception.ErrorCode;
import com.elipair.church.global.tenant.TenantContext;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

/**
 * 로컬 디스크 파일 저장 1차 구현(스펙 §8). FILE_UPLOAD_DIR 아래 yyyy/MM/{uuid}.{ext}로 저장한다.
 * FileProperties 등록은 유일 소비자인 본 클래스에 colocate(@EnableConfigurationProperties).
 */
@Component
@EnableConfigurationProperties(FileProperties.class)
public class LocalFileStorage implements FileStorage {

    private static final DateTimeFormatter SHARD = DateTimeFormatter.ofPattern("yyyy/MM");
    private static final Pattern SAFE_EXT = Pattern.compile("^[a-z0-9]{1,10}$");

    private final Path root;
    private final long maxSize;

    public LocalFileStorage(FileProperties properties) {
        this.root = Paths.get(properties.uploadDir()).toAbsolutePath().normalize();
        this.maxSize = properties.maxSize();
    }

    @Override
    public String store(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE, "빈 파일은 저장할 수 없습니다");
        }
        if (file.getSize() > maxSize) {
            throw new BusinessException(ErrorCode.FILE_SIZE_EXCEEDED);
        }
        // 멀티테넌시(#86): 키는 교회별 접두사로 시작한다. 키는 불투명 값이라 전환 전 파일(접두사 없음)도 그대로 유효하고,
        // S3로 옮길 때는 이 키가 곧 객체 키가 된다. load/delete는 키만 받으며 교회 검사는 media 행 조회가 담당한다.
        String key = "churches/" + TenantContext.require() + "/" + SHARD.format(LocalDate.now()) + "/"
                + UUID.randomUUID() + extensionSuffix(file.getOriginalFilename());
        Path target = root.resolve(key).normalize();
        if (!target.startsWith(root)) {
            throw new BusinessException(ErrorCode.FILE_STORAGE_ERROR);
        }
        try (InputStream in = file.getInputStream()) {
            Files.createDirectories(target.getParent());
            Files.copy(in, target);
        } catch (FileAlreadyExistsException e) {
            // target이 이미 존재(UUID 충돌, 사실상 불가) → 우리가 쓴 부분 파일이 아니므로 삭제하지 않는다(기존 파일 보존).
            throw new BusinessException(ErrorCode.FILE_STORAGE_ERROR);
        } catch (IOException e) {
            deleteQuietly(target); // 우리가 쓰던 부분 저장 파일 best-effort 정리
            throw new BusinessException(ErrorCode.FILE_STORAGE_ERROR);
        }
        return key;
    }

    @Override
    public Resource load(String storedPath) {
        Path target = resolveWithinRoot(storedPath);
        if (target == null || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) || !Files.isReadable(target)) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        return new FileSystemResource(target);
    }

    @Override
    public void delete(String storedPath) {
        Path target = resolveWithinRoot(storedPath);
        if (target == null || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            return; // 미존재·루트밖·디렉터리·심볼릭 링크 → no-op (idempotent)
        }
        try {
            Files.deleteIfExists(target);
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.FILE_STORAGE_ERROR);
        }
    }

    /** basename 확장자만 추출 → 소문자 → 화이트리스트(^[a-z0-9]{1,10}$) 통과 시 ".ext", 아니면 "". */
    private String extensionSuffix(String originalFilename) {
        String ext = StringUtils.getFilenameExtension(originalFilename);
        if (ext == null) {
            return "";
        }
        ext = ext.toLowerCase(Locale.ROOT);
        return SAFE_EXT.matcher(ext).matches() ? "." + ext : "";
    }

    /** storedPath를 루트 기준으로 정규화. 빈 값이거나 루트를 벗어나면 null. */
    private Path resolveWithinRoot(String storedPath) {
        if (storedPath == null || storedPath.isBlank()) {
            return null;
        }
        Path target = root.resolve(storedPath).normalize();
        return target.startsWith(root) ? target : null;
    }

    private void deleteQuietly(Path target) {
        try {
            Files.deleteIfExists(target);
        } catch (IOException ignored) {
            // best-effort
        }
    }
}
