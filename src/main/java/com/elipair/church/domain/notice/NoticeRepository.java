package com.elipair.church.domain.notice;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NoticeRepository extends JpaRepository<Notice, Long>, JpaSpecificationExecutor<Notice> {

    Optional<Notice> findByIdAndDeletedAtIsNull(Long id);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Notice n set n.viewCount = n.viewCount + :delta where n.id = :id and n.deletedAt is null")
    int incrementViewCountBy(@Param("id") Long id, @Param("delta") long delta);

    /**
     * 본문이 media:{id}를 참조하는 미삭제 공지(id·title). PG 정규식 ~ 로 경계 안전 매칭.
     * pattern 예: "media:42($|[^0-9])" — 42가 media:420/421에 매칭되지 않는다.
     */
    @Query(
            value = "select id as id, title as title from notices "
                    + "where church_id = :churchId and deleted_at is null and content ~ :pattern",
            nativeQuery = true)
    List<NoticeRefRow> findReferencesByMedia(@Param("pattern") String pattern, @Param("churchId") long churchId);
}
