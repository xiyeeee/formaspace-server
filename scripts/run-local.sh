#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

if [[ ! -f .env.mysql ]]; then
  echo '缺少 .env.mysql，请先按 docs/local-mysql.md 配置本地数据库。' >&2
  exit 1
fi

# This project-owned file quotes the JDBC URL so it can be loaded by the shell.
set -a
source .env.mysql
set +a

if [[ ! -f .env.auth ]]; then
  (
    umask 077
    auth_file=$(mktemp .env.auth.XXXXXX)
    trap 'rm -f "$auth_file"' EXIT
    printf 'FORMASPACE_LOCAL_JWT_SECRET=%s\n' "$(openssl rand -base64 32)" > "$auth_file"
    printf 'FORMASPACE_LOCAL_HMAC_KEY=%s\n' "$(openssl rand -base64 32)" >> "$auth_file"
    mv "$auth_file" .env.auth
  )
  echo '已生成本地鉴权密钥并保存在 .env.auth；后续启动复用，不输出密钥。'
fi
source .env.auth

export JWT_SECRET="${JWT_SECRET:-$FORMASPACE_LOCAL_JWT_SECRET}"
export AUTH_CODE_HMAC_KEY="${AUTH_CODE_HMAC_KEY:-$FORMASPACE_LOCAL_HMAC_KEY}"
export SPRING_PROFILES_ACTIVE=local
if [[ -z "${JAVA_HOME:-}" && -x /usr/libexec/java_home ]]; then
  export JAVA_HOME
  JAVA_HOME=$(/usr/libexec/java_home -v 21)
fi

exec ./mvnw -s "${FORMASPACE_MAVEN_SETTINGS:-scripts/maven-local-settings.xml}" spring-boot:run "$@"
