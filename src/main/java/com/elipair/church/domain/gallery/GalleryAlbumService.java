package com.elipair.church.domain.gallery;

import com.elipair.church.domain.gallery.dto.GalleryAlbumCardResponse;
import com.elipair.church.domain.gallery.dto.GalleryAlbumCreateRequest;
import com.elipair.church.domain.gallery.dto.GalleryAlbumDetailResponse;
import com.elipair.church.domain.gallery.dto.GalleryAlbumPatchRequest;
import com.elipair.church.domain.gallery.dto.GalleryPhotoResponse;
import com.elipair.church.domain.member.AuthorDisplayService;
import com.elipair.church.domain.tag.ContentResourceType;
import com.elipair.church.domain.tag.ContentTagService;
import com.elipair.church.global.exception.BusinessException;
import com.elipair.church.global.exception.ErrorCode;
import com.elipair.church.global.tenant.TenantContext;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 갤러리 앨범 서비스(스펙 §5.12). 태그(ContentTagService)·작성자(AuthorDisplayService)와 조립.
 * 목록은 썸네일·사진수·태그·작성자를 배치 조회해 N+1을 피한다. 낙관락은 명시적 version 비교 + flush로 응답 정합.
 * 삭제는 앨범 soft delete + 태그 정리 + 사진 행 hard delete(media_id FK 댕글링 차단 — 설계 Critical).
 */
@Service
@Transactional(readOnly = true)
public class GalleryAlbumService {

    private static final ContentResourceType TYPE = ContentResourceType.GALLERY_ALBUM;

    private final GalleryAlbumRepository repository;
    private final GalleryPhotoRepository photoRepository;
    private final ContentTagService contentTagService;
    private final AuthorDisplayService authorDisplayService;

    public GalleryAlbumService(
            GalleryAlbumRepository repository,
            GalleryPhotoRepository photoRepository,
            ContentTagService contentTagService,
            AuthorDisplayService authorDisplayService) {
        this.repository = repository;
        this.photoRepository = photoRepository;
        this.contentTagService = contentTagService;
        this.authorDisplayService = authorDisplayService;
    }

    public Page<GalleryAlbumCardResponse> list(Long tagId, Pageable pageable) {
        List<Long> taggedIds = tagId == null ? null : contentTagService.resourceIdsWithTag(TYPE, tagId);
        Page<GalleryAlbum> page = repository.findAll(GalleryAlbumSpecifications.filter(taggedIds), pageable);

        List<Long> ids = page.map(GalleryAlbum::getId).getContent();
        Map<Long, List<com.elipair.church.domain.tag.dto.TagResponse>> tagsMap =
                contentTagService.getTagsByResources(TYPE, ids);
        Map<Long, String> authorMap = authorDisplayService.displayNames(
                page.map(GalleryAlbum::getUpdatedBy).getContent());
        Map<Long, Long> thumbMap = ids.isEmpty()
                ? Map.of()
                : photoRepository.findThumbnails(ids, TenantContext.require()).stream()
                        .collect(Collectors.toMap(AlbumThumbnailRow::getAlbumId, AlbumThumbnailRow::getMediaId));
        Map<Long, Long> countMap = ids.isEmpty()
                ? Map.of()
                : photoRepository.countByAlbumIds(ids).stream()
                        .collect(Collectors.toMap(AlbumPhotoCountRow::getAlbumId, AlbumPhotoCountRow::getCount));

        return page.map(a -> new GalleryAlbumCardResponse(
                a.getId(),
                a.getTitle(),
                thumbMap.get(a.getId()),
                countMap.getOrDefault(a.getId(), 0L),
                a.getCreatedAt(),
                tagsMap.getOrDefault(a.getId(), List.of()),
                authorMap.getOrDefault(a.getUpdatedBy(), AuthorDisplayService.UNKNOWN)));
    }

    public GalleryAlbumDetailResponse get(Long id) {
        return detail(load(id));
    }

    @Transactional
    public GalleryAlbumDetailResponse create(GalleryAlbumCreateRequest req) {
        GalleryAlbum album = repository.save(GalleryAlbum.create(req.title(), req.description()));
        contentTagService.replaceLinks(TYPE, album.getId(), req.tagIds());
        return detail(album);
    }

    @Transactional
    public GalleryAlbumDetailResponse patch(Long id, GalleryAlbumPatchRequest req) {
        GalleryAlbum album = load(id);
        checkVersion(album, req.version());
        album.applyPatch(req.title(), req.description());
        if (req.tagIds() != null) {
            contentTagService.replaceLinks(TYPE, id, req.tagIds());
        }
        repository.flush(); // 엔티티 필드 변경분의 버전 UPDATE 즉시 반영(tag-only는 행 미변경이라 version 유지)
        return detail(album);
    }

    @Transactional
    public void delete(Long id) {
        GalleryAlbum album = load(id);
        album.softDelete();
        contentTagService.cleanUp(TYPE, id);
        photoRepository.deleteByAlbumId(id); // 연결행 정리 — media 차단삭제가 FK 위반 없이 동작하도록(설계 Critical)
    }

    private GalleryAlbum load(Long id) {
        return repository
                .findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    private void checkVersion(GalleryAlbum album, Long expected) {
        if (!album.getVersion().equals(expected)) {
            throw new BusinessException(ErrorCode.OPTIMISTIC_LOCK_CONFLICT);
        }
    }

    private GalleryAlbumDetailResponse detail(GalleryAlbum a) {
        List<GalleryPhotoResponse> photos = photoRepository.findByAlbumIdOrderBySortOrderAscIdAsc(a.getId()).stream()
                .map(p -> new GalleryPhotoResponse(p.getId(), p.getMediaId(), p.getCaption(), p.getSortOrder()))
                .toList();
        return new GalleryAlbumDetailResponse(
                a.getId(),
                a.getTitle(),
                a.getDescription(),
                contentTagService.getTags(TYPE, a.getId()),
                authorDisplayService.displayName(a.getUpdatedBy()),
                a.getCreatedAt(),
                a.getUpdatedAt(),
                a.getVersion(),
                photos);
    }
}
