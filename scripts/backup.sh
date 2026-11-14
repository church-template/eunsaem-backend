#!/usr/bin/env bash
# DB와 업로드 파일 백업(#86). 운영 서버에서 실행한다: cd /srv/church-backend && ./scripts/backup.sh
#
# 하는 일
#   1) pg_dump -Fc(커스텀 형식)로 DB 전체를 덤프하고, 덤프가 실제로 열리는지 확인한다.
#   2) 업로드 볼륨(/app/uploads)을 tar로 묶고, tar가 읽히는지 확인한다.
#   3) 두 파일을 gpg 대칭 암호화한다(전 교회 회원의 전화번호·비밀번호 해시가 들어 있다).
#   4) BACKUP_UPLOAD_CMD가 있으면 서버 밖으로 보낸다. 같은 서버에만 둔 백업은 서버와 함께 사라진다.
#   5) 로컬 사본은 KEEP_DAYS일치만 남기고, 디스크 사용량이 DISK_WARN_PERCENT 이상이면 경고한다.
#
# 서비스를 멈추지 않는다(pg_dump는 실행 중에도 일관된 스냅샷을 뜬다). 전환 당일처럼 "덤프 이후 쓰기가
# 없어야" 할 때는 운영 절차서대로 backend를 먼저 멈춘 뒤 실행한다 — 멈춘 컨테이너에서도 동작한다.
#
# 환경변수
#   BACKUP_PASSPHRASE   암호화 비밀번호(필수). BACKUP_ENCRYPT=0이면 암호화하지 않는다(권장하지 않음).
#   BACKUP_UPLOAD_CMD   파일 경로 하나를 인자로 받아 서버 밖으로 보내는 명령(선택). 예: "oci os object put -bn church-backup --file"
#   BACKUP_DIR          저장 위치 (기본 ~/backup)
#   KEEP_DAYS           로컬 보관 일수 (기본 7)
#   DISK_WARN_PERCENT   디스크 경고 기준 % (기본 70)
#   POSTGRES_CONTAINER  (기본 church-backend-postgres-1)
#   BACKEND_CONTAINER   (기본 church-backend-backend-1)
#   UPLOAD_PATH         컨테이너 안 업로드 경로 (기본 /app/uploads)
#   ENV_FILE            DB_USERNAME·DB_NAME을 읽을 .env (기본 /srv/church-backend/.env, 없으면 ./.env)
#
# 종료 코드: 0 정상 / 1 백업 실패 / 2 백업은 됐지만 경고 있음(서버 밖 전송 안 함, 디스크 사용량 초과)
set -euo pipefail

BACKUP_DIR="${BACKUP_DIR:-$HOME/backup}"
KEEP_DAYS="${KEEP_DAYS:-7}"
DISK_WARN_PERCENT="${DISK_WARN_PERCENT:-70}"
POSTGRES_CONTAINER="${POSTGRES_CONTAINER:-church-backend-postgres-1}"
BACKEND_CONTAINER="${BACKEND_CONTAINER:-church-backend-backend-1}"
UPLOAD_PATH="${UPLOAD_PATH:-/app/uploads}"
BACKUP_ENCRYPT="${BACKUP_ENCRYPT:-1}"

ENV_FILE="${ENV_FILE:-/srv/church-backend/.env}"
[ -f "$ENV_FILE" ] || ENV_FILE="./.env"
env_value() {
    [ -f "$ENV_FILE" ] && grep -E "^$1=" "$ENV_FILE" | tail -n 1 | cut -d= -f2- || true
}
DB_USERNAME="$(env_value DB_USERNAME)"; DB_USERNAME="${DB_USERNAME:-church_user}"
DB_NAME="$(env_value DB_NAME)"; DB_NAME="${DB_NAME:-church_db}"

fail() { echo "백업 실패: $1" >&2; exit 1; }

if [ "$BACKUP_ENCRYPT" = "1" ]; then
    [ -n "${BACKUP_PASSPHRASE:-}" ] || fail "BACKUP_PASSPHRASE가 없습니다(암호화 없이 받으려면 BACKUP_ENCRYPT=0)"
    command -v gpg >/dev/null || fail "gpg가 설치돼 있지 않습니다"
