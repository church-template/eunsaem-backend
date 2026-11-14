package com.elipair.church.domain.sermon;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SermonRepository extends JpaRepository<Sermon, Long>, JpaSpecificationExecutor<Sermon> {

    Optional<Sermon> findByIdAndDeletedAtIsNull(Long id);

    /** 플러시 잡이 누적 조회수를 +delta 반영. 벌크 UPDATE라 @Version·감사필드 미변경. clear로 L1 stale 방지. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Sermon s set s.viewCount = s.viewCount + :delta where s.id = :id and s.deletedAt is null")
    int incrementViewCountBy(@Param("id") Long id, @Param("delta") long delta);

    /**
     * 본문이 media:{id}를 참조하는 미삭제 설교(id·title). PG 정규식 ~ 로 경계 안전 매칭.
     * pattern 예: "media:42($|[^0-9])" — 42가 media:420/421에 매칭되지 않는다.
     */
    @Query(
            value = "select id as id, title as title from sermons "
                    + "where church_id = :churchId and deleted_at is null and content ~ :pattern",
            nativeQuery = true)
    List<SermonRefRow> findReferencesByMedia(@Param("pattern") String pattern, @Param("churchId") long churchId);
}
