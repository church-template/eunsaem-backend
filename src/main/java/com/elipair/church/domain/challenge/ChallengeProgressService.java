package com.elipair.church.domain.challenge;

import com.elipair.church.domain.challenge.dto.BiblePositionResponse;
import com.elipair.church.domain.challenge.dto.ChallengeParticipantResponse;
import com.elipair.church.domain.challenge.dto.ChallengeReadRequest;
import com.elipair.church.domain.challenge.dto.ChallengeSummaryResponse;
import com.elipair.church.domain.challenge.dto.MyParticipationResponse;
import com.elipair.church.domain.challenge.dto.MyProgressResponse;
import com.elipair.church.domain.challenge.dto.ReadingLogResponse;
import com.elipair.church.global.exception.BusinessException;
import com.elipair.church.global.exception.ErrorCode;
import com.elipair.church.global.tenant.TenantContext;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 통독 진행 기록(설계 §3): 참여·읽음(소급 포함)·취소·대시보드·히트맵 로그·마이페이지 이력.
 * "오늘" = Clock(APP_TIMEZONE) 기준. 동시 클릭은 participation @Version → 409(전역 핸들러).
 * ENDED 챌린지도 기록 허용(늦은 완주 응원) — 단 소프트삭제된 챌린지는 404.
 */
@Service
@Transactional(readOnly = true)
public class ChallengeProgressService {

    private final BibleChallengeRepository challengeRepository;
    private final ChallengeParticipationRepository participationRepository;
    private final ChallengeReadingLogRepository logRepository;
    private final Clock clock;

    public ChallengeProgressService(
            BibleChallengeRepository challengeRepository,
            ChallengeParticipationRepository participationRepository,
            ChallengeReadingLogRepository logRepository,
            Clock clock) {
        this.challengeRepository = challengeRepository;
        this.participationRepository = participationRepository;
        this.logRepository = logRepository;
        this.clock = clock;
    }

    @Transactional
    public MyProgressResponse join(Long challengeId, Long memberId) {
        BibleChallenge challenge = loadChallenge(challengeId);
        if (participationRepository.existsByChallengeIdAndMemberIdAndDeletedAtIsNull(challengeId, memberId)) {
            throw new BusinessException(ErrorCode.DUPLICATE_RESOURCE, "이미 참여 중인 챌린지입니다");
        }
        ChallengeParticipation participation =
                participationRepository.save(ChallengeParticipation.create(challengeId, memberId));
        return progressOf(challenge, participation);
    }

