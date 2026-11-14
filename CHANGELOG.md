# Changelog

**현재 버전:** 0.0.54  
**마지막 업데이트:** 2026-11-14T14:28:58Z  

---

## [0.0.54] - 2026-11-14

**PR:** #89  

**기타**
- Merge pull request #88 from church-template/20261114_#86_교회_경로_접두사를_church로_변경
- docs : 미디어 URL 조립과 접두사 누락 시 동작 설명 정정 https://github.com/church-template/church-backend/issues/86
- refactor : 교회 경로 접두사를 /c/에서 /church/로 변경 https://github.com/church-template/church-backend/issues/86
- Merge pull request #87 from church-template/20261113_#86_기능개선_아키텍처_교회별_서버_분리_구조를_멀티테넌시로_전환
- fix : 역할 priority를 요청자와 같은 등급으로 정할 수 없게 제한 https://github.com/church-template/eunsaem-backend/issues/86
- docs : 운영 절차서의 수동 재생성 명령과 백업 기대값 정정 https://github.com/church-template/eunsaem-backend/issues/86
- fix : 백업 정리를 전송보다 먼저 하고 교회 등록 입력 검증 보강 https://github.com/church-template/eunsaem-backend/issues/86
- docs : 운영 전환 절차서 보강(백업 cron, 수동 백업 위치, 되돌릴 버전 기록) https://github.com/church-template/eunsaem-backend/issues/86
- test : 기존 데이터가 있는 상태의 church_id 마이그레이션 검증 https://github.com/church-template/eunsaem-backend/issues/86
- fix : 로그인 요청의 전화번호·비밀번호 길이 제한 https://github.com/church-template/eunsaem-backend/issues/86
- docs : 멀티테넌시 전환에 맞춰 스펙과 규칙 문서 갱신 https://github.com/church-template/eunsaem-backend/issues/86
- chore : 운영 전환 절차서와 백업 스크립트 추가 https://github.com/church-template/eunsaem-backend/issues/86
- feat : 새 교회 등록 스크립트 추가 https://github.com/church-template/eunsaem-backend/issues/86
- test : 교회 간 격리 테스트 추가 https://github.com/church-template/eunsaem-backend/issues/86
- fix : 관리자 작업 위계 검사와 로그인 시도 제한 추가 https://github.com/church-template/eunsaem-backend/issues/86
- feat : 업로드 경로에 교회 접두사 추가 https://github.com/church-template/eunsaem-backend/issues/86
- feat : 캐시와 조회수 버퍼를 교회별로 분리 https://github.com/church-template/eunsaem-backend/issues/86
- feat : 토큰에 교회 식별자 추가와 교회별 CORS https://github.com/church-template/eunsaem-backend/issues/86
- feat : 네이티브 쿼리에 교회 조건 추가 https://github.com/church-template/eunsaem-backend/issues/86
- feat : 전 테이블 church_id 추가와 테넌트 필터 적용 https://github.com/church-template/eunsaem-backend/issues/86
- feat : 테넌트 식별 기반과 churches 테이블 추가 https://github.com/church-template/eunsaem-backend/issues/86

---

## [0.0.52] - 2026-11-10

**PR:** #85  

**기타**
- Merge pull request #84 from church-template/20261110_#81_pull_request_target_복원_skip_ci_대응
- Merge pull request #82 from church-template/20261110_#81_기능개선_CI_릴리스_워크플로_트리거를_pull_request로_전환
- fix : 릴리스 워크플로 트리거를 pull_request로 전환 #81

---

## [0.0.50] - 2026-11-09

**PR:** #77  

**문서**
- README를 한국어로 개편하고 프로젝트 소개, 빠른 시작, 기술 스택 및 배포 안내를 추가했습니다.
- 내부 설계·구현 계획 문서를 정리했습니다.

**유지보수**
- CI 및 배포 워크플로에서 사용하는 작업을 최신 버전으로 업데이트했습니다.
- 프로젝트 버전을 0.0.50으로 업데이트했습니다.

---

## [0.0.45] - 2026-09-03

**PR:** #73  

**기타**
- Merge pull request #72 from church-template/20260903_#71_배포_시_GHCR_로그인_서버_영구_자격증명_제거
- feat : 배포 시 GHCR 로그인으로 서버 영구 자격증명 제거 #71

---

## [0.0.44] - 2026-09-02

**PR:** #70  

