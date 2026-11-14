package com.elipair.church.global.tenant;

/** 교회 한 건(churches 행). JPA 엔티티가 아니다 — {@link ChurchResolver}가 JdbcClient로 읽는다. */
public record ChurchInfo(long id, String slug, String name, String frontendOrigin) {}
