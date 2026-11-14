package com.elipair.church.domain.inquiry;

import com.elipair.church.domain.inquiry.dto.InquiryCreateRequest;
import com.elipair.church.domain.inquiry.dto.InquiryCreatedResponse;
import com.elipair.church.global.web.ClientIp;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 문의 공개 등록 API(이슈 #50). 비인증 — SecurityConfig anyRequest permitAll. */
@Tag(name = "문의", description = "방문자 문의 등록/관리 API(이슈 #50)")
@RestController
public class InquiryController {

    private final InquiryService service;

    public InquiryController(InquiryService service) {
        this.service = service;
    }

    @Operation(summary = "문의 등록", description = """
                    방문자가 문의를 남긴다(201 Created).

                    - 인증(JWT): 불필요 — 누구나 제출 가능
                    - 요청 본문: `InquiryCreateRequest` — 이름(필수)·연락처(필수)·이메일(선택)·문의 내용(필수, 10자 이상)·`privacyAgreed`(필수 true)
                    - 반환값: `InquiryCreatedResponse` — 접수 번호(`id`)만. 개인정보는 되돌려주지 않는다
                    - 부수효과: 연락처를 숫자만 남겨 정규화 저장 · 동의 시각 기록
                    - 제한: 같은 IP에서 1시간에 5건 초과 시 429 RATE_LIMIT_EXCEEDED
                    """)
    @PostMapping("/api/inquiries")
    public ResponseEntity<InquiryCreatedResponse> create(
            @Valid @RequestBody InquiryCreateRequest request, HttpServletRequest servletRequest) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.create(request, ClientIp.resolve(servletRequest)));
    }
}