**기타**
- Merge pull request #69 from church-template/20260902_#68_챌린지_참여자_진도_명단_조회_API
- feat : 챌린지 참여자 진도 명단 조회 API #68

---

## [0.0.43] - 2026-07-22

**PR:** #67  

**기타**
- Merge pull request #66 from church-template/20260722_#65_픽업_위치_좌표_신청_명단_반영
- feat : 차량 탑승 신청 픽업 좌표(위경도) 추가 #65

---

## [0.0.42] - 2026-07-21

**PR:** #64  

**기타**
- Merge pull request #63 from church-template/20260721_#62_차량운행_신청_API
- docs : 차량운행 경로 인가·도메인 문서 동기화 #62
- fix : 동시 중복 신청 레이스를 409 DUPLICATE_RESOURCE로 매핑 #62
- feat : 운행일별 통합 명단 조회 API #62
- feat : 차량운행 조회·탑승 신청·취소 API #62
- test : MeApiTest MEMBER 권한 목록에 VEHICLE_APPLY 반영 #62
- feat : 운행일 관리 CRUD API #62
- feat : /api/vehicle-runs 경로 인가(VEHICLE_APPLY) 추가 #62
- feat : 차량운행 테이블·권한 시드 마이그레이션(V16) #62
- docs : 차량운행 신청 설계·구현 계획 #62

---

## [0.0.41] - 2026-07-20

**PR:** #61  

**새 기능**
- 운영 환경에서 PostgreSQL 서비스를 추가로 사용할 수 있습니다.
- 데이터베이스 포트는 로컬 호스트에만 연결되어 외부에 직접 노출되지 않습니다.
- **문서**
- README에 표시되는 제품 버전과 업데이트 날짜가 최신 정보로 갱신되었습니다.
- **릴리스**
- 제품 버전이 `0.0.41`로 업데이트되었습니다.
- 버전 코드와 관련 메타데이터가 새 릴리스에 맞게 동기화되었습니다.

---

## [0.0.40] - 2026-07-20

**PR:** #58  

**기타**
- Merge pull request #57 from church-template/20260720_#56_AI_에이전트_개발자_온보딩_문서_체계_구축
- fix : CodeRabbit 반영 — 시드 정리 조건 9000~9999 한정(실데이터 보호)·플랜 Expected 정합 #56
- docs : CLAUDE.md 스테일 단락 현행화 — early scaffold·미배선 목록 정정 (최종 리뷰 반영) #56
- docs : README 신설(문서 지도) + CLAUDE.md에 setup 문서 링크 추가 #56
- docs : AI 공용 진입점 AGENTS.md 신설 (불변식 요약·읽기 순서·명령어) #56
- docs : 새 교회 배포 정본 문서 setup-new-church.md 신설 (코드 수정 0 원칙) #56
- docs : 로컬 개발 정본 문서 setup-dev.md 신설 (오버라이드·시드·검증·함정) #56
- docs : 경로 인가 5단·신규 도메인 현행화 (CLAUDE.md·rbac 규칙 동기화) #56
- docs : 스펙 §6 정정 — override는 처음부터 미추적(오독), Task 1은 no-op #56
- Merge branch 'main' of https://github.com/church-template/church-backend
- docs : AI 온보딩 문서 체계 구현 플랜

---

## [0.0.38] - 2026-07-20

**PR:** #55  

**기타**
- Merge branch 'main' of https://github.com/church-template/church-backend
- docs : AI 온보딩 문서 체계 설계 스펙 (AGENTS.md·README·setup 문서 + 기존 문서 동기화)
- chore : 필요 파일 생성
- Merge pull request #54 from church-template/20260719_#53_설교_회원전용_전환
- docs : 설교 회원전용 전환 스펙·플랜(+/api/main 공개 유지 결정 명시) #53
- test : /api/main 클릭스루 상세(/api/sermons/{id}) 회원전용 차단 검증 추가 #53
- feat : 설교 조회를 SERMON_VIEW 회원전용으로 게이트 #53
- feat : 설교 회원전용용 SERMON_VIEW 권한 신설·시드 #53

---

## [0.0.35] - 2026-07-16

**PR:** #52  

**기타**
- docs : 배포 가이드에 Caddy HTTPS 리버스 프록시 섹션 추가
- fix : 리버스 프록시 뒤 https 인식 위해 forward-headers-strategy 설정

---

