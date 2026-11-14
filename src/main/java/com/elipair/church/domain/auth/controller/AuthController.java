package com.elipair.church.domain.auth.controller;

import com.elipair.church.domain.auth.AuthService;
import com.elipair.church.domain.auth.dto.LoginRequest;
import com.elipair.church.domain.auth.dto.LoginResponse;
import com.elipair.church.domain.auth.dto.LogoutRequest;
import com.elipair.church.domain.auth.dto.RefreshRequest;
import com.elipair.church.domain.auth.dto.RefreshResponse;
import com.elipair.church.domain.auth.dto.SignupRequest;
import com.elipair.church.domain.auth.dto.SignupResponse;
import com.elipair.church.global.security.MemberPrincipal;
import com.elipair.church.global.web.ClientIp;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 인증(스펙 §5.1). signup·login·refresh는 공개, logout만 메서드 보안으로 인증 강제. */
@Tag(name = "인증", description = "회원가입·로그인·토큰 재발급·로그아웃 API(스펙 §5.1)")
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @Operation(summary = "회원가입", description = """
                    신규 회원 가입(201). 가입 시 기본 `USER` 역할만 부여되며, 교인 승인(`MEMBER`)은 관리자가 별도 진행.

                    - 인증(JWT): 불필요
                    - 요청 본문: `SignupRequest` — 전화번호·이름·비밀번호(8자 이상)·이메일(선택)·`termsAgreed`·`privacyAgreed`(둘 다 true 필수)
                    - 반환값: `SignupResponse` — uuid·이름·전화번호·부여된 역할 목록
                    - 부수효과: 전화번호 정규화 후 중복 시 409 DUPLICATE_RESOURCE(deleted 제외 부분 unique)
                    """)
    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public SignupResponse signup(@Valid @RequestBody SignupRequest request) {
        return authService.signup(request);
    }

    @Operation(summary = "로그인", description = """
                    전화번호+비밀번호 인증 후 토큰 발급. refresh는 jti 기준으로 Redis에 등록(다중 세션 허용).

                    - 인증(JWT): 불필요
                    - 요청 본문: `LoginRequest` — 전화번호·비밀번호
                    - 반환값: `LoginResponse` — `tokens`(accessToken·refreshToken)·`member` 요약·`requiresAgreement`(true면 재동의 유도)
                    - 부수효과: 인증 실패 시 전화번호 존재 여부와 무관하게 동일한 401 AUTHENTICATION_FAILED 반환(계정 열거 방지)
                    """)
    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest servletRequest) {
        return authService.login(request, ClientIp.resolve(servletRequest));
    }

    @Operation(summary = "토큰 재발급", description = """
                    refreshToken으로 accessToken만 재발급. 권한은 DB에서 재조회되어 새 access에 반영되고, refresh는 그대로 echo된다.

                    - 인증(JWT): 불필요
                    - 요청 본문: `RefreshRequest` — refreshToken
                    - 반환값: `RefreshResponse` — `tokens`(새 accessToken + 기존 refreshToken)
                    - 부수효과: refresh 만료·위변조·revoke·미등록·탈퇴 회원 시 401 INVALID_TOKEN
                    """)
    @PostMapping("/refresh")
    public RefreshResponse refresh(@Valid @RequestBody RefreshRequest request) {
        return authService.refresh(request);
    }

    @Operation(summary = "로그아웃", description = """
                    현재 access를 블랙리스트에 등록하고 본인 소유 refresh를 revoke(204). 무효·타인 refresh는 무시(멱등).

                    - 인증(JWT): 필요 — 로그인(본인)
                    - 요청 본문: `LogoutRequest` — refreshToken
                    - 반환값: 없음(204)
                    - 부수효과: access jti를 남은 수명만큼 Redis 블랙리스트 등록 + refresh jti revoke → 두 토큰 재사용 불가
                    """)
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("isAuthenticated()")
    public void logout(
            @AuthenticationPrincipal MemberPrincipal principal,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @Valid @RequestBody LogoutRequest request) {
        String accessToken = (authorization != null && authorization.startsWith("Bearer "))
                ? authorization.substring(7)
                : authorization;
        authService.logout(principal, accessToken, request.refreshToken());
    }
}
