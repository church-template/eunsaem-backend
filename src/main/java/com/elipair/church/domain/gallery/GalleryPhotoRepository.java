package com.elipair.church.domain.gallery;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GalleryPhotoRepository extends JpaRepository<GalleryPhoto, Long> {

    /** 앨범 상세용 — 앨범 내 사진 정렬(첫 사진=대표). 동률 시 id ASC(결정적). */
    List<GalleryPhoto> findByAlbumIdOrderBySortOrderAscIdAsc(Long albumId);

    /** 앨범 삭제 시 연결행 정리(설계 §6, FK 댕글링 차단). */
    void deleteByAlbumId(Long albumId);

    /** append 기준값 — 미존재 시 -1(→ 첫 사진 sort_order 0). */
    @Query("select coalesce(max(p.sortOrder), -1) from GalleryPhoto p where p.albumId = :albumId")
    int findMaxSortOrder(@Param("albumId") Long albumId);

    /** 목록 썸네일 배치 — 앨범당 첫 사진(min sort_order, 동률 id). PG DISTINCT ON. */
    @Query(
            value =
                    "select distinct on (album_id) album_id as albumId, media_id as mediaId "
                            + "from gallery_photos where church_id = :churchId and album_id in (:albumIds) order by album_id, sort_order, id",
            nativeQuery = true)
    List<AlbumThumbnailRow> findThumbnails(
            @Param("albumIds") Collection<Long> albumIds, @Param("churchId") long churchId);

    /** 목록 사진수 배치. */
    @Query("select p.albumId as albumId, count(p) as count from GalleryPhoto p "
            + "where p.albumId in :albumIds group by p.albumId")
    List<AlbumPhotoCountRow> countByAlbumIds(@Param("albumIds") Collection<Long> albumIds);

    /**
     * 사진 FK 미디어 참조(스펙 §5.10 SQL) — 소속 앨범(id·title)으로 표면화. 미삭제 앨범만(a.deleted_at IS NULL).
     * 같은 앨범 내 중복 media는 DISTINCT로 1건.
     */
    @Query(
            value = "select distinct a.id as id, a.title as title from gallery_photos p "
                    + "join gallery_albums a on a.id = p.album_id "
                    + "where p.church_id = :churchId and p.media_id = :mediaId and a.deleted_at is null",
            nativeQuery = true)
    List<GalleryPhotoRefRow> findReferencesByMediaId(@Param("mediaId") long mediaId, @Param("churchId") long churchId);
}
