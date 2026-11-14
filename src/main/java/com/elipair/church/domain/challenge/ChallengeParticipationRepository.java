package com.elipair.church.domain.challenge;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChallengeParticipationRepository extends JpaRepository<ChallengeParticipation, Long> {

    Optional<ChallengeParticipation> findByChallengeIdAndMemberIdAndDeletedAtIsNull(Long challengeId, Long memberId);

    boolean existsByChallengeIdAndMemberIdAndDeletedAtIsNull(Long challengeId, Long memberId);

    /** 관리자 구간·기간 수정 가드(설계 §3): 참여자가 하나라도 있으면 구조 필드 수정 거부. */
    boolean existsByChallengeIdAndDeletedAtIsNull(Long challengeId);

    Page<ChallengeParticipation> findByMemberIdAndDeletedAtIsNull(Long memberId, Pageable pageable);

    /**
     * 참여자 진도 명단(#68). 정렬 키는 누적 총 장 수(rounds × 구간 장 수 + 포인터) — chapters_read만으로 정렬하면
     * 1회독을 마쳐 포인터가 0으로 리셋된 참여자가 꼴찌가 된다. 동률은 참여 순(id)으로 안정 정렬.
     * 탈퇴 회원은 목록·총 개수 양쪽에서 제외한다(members join + deleted_at is null).
     */
    @Query(value = """
                    select p.member_id as memberId, m.name as name,
                           p.chapters_read as chaptersRead, p.rounds_completed as roundsCompleted
                    from challenge_participations p
                    join members m on m.id = p.member_id
                    where p.church_id = :churchId and p.challenge_id = :challengeId
                      and p.deleted_at is null and m.deleted_at is null
                    order by p.rounds_completed * :totalChapters + p.chapters_read desc, p.id
                    """, countQuery = """
                    select count(*) from challenge_participations p
                    join members m on m.id = p.member_id
                    where p.church_id = :churchId and p.challenge_id = :challengeId
                      and p.deleted_at is null and m.deleted_at is null
                    """, nativeQuery = true)
    Page<ChallengeParticipantRow> findParticipants(
            @Param("challengeId") Long challengeId,
            @Param("totalChapters") int totalChapters,
            Pageable pageable,
            @Param("churchId") long churchId);
}