fi

mkdir -p "$BACKUP_DIR"
chmod 700 "$BACKUP_DIR"
TS="$(date +%Y%m%d-%H%M%S)"
DUMP="$BACKUP_DIR/db-$TS.dump"
UPLOADS="$BACKUP_DIR/uploads-$TS.tar"

# 실패하면 반쪽짜리 파일을, 암호화했으면 평문을 남기지 않는다.
DONE=0
cleanup() {
    if [ "$DONE" != "1" ] || [ "$BACKUP_ENCRYPT" = "1" ]; then
        rm -f "$DUMP" "$UPLOADS"
    fi
}
trap cleanup EXIT

# 1) DB 덤프 + 확인
docker exec "$POSTGRES_CONTAINER" pg_dump -U "$DB_USERNAME" -d "$DB_NAME" -Fc > "$DUMP" || fail "pg_dump"
TABLES="$(docker exec -i "$POSTGRES_CONTAINER" pg_restore --list < "$DUMP" | grep -c 'TABLE DATA' || true)"
[ "$TABLES" -gt 0 ] || fail "덤프에 테이블 데이터가 없습니다"

# 2) 업로드 파일 + 확인 (docker cp는 멈춘 컨테이너에서도 동작한다)
docker cp "$BACKEND_CONTAINER:$UPLOAD_PATH" - > "$UPLOADS" || fail "업로드 파일 복사"
FILES="$(tar tf "$UPLOADS" | grep -vc '/$' || true)"

echo "덤프: 테이블 데이터 $TABLES개 / 업로드: 파일 $FILES개"

# 3) 암호화 (비밀번호는 파일 디스크립터로 넘긴다 — 프로세스 목록에 남지 않는다)
OUTPUTS=("$DUMP" "$UPLOADS")
if [ "$BACKUP_ENCRYPT" = "1" ]; then
    OUTPUTS=()
    for plain in "$DUMP" "$UPLOADS"; do
        gpg --batch --yes --quiet --pinentry-mode loopback --passphrase-fd 3 \
            --symmetric --cipher-algo AES256 -o "$plain.gpg" "$plain" 3<<<"$BACKUP_PASSPHRASE" \
            || fail "암호화: $plain"
        rm -f "$plain"
        OUTPUTS+=("$plain.gpg")
    done
fi
chmod 600 "${OUTPUTS[@]}"
ls -lh "${OUTPUTS[@]}"

WARN=0

# 4) 서버 밖으로 전송
if [ -n "${BACKUP_UPLOAD_CMD:-}" ]; then
    for file in "${OUTPUTS[@]}"; do
        bash -c "$BACKUP_UPLOAD_CMD \"\$1\"" _ "$file" || fail "서버 밖 전송: $file"
    done
    echo "서버 밖으로 보냈습니다."
else
    echo "경고: BACKUP_UPLOAD_CMD가 없어 서버 안에만 있습니다. 파일을 서버 밖으로 옮기세요: $BACKUP_DIR" >&2
    WARN=2
fi

# 5) 오래된 로컬 사본 정리 + 디스크 사용량
find "$BACKUP_DIR" -maxdepth 1 -type f \( -name 'db-*' -o -name 'uploads-*' \) -mtime "+$KEEP_DAYS" -delete
USED="$(df --output=pcent / 2>/dev/null | tail -n 1 | tr -dc '0-9' || true)"
[ -n "$USED" ] || USED="$(df -P / | tail -n 1 | awk '{print $5}' | tr -dc '0-9')"
if [ "$USED" -ge "$DISK_WARN_PERCENT" ]; then
    echo "경고: 디스크 사용량 ${USED}% (기준 ${DISK_WARN_PERCENT}%). 업로드 파일이 차고 있다면 객체 저장소 이전을 검토하세요." >&2
    WARN=2
fi

DONE=1
exit "$WARN"
