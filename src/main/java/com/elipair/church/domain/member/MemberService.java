package com.elipair.church.domain.member;

import com.elipair.church.domain.member.dto.AdminMemberUpdateRequest;
import com.elipair.church.domain.member.dto.AgreementResponse;
import com.elipair.church.domain.member.dto.AgreementUpdateRequest;
import com.elipair.church.domain.member.dto.MeResponse;
import com.elipair.church.domain.member.dto.MeUpdateRequest;
import com.elipair.church.domain.member.dto.MemberCardResponse;
import com.elipair.church.domain.member.dto.MemberDetailResponse;
import com.elipair.church.domain.member.dto.ResetPasswordResponse;
import com.elipair.church.domain.position.Position;
import com.elipair.church.domain.position.PositionRepository;
import com.elipair.church.global.exception.BusinessException;
import com.elipair.church.global.exception.ErrorCode;
import com.elipair.church.global.security.AccessTokenBlacklister;
import com.elipair.church.global.security.RoleHierarchyValidator;
import com.elipair.church.global.security.redis.RefreshTokenStore;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class MemberService {

    private static final String SUPER_ADMIN = "SUPER_ADMIN";

    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenStore refreshTokenStore;
    private final AccessTokenBlacklister accessTokenBlacklister;
    private final RoleHierarchyValidator hierarchyValidator;
    private final PositionRepository positionRepository;

    public MemberService(
            MemberRepository memberRepository,
            PasswordEncoder passwordEncoder,
            RefreshTokenStore refreshTokenStore,
            AccessTokenBlacklister accessTokenBlacklister,
            RoleHierarchyValidator hierarchyValidator,
            PositionRepository positionRepository) {
        this.memberRepository = memberRepository;
        this.passwordEncoder = passwordEncoder;
        this.refreshTokenStore = refreshTokenStore;
        this.accessTokenBlacklister = accessTokenBlacklister;
        this.hierarchyValidator = hierarchyValidator;
        this.positionRepository = positionRepository;
    }

    public MeResponse getMe(Long memberId) {
        return MeResponse.from(findActive(memberId));
    }

    @Transactional
    public MeResponse updateMe(Long memberId, MeUpdateRequest request) {
        Member member = findActive(memberId);
        applyProfile(member, request.name(), request.phone(), request.email(), member.getId());
        if (request.password() != null) {
            member.changePassword(passwordEncoder.encode(request.password()));
        }
        return MeResponse.from(persist(member));
    }

    @Transactional
    public void withdraw(Long memberId, String uuid, String accessToken, String rawPassword) {
        Member member = findActive(memberId);
        if (!passwordEncoder.matches(rawPassword, member.getPassword())) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_FAILED);
        }
        if (member.hasRole(SUPER_ADMIN)) {
            long activeSuperAdmins = memberRepository.countByRoles_NameAndDeletedAtIsNull(SUPER_ADMIN);
            hierarchyValidator.validateNotLastSuperAdmin(true, activeSuperAdmins);
        }
        member.withdraw();
        memberRepository.saveAndFlush(member);
        refreshTokenStore.revokeAll(uuid);
        accessTokenBlacklister.blacklist(accessToken);
    }

    public AgreementResponse getAgreements(Long memberId) {
        return AgreementResponse.from(findActive(memberId));
    }

    @Transactional
    public AgreementResponse submitAgreements(Long memberId, AgreementUpdateRequest request) {
        if (!(request.termsAgreed() && request.privacyAgreed())) {
            throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE, "필수 약관에 모두 동의해야 합니다");
        }
        Member member = findActive(memberId);
        member.agree();
        return AgreementResponse.from(member);
    }

    public Page<MemberCardResponse> list(String q, Pageable pageable) {
        Page<Member> page = (q == null || q.isBlank())
                ? memberRepository.findByDeletedAtIsNull(pageable)
                : memberRepository.findAll(MemberSpecifications.filter(q), pageable);
        return page.map(MemberCardResponse::from);
    }

    public MemberDetailResponse detail(UUID uuid) {
        return MemberDetailResponse.from(findActiveByUuid(uuid));
    }

    @Transactional
    public MemberDetailResponse changePosition(UUID uuid, Long positionId, int requesterMaxPriority) {
        Member member = findActiveByUuid(uuid); // 미존재/탈퇴 → 404
        validateCanManage(requesterMaxPriority, member);
        Position position = positionId == null
                ? null
                : positionRepository
                        .findById(positionId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND)); // 미존재 직분 → 404
        member.changePosition(position); // null이면 직분 해제
        return MemberDetailResponse.from(member);
    }

    @Transactional
    public MemberDetailResponse adminUpdate(UUID uuid, AdminMemberUpdateRequest request, int requesterMaxPriority) {
        Member member = findActiveByUuid(uuid);
        validateCanManage(requesterMaxPriority, member);
        applyProfile(member, request.name(), request.phone(), request.email(), member.getId());
        return MemberDetailResponse.from(persist(member));
    }

    @Transactional
    public void resetAgreements(String target) {
        switch (target) {
            case "terms" -> memberRepository.resetTermsAgreed();
            case "privacy" -> memberRepository.resetPrivacyAgreed();
            default -> throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE, "알 수 없는 약관 항목입니다: " + target);
        }
    }

    @Transactional
    public ResetPasswordResponse resetPassword(UUID uuid, int requesterMaxPriority) {
        Member member = findActiveByUuid(uuid);
        validateCanManage(requesterMaxPriority, member);
        String temporary = TemporaryPasswords.generate();
        member.changePassword(passwordEncoder.encode(temporary));
        return new ResetPasswordResponse(temporary);
    }

    /**
     * 관리자가 <b>다른 회원</b>을 바꾸는 작업(정보 수정·직분 지정·비밀번호 초기화)의 위계 검사(#86).
     * 대상의 최고 priority가 요청자의 maxPriority보다 높으면 403 — MEMBER_MANAGE만 보면 교회 ADMIN이
     * 교회 대표자(SUPER_ADMIN)의 비밀번호를 초기화해 계정을 가져가거나 전화번호를 바꿔 잠글 수 있다.
     * 동급은 허용한다(막으려는 것은 위로의 권한 상승이고, ADMIN끼리 비밀번호 초기화는 일상 운영이다).
     */
    private void validateCanManage(int requesterMaxPriority, Member target) {
        hierarchyValidator.validateAssignable(requesterMaxPriority, MemberAuthorities.maxPriority(target));
    }

    /** 이름·전화번호·이메일 부분 수정 + 전화번호 자기제외 중복 체크. */
    private void applyProfile(Member member, String name, String rawPhone, String email, Long selfId) {
        String normalizedPhone = null;
        if (rawPhone != null) {
            normalizedPhone = PhoneNumbers.normalize(rawPhone);
            if (!normalizedPhone.equals(member.getPhone())
                    && memberRepository.existsByPhoneAndDeletedAtIsNullAndIdNot(normalizedPhone, selfId)) {
                throw new BusinessException(ErrorCode.DUPLICATE_RESOURCE);
            }
        }
        member.updateProfile(name, normalizedPhone, email);
    }

    // partial unique 동시 변경 백스톱(Position·Role 패턴).
    private Member persist(Member member) {
        try {
            return memberRepository.saveAndFlush(member);
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.DUPLICATE_RESOURCE);
        }
    }

    private Member findActive(Long memberId) {
        return memberRepository
                .findByIdAndDeletedAtIsNull(memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    private Member findActiveByUuid(UUID uuid) {
        return memberRepository
                .findByUuidAndDeletedAtIsNull(uuid)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    }
}
