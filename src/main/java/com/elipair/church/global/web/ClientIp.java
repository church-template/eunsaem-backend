package com.elipair.church.global.web;

import jakarta.servlet.http.HttpServletRequest;

/** 요청의 클라이언트 IP. 리버스 프록시 뒤에 서므로 X-Forwarded-For의 첫 홉을 우선 사용한다(문의·로그인 제한 공용). */
public final class ClientIp {

    private ClientIp() {}

    public static String resolve(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
