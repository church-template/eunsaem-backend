#!/usr/bin/env bash
# 새 교회 등록(#86). 운영 서버에서 실행한다: cd /srv/church-backend && ./scripts/new-church.sh
#
# 교회 1행 + 기본 역할 4종 + 교회 대표자 계정(SUPER_ADMIN)을 한 번에 만든다. 실패하면 아무것도 남지 않는다.
# 앱 재시작·DNS 작업은 필요 없다. 끝나면 그 교회 프론트에 넣을 NEXT_PUBLIC_API_BASE 값을 출력한다.
#
# 환경변수(선택):
#   POSTGRES_CONTAINER  DB 컨테이너 이름 (기본 church-backend-postgres-1)
#   ENV_FILE            DB_USERNAME·DB_NAME을 읽을 .env (기본 /srv/church-backend/.env, 없으면 ./.env)
#   API_BASE            안내 출력에 쓸 공용 API 주소 (기본 https://<공용API>)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SQL_FILE="$SCRIPT_DIR/new-church.sql"
POSTGRES_CONTAINER="${POSTGRES_CONTAINER:-church-backend-postgres-1}"
API_BASE="${API_BASE:-https://<공용API>}"

[ -f "$SQL_FILE" ] || { echo "new-church.sql을 찾을 수 없습니다: $SQL_FILE" >&2; exit 1; }

ENV_FILE="${ENV_FILE:-/srv/church-backend/.env}"
[ -f "$ENV_FILE" ] || ENV_FILE="./.env"
env_value() { # .env에서 KEY=VALUE 한 줄을 읽는다(파일을 실행하지 않는다)
    [ -f "$ENV_FILE" ] && grep -E "^$1=" "$ENV_FILE" | tail -n 1 | cut -d= -f2- || true
}
DB_USERNAME="$(env_value DB_USERNAME)"; DB_USERNAME="${DB_USERNAME:-church_user}"
DB_NAME="$(env_value DB_NAME)"; DB_NAME="${DB_NAME:-church_db}"

read -r -p "교회 이름: " NC_NAME
read -r -p "교회 키(slug, 소문자·숫자·하이픈): " NC_SLUG
read -r -p "프론트 주소(https://…): " NC_ORIGIN
read -r -p "교회 대표자 전화번호: " NC_PHONE
read -r -p "교회 대표자 이름: " NC_OWNER_NAME
# IFS= : 비밀번호 앞뒤 공백을 잘라내지 않는다(입력한 그대로가 비밀번호다).
IFS= read -r -s -p "임시 비밀번호(8자 이상, 화면에 보이지 않음): " NC_PW; echo
IFS= read -r -s -p "임시 비밀번호 확인: " NC_PW_CONFIRM; echo

fail() { echo "오류: $1" >&2; exit 1; }
[ -n "$NC_NAME" ] || fail "교회 이름이 비었습니다"
[[ "$NC_SLUG" =~ ^[a-z0-9-]{1,40}$ ]] || fail "교회 키는 소문자·숫자·하이픈 1~40자여야 합니다"
[[ "$NC_ORIGIN" =~ ^https://[^/[:space:]]+$ ]] || fail "프론트 주소는 https://로 시작하고 경로·끝 슬래시가 없어야 합니다 (예: https://www.example.com)"
# 전화번호는 숫자만 남겨 저장한다. +82 10-… 처럼 넣으면 8210…으로 저장돼 010…으로 로그인할 수 없다.
[[ "${NC_PHONE//[^0-9]/}" =~ ^0[0-9]{8,10}$ ]] || fail "전화번호는 0으로 시작하는 국내 번호로 넣습니다 (예: 010-1234-5678)"
[ -n "$NC_OWNER_NAME" ] || fail "대표자 이름이 비었습니다"
[ "${#NC_PW}" -ge 8 ] || fail "임시 비밀번호는 8자 이상이어야 합니다"
[ "$NC_PW" = "$NC_PW_CONFIRM" ] || fail "임시 비밀번호가 서로 다릅니다"

cat <<CONFIRM

다음 값으로 교회를 등록합니다.
  교회 이름      : $NC_NAME
  교회 키(slug)  : $NC_SLUG
  프론트 주소    : $NC_ORIGIN
  대표자 전화번호: $NC_PHONE
  대표자 이름    : $NC_OWNER_NAME
  DB             : $POSTGRES_CONTAINER / $DB_NAME ($DB_USERNAME)
CONFIRM
read -r -p "진행할까요? (y/N): " ANSWER
[ "$ANSWER" = "y" ] || { echo "취소했습니다."; exit 0; }

# 값은 환경변수로 넘긴다(docker exec -e 이름만 지정) — 비밀번호가 프로세스 목록이나 셸 히스토리에 남지 않는다.
# psql의 \getenv가 환경변수를 psql 변수로 옮기고, SQL은 표준 입력으로 넘긴다.
export NC_NAME NC_SLUG NC_ORIGIN NC_PHONE NC_OWNER_NAME NC_PW
{
    printf '%s\n' \
        '\getenv name NC_NAME' \
        '\getenv slug NC_SLUG' \
        '\getenv origin NC_ORIGIN' \
        '\getenv phone NC_PHONE' \
        '\getenv owner_name NC_OWNER_NAME' \
        '\getenv pw NC_PW'
    cat "$SQL_FILE"
} | docker exec -i \
    -e NC_NAME -e NC_SLUG -e NC_ORIGIN -e NC_PHONE -e NC_OWNER_NAME -e NC_PW \
    "$POSTGRES_CONTAINER" \
    psql -U "$DB_USERNAME" -d "$DB_NAME" -v ON_ERROR_STOP=1 --quiet -f - >/dev/null

cat <<DONE

등록했습니다.

1) 이 교회 프론트의 환경변수에 넣고 빌드·배포합니다:
     NEXT_PUBLIC_API_BASE=$API_BASE/c/$NC_SLUG
2) 확인(200과 빈 목록이 나오면 정상):
     curl -s $API_BASE/c/$NC_SLUG/api/main
3) 교회 대표자에게 전화번호와 임시 비밀번호를 전달합니다. 첫 로그인 후 비밀번호를 바꾸도록 안내하세요.
DONE
