package com.elipair.church.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.elipair.church.domain.auth.dto.LoginRequest;
import com.elipair.church.domain.auth.dto.RefreshRequest;
import com.elipair.church.domain.auth.dto.SignupRequest;
import com.elipair.church.domain.auth.dto.SignupResponse;
import com.elipair.church.domain.member.Member;
import com.elipair.church.domain.member.MemberRepository;
import com.elipair.church.domain.role.Role;
import com.elipair.church.domain.role.RoleRepository;
import com.elipair.church.global.exception.BusinessException;
import com.elipair.church.global.exception.ErrorCode;
import com.elipair.church.global.security.AccessTokenBlacklister;
import com.elipair.church.global.security.JwtTokenProvider;
import com.elipair.church.global.security.MemberPrincipal;
import com.elipair.church.global.security.redis.RefreshTokenStore;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.MalformedJwtException;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private MemberRepository memberRepository;

    @Mock
    private RoleRepository roleRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtTokenProvider tokenProvider;

    @Mock
    private RefreshTokenStore refreshTokenStore;

    @Mock
    private AccessTokenBlacklister accessTokenBlacklister;

    @Mock
    private LoginRateLimiter loginRateLimiter;

    @InjectMocks
    private AuthService authService;

    @Test
    void signup_normalizes_phone_grants_user_and_returns_summary() {
        when(memberRepository.existsByPhoneAndDeletedAtIsNull("01012345678")).thenReturn(false);
        when(passwordEncoder.encode("password123")).thenReturn("{bcrypt}");
        Role userRole = mock(Role.class);
        when(userRole.getName()).thenReturn("USER");
        when(roleRepository.findByName("USER")).thenReturn(Optional.of(userRole));
        when(memberRepository.saveAndFlush(any(Member.class))).thenAnswer(inv -> inv.getArgument(0));

        SignupResponse res =
                authService.signup(new SignupRequest("010-1234-5678", "홍길동", "password123", null, true, true));

        assertThat(res.name()).isEqualTo("홍길동");
        assertThat(res.phone()).isEqualTo("01012345678");
        assertThat(res.roles()).containsExactly("USER");
    }

    @Test
    void signup_duplicate_phone_is_duplicate_resource() {
        when(memberRepository.existsByPhoneAndDeletedAtIsNull("01012345678")).thenReturn(true);

        assertThatThrownBy(() ->
                        authService.signup(new SignupRequest("010-1234-5678", "홍길동", "password123", null, true, true)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.DUPLICATE_RESOURCE);
    }

    @Test
    void signup_rejects_when_consent_not_given() {
        assertThatThrownBy(() ->
                        authService.signup(new SignupRequest("010-1234-5678", "홍길동", "password123", null, false, true)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_INPUT_VALUE);
    }

    @Test
    void login_unknown_phone_is_authentication_failed() {
        when(memberRepository.findByPhoneAndDeletedAtIsNull("01012345678")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(new LoginRequest("010-1234-5678", "pw"), "1.1.1.1"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.AUTHENTICATION_FAILED);
    }

    @Test
    void login_wrong_password_is_authentication_failed() {
        Member m = Member.create("01012345678", "홍길동", "{enc}", null, null, true, true);
        when(memberRepository.findByPhoneAndDeletedAtIsNull("01012345678")).thenReturn(Optional.of(m));
        when(passwordEncoder.matches(eq("pw"), anyString())).thenReturn(false);

        assertThatThrownBy(() -> authService.login(new LoginRequest("010-1234-5678", "pw"), "1.1.1.1"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.AUTHENTICATION_FAILED); // 미존재와 동일 코드 — 열거 방지
    }

    @Test
    void refresh_parse_failure_is_invalid_token() {
        when(tokenProvider.parse("bad")).thenThrow(new MalformedJwtException("bad"));

        assertThatThrownBy(() -> authService.refresh(new RefreshRequest("bad")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_TOKEN); // 500이 아니라 401
    }

    @Test
    void refresh_access_type_token_is_invalid_token() {
        Claims claims = mock(Claims.class);
        when(tokenProvider.parse("tok")).thenReturn(claims);
        when(claims.get(JwtTokenProvider.CLAIM_TYPE, String.class)).thenReturn("access");

        assertThatThrownBy(() -> authService.refresh(new RefreshRequest("tok")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_TOKEN);
    }

    @Test
    void refresh_revoked_token_is_invalid_token() {
        Claims claims = mock(Claims.class);
        when(tokenProvider.parse("tok")).thenReturn(claims);
        when(claims.get(JwtTokenProvider.CLAIM_TYPE, String.class)).thenReturn("refresh");
        when(claims.getSubject()).thenReturn("uuid-1");
        when(claims.getId()).thenReturn("jti-1");
        when(refreshTokenStore.isValid("uuid-1", "jti-1")).thenReturn(false);

        assertThatThrownBy(() -> authService.refresh(new RefreshRequest("tok")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_TOKEN);
    }

    @Test
    void refresh_non_uuid_subject_is_invalid_token() {
        Claims claims = mock(Claims.class);
        when(tokenProvider.parse("tok")).thenReturn(claims);
        when(claims.get(JwtTokenProvider.CLAIM_TYPE, String.class)).thenReturn("refresh");
        when(claims.getSubject()).thenReturn("not-a-uuid");
        when(claims.getId()).thenReturn("jti-1");
        when(refreshTokenStore.isValid("not-a-uuid", "jti-1")).thenReturn(true);

        assertThatThrownBy(() -> authService.refresh(new RefreshRequest("tok")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_TOKEN); // UUID 형식 아님 → 500 아니라 401
    }

    @Test
    void logout_blacklists_access_and_revokes_owned_refresh() {
        Claims refresh = mock(Claims.class);
        when(tokenProvider.parse("refresh-tok")).thenReturn(refresh);
        when(refresh.get(JwtTokenProvider.CLAIM_TYPE, String.class)).thenReturn("refresh");
        when(refresh.getSubject()).thenReturn("uuid-1");
        when(refresh.getId()).thenReturn("rjti");

        authService.logout(new MemberPrincipal(1L, "uuid-1", "n", 100), "access-tok", "refresh-tok");

        verify(accessTokenBlacklister).blacklist("access-tok");
        verify(refreshTokenStore).revoke("uuid-1", "rjti");
    }

    @Test
    void logout_skips_revoke_for_other_users_refresh() {
        Claims refresh = mock(Claims.class);
        when(tokenProvider.parse("other-tok")).thenReturn(refresh);
        when(refresh.get(JwtTokenProvider.CLAIM_TYPE, String.class)).thenReturn("refresh");
        when(refresh.getSubject()).thenReturn("uuid-OTHER");
        lenient().when(refresh.getId()).thenReturn("rjti"); // 소유자 불일치가 유일한 차단 사유임을 격리(조건 재배열에도 견고)

        authService.logout(new MemberPrincipal(1L, "uuid-1", "n", 100), "access-tok", "other-tok");

        verify(accessTokenBlacklister).blacklist("access-tok");
        verify(refreshTokenStore, never()).revoke(any(), any());
    }
}