    @Transactional
    public MyProgressResponse read(Long challengeId, Long memberId, ChallengeReadRequest req) {
        BibleChallenge challenge = loadChallenge(challengeId);
        ChallengeParticipation participation = loadParticipation(challengeId, memberId);
        LocalDate today = LocalDate.now(clock);
        LocalDate date = req.date() != null ? req.date() : today;
        if (date.isBefore(challenge.getStartDate()) || date.isAfter(today)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE, "기록 날짜는 챌린지 시작일부터 오늘까지만 가능합니다");
        }
        ChallengeReadingLog existing = logRepository
                .findByParticipationIdAndReadDate(participation.getId(), date)
                .orElse(null);
        int logged = existing != null ? existing.getChapters() : 0;
        int chapters = req.chapters() != null ? req.chapters() : Math.max(challenge.dailyGoal() - logged, 0);
        if (chapters <= 0) {
            throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE, "해당 날짜의 목표를 이미 달성했습니다. 장 수를 직접 지정하세요");
        }
        if (existing != null) {
            existing.addChapters(chapters);
        } else {
            logRepository.save(ChallengeReadingLog.create(participation.getId(), date, chapters));
        }
        participation.advance(chapters, challenge.totalChapters());
        // 오늘 기록이면 방금 계산한 값을 그대로 전달 — 재조회는 미플러시 상태에 의존하고 단위 테스트도 어렵다.
        Integer todayOverride = date.equals(today) ? logged + chapters : null;
        return progressOf(challenge, participation, todayOverride);
    }

    @Transactional
    public MyProgressResponse cancelRead(Long challengeId, Long memberId, LocalDate date) {
        BibleChallenge challenge = loadChallenge(challengeId);
        ChallengeParticipation participation = loadParticipation(challengeId, memberId);
        LocalDate target = date != null ? date : LocalDate.now(clock);
        ChallengeReadingLog log = logRepository
                .findByParticipationIdAndReadDate(participation.getId(), target)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        participation.rollback(log.getChapters(), challenge.totalChapters());
        logRepository.delete(log);
        // 오늘 기록을 지웠으면 todayChapters=0 확정 — 삭제 플러시 전 재조회에 의존하지 않는다.
        Integer todayOverride = target.equals(LocalDate.now(clock)) ? 0 : null;
        return progressOf(challenge, participation, todayOverride);
    }

    public MyProgressResponse myProgress(Long challengeId, Long memberId) {
        BibleChallenge challenge = loadChallenge(challengeId);
        return progressOf(challenge, loadParticipation(challengeId, memberId));
    }

    public List<ReadingLogResponse> myLogs(Long challengeId, Long memberId, LocalDate from, LocalDate to) {
        BibleChallenge challenge = loadChallenge(challengeId);
        ChallengeParticipation participation = loadParticipation(challengeId, memberId);
        LocalDate start = from != null ? from : challenge.getStartDate();
        LocalDate end = to != null ? to : challenge.endDate();
        return logRepository
                .findByParticipationIdAndReadDateBetweenOrderByReadDateAsc(participation.getId(), start, end)
                .stream()
                .map(l -> new ReadingLogResponse(l.getReadDate(), l.getChapters()))
                .toList();
    }

    /**
     * 참여자 진도 명단(#68). 열람 자격 = 그 챌린지 참여자(내 진도를 공개하는 대가로 남의 진도를 본다) — 아니면 403.
     * 정렬은 쿼리가 누적 장 수로 고정한다: 클라이언트 sort 파라미터를 그대로 넘기면 native order by 뒤에 덧붙어 순서가 깨진다.
     */
    public Page<ChallengeParticipantResponse> participants(Long challengeId, Long memberId, Pageable pageable) {
        BibleChallenge challenge = loadChallenge(challengeId);
        if (!participationRepository.existsByChallengeIdAndMemberIdAndDeletedAtIsNull(challengeId, memberId)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED, "챌린지에 참여해야 참여자 명단을 볼 수 있습니다");
        }
        int totalChapters = challenge.totalChapters();
        Pageable fixedOrder = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());
        return participationRepository
                .findParticipants(challengeId, totalChapters, fixedOrder, TenantContext.require())
                .map(row -> new ChallengeParticipantResponse(
                        row.getName(),
                        row.getChaptersRead(),
                        progressRate(row.getChaptersRead(), totalChapters),
                        row.getRoundsCompleted(),
                        locate(challenge, row.getChaptersRead()),
                        row.getMemberId().equals(memberId)));
    }

    public Page<MyParticipationResponse> myParticipations(Long memberId, Pageable pageable) {
        LocalDate today = LocalDate.now(clock);
        Page<ChallengeParticipation> page =
                participationRepository.findByMemberIdAndDeletedAtIsNull(memberId, pageable);
        // 소프트삭제된 챌린지도 이력에 표시(참여 기록 보존) — findAllById는 deleted_at을 거르지 않는다.
        Map<Long, BibleChallenge> challenges =
                challengeRepository
                        .findAllById(
                                page.map(ChallengeParticipation::getChallengeId).getContent())
                        .stream()
                        .collect(Collectors.toMap(BibleChallenge::getId, Function.identity()));
        // ponytail: 페이지당 참여별 스트릭 조회 N회(기본 10) — 인덱스 조회라 충분, 병목 시 일괄 조회로 교체.
        return page.map(p -> {
            BibleChallenge c = challenges.get(p.getChallengeId());
            return new MyParticipationResponse(
                    ChallengeSummaryResponse.from(c, today),
                    p.getCreatedAt().toLocalDate(),
                    progressRate(p, c),
                    p.getChaptersRead(),
                    p.getRoundsCompleted(),
                    p.getRoundsCompleted() >= 1,
                    streak(p.getId(), today));
        });
    }

    // ---- 조립 ----

    private MyProgressResponse progressOf(BibleChallenge challenge, ChallengeParticipation p) {
        return progressOf(challenge, p, null);
    }

    /** todayChaptersOverride: read/cancel이 방금 계산한 오늘 장 수 — null이면 로그에서 조회(join/myProgress 경로). */
    private MyProgressResponse progressOf(
            BibleChallenge challenge, ChallengeParticipation p, Integer todayChaptersOverride) {
        LocalDate today = LocalDate.now(clock);
        int dailyGoal = challenge.dailyGoal();
        int todayChapters = todayChaptersOverride != null
                ? todayChaptersOverride
                : logRepository
                        .findByParticipationIdAndReadDate(p.getId(), today)
                        .map(ChallengeReadingLog::getChapters)
                        .orElse(0);
        return new MyProgressResponse(
                progressRate(p, challenge),
                locate(challenge, p.getChaptersRead()),
                p.getChaptersRead(),
                challenge.totalChapters(),
                todayChapters,
                dailyGoal,
                todayChapters >= dailyGoal,
                streak(p.getId(), today),
                p.getRoundsCompleted(),
                paceDays(challenge, p, today),
                ChallengeSummaryResponse.from(challenge, today));
    }

    /** 현재 회독 기준 %, 소수 1자리 반올림(설계 §3). */
    private double progressRate(ChallengeParticipation p, BibleChallenge challenge) {
        return progressRate(p.getChaptersRead(), challenge.totalChapters());
    }

    private double progressRate(int chaptersRead, int totalChapters) {
        return Math.round(chaptersRead * 1000.0 / totalChapters) / 10.0;
    }

    /** 포인터 0(현재 회독 시작 전)이면 null — locate는 1-based ordinal만 받는다. */
    private BiblePositionResponse locate(BibleChallenge challenge, int chaptersRead) {
        if (chaptersRead <= 0) {
            return null;
        }
        BibleStructure.BiblePosition located = BibleStructure.locate(challenge.getStartBook(), chaptersRead);
        return new BiblePositionResponse(located.book(), located.chapter());
    }

    /** 연속 기록 일수 — 오늘 기록이 없으면 어제부터 역산(오늘은 아직 기회가 있다). 소급 백필로 치유된다(설계 §3). */
    private int streak(Long participationId, LocalDate today) {
        Set<LocalDate> dates = Set.copyOf(logRepository.findReadDatesDesc(participationId));
        LocalDate cursor = dates.contains(today) ? today : today.minusDays(1);
        int streak = 0;
        while (dates.contains(cursor)) {
            streak++;
            cursor = cursor.minusDays(1);
        }
        return streak;
    }

    /** 예정 대비 앞섬(+)/뒤처짐(-) 일수. ENDED면 null. 늦은 참여자도 챌린지 시작일 기준(공동 챌린지, 설계 §3). */
    private Integer paceDays(BibleChallenge challenge, ChallengeParticipation p, LocalDate today) {
        if (challenge.status(today) == ChallengeStatus.ENDED) {
            return null;
        }
        long elapsedDays = Math.min(
                Math.max(ChronoUnit.DAYS.between(challenge.getStartDate(), today) + 1, 0), challenge.getTargetDays());
        long expected = Math.min(elapsedDays * challenge.dailyGoal(), challenge.totalChapters());
        long actual = p.totalChaptersRead(challenge.totalChapters());
        return Math.toIntExact(Math.round((actual - expected) / (double) challenge.dailyGoal()));
    }

    private BibleChallenge loadChallenge(Long id) {
        return challengeRepository
                .findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    private ChallengeParticipation loadParticipation(Long challengeId, Long memberId) {
        return participationRepository
                .findByChallengeIdAndMemberIdAndDeletedAtIsNull(challengeId, memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    }
}
