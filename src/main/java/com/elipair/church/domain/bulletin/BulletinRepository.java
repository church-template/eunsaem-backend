package com.elipair.church.domain.bulletin;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BulletinRepository extends JpaRepository<Bulletin, Long> {

    /** 공개 목록 — 미삭제만. 정렬은 Pageable(service_date DESC 기본). */
    Page<Bulletin> findByDeletedAtIsNull(Pageable pageable);

    Optional<Bulletin> findByIdAndDeletedAtIsNull(Long id);

    /** media_id FK 참조(스펙 §5.10) — 활성 주보만(deleted_at IS NULL). */
    @Query(
            value =
                    "select id as id, title as title from bulletins where church_id = :churchId and media_id = :mediaId and deleted_at is null",
            nativeQuery = true)
    List<BulletinRefRow> findReferencesByMediaId(@Param("mediaId") long mediaId, @Param("churchId") long churchId);
}
