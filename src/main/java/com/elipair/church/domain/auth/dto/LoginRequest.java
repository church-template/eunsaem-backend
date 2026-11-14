package com.elipair.church.domain.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 길이 상한은 로그인 시도 제한 때문이다(#86): 전화번호는 실패 횟수의 Redis 키에 들어가므로,
 * 제한이 없으면 거대한 숫자열로 Redis 메모리를 채울 수 있다. 하이픈·공백을 포함한 입력을 넉넉히 받는 값이다.
 */
public record LoginRequest(
        @NotBlank @Size(max = 30) String phone,
        @NotBlank @Size(max = 200) String password) {}
