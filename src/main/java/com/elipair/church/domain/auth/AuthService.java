package com.elipair.church.domain.auth;

import com.elipair.church.domain.auth.dto.LoginRequest;
import com.elipair.church.domain.auth.dto.LoginResponse;
import com.elipair.church.domain.auth.dto.MemberSummary;
import com.elipair.church.domain.auth.dto.RefreshRequest;
import com.elipair.church.domain.auth.dto.RefreshResponse;
import com.elipair.church.domain.auth.dto.SignupRequest;
import com.elipair.church.domain.auth.dto.SignupResponse;
import com.elipair.church.domain.auth.dto.TokenPair;
import com.elipair.church.domain.member.Member;
import com.elipair.church.domain.member.MemberAuthorities;
import com.elipair.church.domain.member.MemberRepository;
import com.elipair.church.domain.member.PhoneNumbers;
import com.elipair.church.domain.role.Role;
import com.elipair.church.domain.role.RoleRepository;
import com.elipair.church.global.exception.BusinessException;
import com.elipair.church.global.exception.ErrorCode;
import com.elipair.church.global.security.AccessTokenBlacklister;
import com.elipair.church.global.security.JwtTokenProvider;
import com.elipair.church.global.security.MemberPrincipal;
import com.elipair.church.global.security.redis.RefreshTokenStore;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class AuthService {

    private static final String DEFAULT_ROLE = "USER";

    private final MemberRepository memberRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider tokenProvider;
    private final RefreshTokenStore refreshTokenStore;
    private final AccessTokenBlacklister accessTokenBlacklister;
    private final LoginRateLimiter loginRateLimiter;

    public AuthService(
            MemberRepository memberRepository,
            RoleRepository roleRepository,
            PasswordEncoder passwordEncoder,
            JwtTokenProvider tokenProvider,
            RefreshTokenStore refreshTokenStore,
            AccessTokenBlacklister accessTokenBlacklister,
            LoginRateLimiter loginRateLimiter) {
        this.memberRepository = memberRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenProvider = tokenProvider;
        this.refreshTokenStore = refreshTokenStore;
        this.accessTokenBlacklister = accessTokenBlacklister;
        this.loginRateLimiter = loginRateLimiter;
    }

    @Transactional
    public SignupResponse signup(SignupRequest request) {
        String phone = PhoneNumbers.normalize(request.phone());
        if (memberRepository.existsByPhoneAndDeletedAtIsNull(phone)) {
            throw new BusinessException(ErrorCode.DUPLICATE_RESOURCE);
        }
        Member member = Member.create(
                phone,
                request.name(),
                passwordEncoder.encode(request.password()),
                request.email(),
                null,
                request.termsAgreed(),
                request.privacyAgreed());
        Role userRole = roleRepository
                .findByName(DEFAULT_ROLE)
                .orElseThrow(() -> new IllegalStateException("USER 역할 시드(V2)가 없습니다"));
        member.grantRole(userRole);
        try {
            memberRepository.saveAndFlush(member);
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.DUPLICATE_RESOURCE); // 동시 가입 경합 백스톱(partial unique)
        }
        return SignupResponse.from(member);
    }

    /** clientIp는 시도 제한의 잠금 단위((교회, 전화번호, IP), #86)에 쓴다. */
    public LoginResponse login(LoginRequest request, String clientIp) {
        String phone = PhoneNumbers.normalize(request.phone());
        loginRateLimiter.checkAllowed(phone, clientIp); // 한도 초과 → 429 (맞는 비밀번호도 막는다)
        Member member = memberRepository.findByPhoneAndDeletedAtIsNull(phone).orElse(null);
        if (member == null || !passwordEncoder.matches(request.password(), member.getPassword())) {
            loginRateLimiter.recordFailure(phone, clientIp); // 미존재 번호도 똑같이 센다
            throw new BusinessException(ErrorCode.AUTHENTICATION_FAILED); // 미존재와 구분 없는 동일 응답
        }
        loginRateLimiter.reset(phone, clientIp);
        TokenPair tokens = issueTokens(member);
        boolean requiresAgreement = !(member.isTermsAgreed() && member.isPrivacyAgreed());
        return new LoginResponse(tokens, MemberSummary.from(member), requiresAgreement);
    }

    /** access + refresh 발급, 발급한 refresh를 재파싱해 jti·exp로 다중세션 등록. */
    private TokenPair issueTokens(Member member) {
        String access = tokenProvider.issueAccess(
                principalOf(member), positionOf(member), MemberAuthorities.permissions(member));
        String refresh = tokenProvider.issueRefresh(member.getUuid().toString());
        Claims claims = tokenProvider.parse(refresh); // 자기 서명 토큰 — 항상 성공
        refreshTokenStore.save(
                member.getUuid().toString(),
                claims.getId(),
                claims.getExpiration().toInstant());
        return new TokenPair(access, refresh);
    }

    private MemberPrincipal principalOf(Member m) {
        return new MemberPrincipal(m.getId(), m.getUuid().toString(), m.getName(), MemberAuthorities.maxPriority(m));
    }

    private String positionOf(Member m) {
        return m.getPosition() == null ? null : m.getPosition().getName();
    }

    public RefreshResponse refresh(RefreshRequest request) {
        Claims claims = parseToken(request.refreshToken());
        if (!JwtTokenProvider.TYPE_REFRESH.equals(claims.get(JwtTokenProvider.CLAIM_TYPE, String.class))) {
            throw new BusinessException(ErrorCode.INVALID_TOKEN);
        }
        String uuid = claims.getSubject();
        String jti = claims.getId();
        if (uuid == null || jti == null || !refreshTokenStore.isValid(uuid, jti)) {
            throw new BusinessException(ErrorCode.INVALID_TOKEN); // revoke·만료·미등록
        }
        UUID memberUuid;
        try {
            memberUuid = UUID.fromString(uuid);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INVALID_TOKEN); // sub가 UUID 형식이 아님 → 500 누수 방지
        }
        Member member = memberRepository
                .findByUuidAndDeletedAtIsNull(memberUuid)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_TOKEN)); // 탈퇴 회원
        // Access만 재발급 — DB에서 권한 재조회(스펙 §4.1), refresh는 그대로 echo
        String access = tokenProvider.issueAccess(
                principalOf(member), positionOf(member), MemberAuthorities.permissions(member));
        return new RefreshResponse(new TokenPair(access, request.refreshToken()));
    }

    public void logout(MemberPrincipal principal, String accessToken, String refreshToken) {
        accessTokenBlacklister.blacklist(accessToken);
        revokeRefreshIfOwned(principal.uuid(), refreshToken);
    }

    /** 본인 소유 refresh일 때만 revoke. 무효/타인 토큰은 skip(멱등 로그아웃 — INVALID_TOKEN 던지지 않음). */
    private void revokeRefreshIfOwned(String requesterUuid, String refreshToken) {
        try {
            Claims claims = tokenProvider.parse(refreshToken);
            boolean isRefresh =
                    JwtTokenProvider.TYPE_REFRESH.equals(claims.get(JwtTokenProvider.CLAIM_TYPE, String.class));
            if (isRefresh && requesterUuid.equals(claims.getSubject()) && claims.getId() != null) {
                refreshTokenStore.revoke(requesterUuid, claims.getId());
            }
        } catch (JwtException | IllegalArgumentException ignored) {
            // 무효 refresh — skip
        }
    }

    /** 토큰 파싱 실패(만료·위변조·형식)를 INVALID_TOKEN으로 변환. 핸들러에 JwtException 핸들러가 없어 미변환 시 500. */
    private Claims parseToken(String token) {
        try {
            return tokenProvider.parse(token);
        } catch (JwtException | IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INVALID_TOKEN);
        }
    }
}
