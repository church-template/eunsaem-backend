package com.elipair.church.domain.event;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EventRepository extends JpaRepository<Event, Long>, JpaSpecificationExecutor<Event> {

    Optional<Event> findByIdAndDeletedAtIsNull(Long id);

    /** 다가오는 일정(start_at >= now, 미삭제, start_at ASC). 동일 start_at 시 id ASC로 결정적 정렬. idx_events_start_at 활용. */
    @Query("select e from Event e where e.startAt >= :now and e.deletedAt is null order by e.startAt asc, e.id asc")
    List<Event> findUpcoming(@Param("now") LocalDateTime now, Pageable pageable);

    /**
     * 본문(description)이 media:{id}를 참조하는 미삭제 일정(id·title). PG 정규식 ~ 로 경계 안전 매칭.
     * pattern 예: "media:42($|[^0-9])" — 42가 media:420/421에 매칭되지 않는다.
     */
    @Query(
            value =
                    "select id as id, title as title from events where church_id = :churchId and deleted_at is null and description ~ :pattern",
            nativeQuery = true)
    List<EventRefRow> findReferencesByMedia(@Param("pattern") String pattern, @Param("churchId") long churchId);
}
