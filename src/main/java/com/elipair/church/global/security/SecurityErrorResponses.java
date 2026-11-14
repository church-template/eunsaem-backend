package com.elipair.church.global.security;

import com.elipair.church.global.exception.ErrorCode;
import com.elipair.church.global.exception.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import tools.jackson.databind.ObjectMapper;

/** 필터 단계 인증·인가 실패를 G2와 동일한 RFC 7807 봉투로 직렬화하는 공용 헬퍼. */
public final class SecurityErrorResponses {

    private SecurityErrorResponses() {}

    public static void write(
            HttpServletResponse response, HttpServletRequest request, ErrorCode code, ObjectMapper mapper)
            throws IOException {
        response.setStatus(code.getStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        mapper.writeValue(response.getWriter(), ErrorResponse.of(code, request.getRequestURI()));
    }
}
