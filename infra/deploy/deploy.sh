#!/usr/bin/env bash
set -euo pipefail

fail() { printf '%s\n' "$*" >&2; exit 1; }
[[ $# == 2 ]] || fail 'Uso: deploy.sh <sha completo> <owner/repository>'
release_sha=$1
repository=$2
[[ $release_sha =~ ^[0-9a-f]{40}$ ]] || fail 'SHA inválido.'
[[ $repository =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ ]] || fail 'Repositório inválido.'
base=/var/www/apps/minha-obra
[[ -d $base && ! -L $base ]] || fail 'Criar o diretório de deploy real antes da publicação.'
[[ $(realpath -e "$base") == "$base" ]] || fail 'Diretório de deploy inesperado.'
release_dir="$base/releases/$release_sha"
[[ -d $release_dir && ! -L $release_dir ]] || fail 'Release não encontrada.'
[[ $(realpath -e "$release_dir") == "$release_dir" ]] || fail 'Release com caminho inesperado.'
[[ $(realpath -e "$(dirname "$BASH_SOURCE")") == "$release_dir" ]] || fail 'Execute o script da própria release.'
[[ -f "$base/.env" && ! -L "$base/.env" ]] || fail 'Configuração privada da VPS ausente.'
env_mode=$(stat -c '%a' "$base/.env")
[[ $env_mode == 600 || $env_mode == 400 ]] || fail 'Proteja a configuração com chmod 600.'
if grep -Eq '^(POSTGRES_PASSWORD|POSTGRES_ADMIN_PASSWORD|KAFKA_CLUSTER_ID)=REPLACE|^APP_DOMAIN=replace\.' "$base/.env"; then
  fail 'Preencha a configuração privada; não usar os placeholders do exemplo.'
fi
for dependency in docker python3 flock realpath; do
  command -v "$dependency" >/dev/null || fail "Dependência ausente: $dependency"
done
exec 9>"$base/.deploy.lock"
flock -n 9 || fail 'Já existe um deploy em execução.'
downloads="$base/downloads"
[[ ! -L $downloads ]] || fail 'Diretório de downloads inesperado.'
install -d -m 0755 "$downloads"
[[ $(realpath -e "$downloads") == "$downloads" ]] || fail 'Caminho de downloads inesperado.'
[[ -z ${DOCKER_HOST:-} ]] || fail 'DOCKER_HOST externo não é permitido.'
docker_endpoint=$(docker context inspect --format '{{.Endpoints.docker.Host}}')
case "$docker_endpoint" in
  unix:///var/run/docker.sock|unix:///run/docker.sock) ;;
  *) fail 'O contexto Docker precisa apontar para o daemon local.' ;;
esac
if docker compose version >/dev/null 2>&1; then
  compose_cmd=(docker compose)
elif command -v docker-compose >/dev/null && docker-compose version >/dev/null 2>&1; then
  compose_cmd=(docker-compose)
else
  fail 'Docker Compose indisponível; não foi feita atualização automática.'
fi
up_help=$("${compose_cmd[@]}" up --help)
grep -q -- '--wait ' <<< "$up_help" || fail 'Compose sem suporte a up --wait.'
grep -q -- '--wait-timeout' <<< "$up_help" || fail 'Compose sem suporte a --wait-timeout.'
free_kib=$(df -Pk "$base" | awk 'NR == 2 {print $4}')
[[ $free_kib =~ ^[0-9]+$ ]] && (( free_kib >= 2097152 )) || fail 'Exigir ao menos 2 GiB livres antes do pull.'
python3 "$release_dir/write_release_env.py" \
  "$release_dir/release.json" "$release_dir/release.env" \
  --sha "$release_sha" --repository "$repository"
python3 "$release_dir/initialize_auth_key.py" "$base/.env"
for name in POSTGRES_PASSWORD POSTGRES_ADMIN_PASSWORD APP_DOMAIN KAFKA_CLUSTER_ID APP_TOTP_ENCRYPTION_KEY APP_EVENTS_ENABLED APP_PUSH_ENABLED APP_PUSH_PROJECT_ID APP_PUSH_CREDENTIALS_HOST_FILE BACKEND_IMAGE FRONTEND_IMAGE; do
  unset "$name"
done
compose_args=(-p minha-obra --env-file "$base/.env" --env-file "$release_dir/release.env" -f "$release_dir/compose.prod.yml")
"${compose_cmd[@]}" "${compose_args[@]}" config --quiet
# Não imprimir config/env/inspect completo, não compilar e não remover volumes.
"${compose_cmd[@]}" "${compose_args[@]}" pull
"${compose_cmd[@]}" "${compose_args[@]}" up -d --no-build --wait --wait-timeout 300
"${compose_cmd[@]}" "${compose_args[@]}" ps
printf '%s\n' "$release_sha" > "$base/.current-release.next"
mv "$base/.current-release.next" "$base/.current-release"
printf '%s\n' 'Release iniciada e healthchecks aprovados. O workflow verificará HTTPS externamente.'
