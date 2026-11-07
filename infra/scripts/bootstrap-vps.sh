#!/usr/bin/env bash
set -euo pipefail
umask 077

fail() { printf '%s\n' "$*" >&2; exit 1; }
usage() { printf '%s\n' 'Uso: bootstrap-vps.sh --public-key-file <chave-publica-ed25519> [--domain <dominio>]'; }

deploy_user=minhaobra-deploy
deploy_home=/home/minhaobra-deploy
base=/var/www/apps/minha-obra
webroot=/var/www/certbot
app_domain=focuslabapp.com
vps_host=129.121.49.54
vps_port=22022
public_key_file=
while (( $# )); do
  case "$1" in
    --public-key-file) (( $# >= 2 )) || fail 'Informe o arquivo da chave pública.'; public_key_file=$2; shift 2 ;;
    --domain) (( $# >= 2 )) || fail 'Informe o domínio.'; app_domain=$2; shift 2 ;;
    --help|-h) usage; exit 0 ;;
    *) usage >&2; fail 'Argumento desconhecido.' ;;
  esac
done
[[ $EUID == 0 ]] || fail 'Execute como root na VPS.'
[[ -n $public_key_file ]] || fail 'Forneça --public-key-file; nunca forneça uma chave privada.'
for dependency in adduser usermod getent docker python3 realpath ssh-keygen openssl sshd awk grep; do
  command -v "$dependency" >/dev/null || fail "Dependência ausente: $dependency. Nada será instalado automaticamente."
done
# Inventário específico desta VPS; não alterar a instalação para contornar falhas.
source /etc/os-release
[[ $ID == ubuntu && $VERSION_ID == 22.04 ]] || fail 'Bootstrap destinado ao Ubuntu 22.04.'
[[ -z ${DOCKER_HOST:-} ]] || fail 'DOCKER_HOST externo não é permitido.'
docker_endpoint=$(docker context inspect --format '{{.Endpoints.docker.Host}}')
case "$docker_endpoint" in
  unix:///var/run/docker.sock|unix:///run/docker.sock) ;;
  *) fail 'Docker precisa apontar para o socket local.' ;;
esac
[[ -S ${docker_endpoint#unix://} ]] || fail 'Socket Docker local ausente.'
docker version --format '{{.Server.Version}}' >/dev/null
getent group docker >/dev/null || fail 'Grupo docker ausente; verificar a instalação existente.'
if docker compose version >/dev/null 2>&1; then
  compose_cmd=(docker compose)
elif command -v docker-compose >/dev/null 2>&1 && docker-compose version >/dev/null 2>&1; then
  compose_cmd=(docker-compose)
else
  fail 'Docker Compose não está disponível. Nada será instalado automaticamente.'
fi
compose_version=$("${compose_cmd[@]}" version --short)
up_help=$("${compose_cmd[@]}" up --help)
grep -q -- '--wait ' <<< "$up_help" || fail 'Compose sem suporte a up --wait.'
grep -q -- '--wait-timeout' <<< "$up_help" || fail 'Compose sem suporte a --wait-timeout.'

public_key_file=$(realpath -sm -- "$public_key_file")
normalized_key=$(python3 - "$public_key_file" "$app_domain" <<'PY'
import os, pathlib, re, stat, sys
path, domain = sys.argv[1:]
labels = domain.split('.')
if len(domain) > 253 or len(labels) < 2 or any(not re.fullmatch(r'[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?', label) for label in labels):
    raise SystemExit('Domínio inválido.')
if os.path.realpath(path) != path:
    raise SystemExit('Arquivo da chave pública não pode usar symlinks ou aliases de caminho.')
fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
with os.fdopen(fd, 'r', encoding='utf-8') as stream:
    if not stat.S_ISREG(os.fstat(stream.fileno()).st_mode):
        raise SystemExit('Chave pública precisa ser um arquivo regular.')
    lines = [line.strip() for line in stream if line.strip()]
if len(lines) != 1:
    raise SystemExit('Forneça exatamente uma chave pública Ed25519.')
fields = lines[0].split()
if len(fields) < 2 or fields[0] != 'ssh-ed25519':
    raise SystemExit('Forneça somente uma chave pública Ed25519, sem opções.')
print(' '.join(fields[:2]))
PY
)
ssh-keygen -l -f "$public_key_file" >/dev/null || fail 'Chave pública inválida.'
certificate="/etc/letsencrypt/live/$app_domain/fullchain.pem"
[[ -f $certificate && -r $certificate ]] || fail 'Certificado fullchain existente e legível é obrigatório.'
certificate_host_result=$(LC_ALL=C openssl x509 -in "$certificate" -noout -checkhost "$app_domain") || fail 'Não foi possível validar o domínio do certificado.'
[[ $certificate_host_result == "Hostname $app_domain does match certificate" ]] || fail 'Certificado não cobre o domínio.'
openssl x509 -in "$certificate" -noout -checkend 0 >/dev/null || fail 'Certificado expirado.'
[[ -f /etc/letsencrypt/live/$app_domain/privkey.pem && -r /etc/letsencrypt/live/$app_domain/privkey.pem ]] || fail 'Arquivo da chave TLS existente e legível é obrigatório.'
host_public_key=/etc/ssh/ssh_host_ed25519_key.pub
[[ -f $host_public_key && -r $host_public_key ]] || fail 'Chave pública Ed25519 do host ausente.'
ssh-keygen -l -f "$host_public_key" >/dev/null || fail 'Chave pública do host inválida.'
read -r host_key_type host_key_blob _ < "$host_public_key"
[[ $host_key_type == ssh-ed25519 && -n $host_key_blob ]] || fail 'Tipo inesperado da chave pública do host.'

# A primeira passagem é somente leitura. A segunda revalida antes de criar os
# arquivos; nenhuma configuração privada é emitida em stdout/stderr.
manage_paths() {
  python3 - "$1" "$deploy_user" "$deploy_home" "$base" "$webroot" "$app_domain" "$normalized_key" <<'PY'
import base64, os, pathlib, pwd, secrets, shlex, stat, sys, uuid

mode, user, home, base, webroot, domain, key = sys.argv[1:]
try:
    account = pwd.getpwnam(user)
except KeyError:
    account = None
if account and (account.pw_dir != home or account.pw_shell != '/bin/bash'):
    raise SystemExit('Usuário existente tem home ou shell inesperado.')
if account is None and os.path.lexists(home):
    raise SystemExit('Home já existe sem o usuário esperado; verificar manualmente.')

def check_path(path, kind):
    if os.path.realpath(path) != path:
        raise SystemExit('Symlink ou alias de caminho não permitido: ' + path)
    try:
        info = os.lstat(path)
    except FileNotFoundError:
        return None
    valid = stat.S_ISDIR(info.st_mode) if kind == 'directory' else stat.S_ISREG(info.st_mode)
    if not valid:
        raise SystemExit('Tipo de arquivo inesperado: ' + path)
    if kind == 'file' and info.st_nlink != 1:
        raise SystemExit('Arquivo com hardlinks não permitido: ' + path)
    return info

paths = [(home, 'directory'), (home + '/.ssh', 'directory'),
         (home + '/.ssh/authorized_keys', 'file'), (base, 'directory'),
         (base + '/releases', 'directory'), (base + '/.env', 'file'),
         (webroot, 'directory')]
metadata = {path: check_path(path, kind) for path, kind in paths}
if metadata[home] and (metadata[home].st_uid != account.pw_uid or metadata[home].st_mode & 0o022):
    raise SystemExit('Home precisa pertencer ao usuário e não ser gravável por grupo/outros.')

def validate_env(fd):
    info = os.fstat(fd)
    if not account or not stat.S_ISREG(info.st_mode) or info.st_nlink != 1 or info.st_uid != account.pw_uid or stat.S_IMODE(info.st_mode) not in (0o600, 0o400):
        raise SystemExit('Configuração privada existente exige arquivo regular, dono correto e modo 600/400.')
    required = {'APP_DOMAIN', 'POSTGRES_PASSWORD', 'POSTGRES_ADMIN_PASSWORD', 'KAFKA_CLUSTER_ID'}
    allowed = required | {'APP_TOTP_ENCRYPTION_KEY'}
    seen = set()
    with os.fdopen(os.dup(fd), 'r', encoding='utf-8') as stream:
        for line in stream:
            line = line.strip()
            if not line or line.startswith('#'):
                continue
            name, separator, value = line.partition('=')
            if not separator or name not in allowed or name in seen or not value:
                raise SystemExit('Configuração privada existente tem nomes ausentes, duplicados ou inesperados.')
            if name == 'APP_DOMAIN' and value != domain:
                raise SystemExit('Domínio da configuração privada existente difere do domínio solicitado.')
            if name == 'APP_TOTP_ENCRYPTION_KEY':
                try:
                    if len(base64.b64decode(value.strip("\"'"), validate=True)) != 32:
                        raise ValueError()
                except ValueError:
                    raise SystemExit('Chave de autenticação existente inválida; ela não foi alterada.') from None
            seen.add(name)
    if not required.issubset(seen):
        raise SystemExit('Configuração privada existente está incompleta.')

def already_authorized(text):
    found = False
    key_type, key_blob = key.split()
    for line in text.splitlines():
        if not line.strip() or line.lstrip().startswith('#'):
            continue
        try:
            fields = shlex.split(line)
        except ValueError:
            continue
        for index in range(len(fields) - 1):
            if fields[index:index + 2] == [key_type, key_blob]:
                if index != 1 or fields[0] != 'restrict':
                    raise SystemExit('A mesma chave já existe com opções diferentes; verificar manualmente.')
                found = True
                break
    return found

if metadata[base + '/.env']:
    fd = os.open(base + '/.env', os.O_RDONLY | os.O_NOFOLLOW)
    try:
        validate_env(fd)
    finally:
        os.close(fd)
if metadata[home + '/.ssh/authorized_keys']:
    fd = os.open(home + '/.ssh/authorized_keys', os.O_RDONLY | os.O_NOFOLLOW)
    with os.fdopen(fd, 'r', encoding='utf-8') as stream:
        already_authorized(stream.read())
if mode == 'preflight':
    raise SystemExit(0)
if account is None:
    raise SystemExit('Usuário de deploy não foi criado.')

def open_directory(path, create=False):
    # Caminhar por fds evita seguir symlinks também entre validação e escrita.
    fd = os.open('/', os.O_RDONLY | os.O_DIRECTORY)
    try:
        for part in pathlib.PurePosixPath(path).parts[1:]:
            try:
                next_fd = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=fd)
            except FileNotFoundError:
                if not create:
                    raise
                os.mkdir(part, 0o755, dir_fd=fd)
                next_fd = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=fd)
                os.fchmod(next_fd, 0o755)
            os.close(fd)
            fd = next_fd
        return fd
    except BaseException:
        os.close(fd)
        raise

def owned_directory(path, permissions):
    fd = open_directory(path, create=True)
    os.fchown(fd, account.pw_uid, account.pw_gid)
    os.fchmod(fd, permissions)
    return fd

base_fd = owned_directory(base, 0o750)
os.close(owned_directory(base + '/releases', 0o750))
os.close(open_directory(webroot, create=True))
ssh_fd = owned_directory(home + '/.ssh', 0o700)
try:
    fd = os.open('authorized_keys', os.O_RDWR | os.O_APPEND | os.O_CREAT | os.O_NOFOLLOW, 0o600, dir_fd=ssh_fd)
    with os.fdopen(fd, 'r+', encoding='utf-8', newline='') as stream:
        info = os.fstat(stream.fileno())
        if not stat.S_ISREG(info.st_mode) or info.st_nlink != 1:
            raise SystemExit('authorized_keys precisa ser arquivo regular.')
        text = stream.read()
        present = already_authorized(text)
        os.fchown(stream.fileno(), account.pw_uid, account.pw_gid)
        os.fchmod(stream.fileno(), 0o600)
        if not present:
            stream.write(('\n' if text and not text.endswith('\n') else '') + 'restrict ' + key + '\n')
finally:
    os.close(ssh_fd)
try:
    fd = os.open('.env', os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600, dir_fd=base_fd)
except FileExistsError:
    fd = os.open('.env', os.O_RDONLY | os.O_NOFOLLOW, dir_fd=base_fd)
    try:
        validate_env(fd)
    finally:
        os.close(fd)
else:
    with os.fdopen(fd, 'w', encoding='utf-8', newline='\n') as stream:
        os.fchown(stream.fileno(), account.pw_uid, account.pw_gid)
        os.fchmod(stream.fileno(), 0o600)
        password = secrets.token_hex(32)
        admin_password = secrets.token_hex(32)
        while admin_password == password:
            admin_password = secrets.token_hex(32)
        cluster_id = base64.urlsafe_b64encode(uuid.uuid4().bytes).decode('ascii').rstrip('=')
        auth_key = base64.b64encode(secrets.token_bytes(32)).decode('ascii')
        stream.write(f'APP_DOMAIN={domain}\nPOSTGRES_PASSWORD={password}\nPOSTGRES_ADMIN_PASSWORD={admin_password}\nKAFKA_CLUSTER_ID={cluster_id}\nAPP_TOTP_ENCRYPTION_KEY={auth_key}\n')
finally:
    os.close(base_fd)
PY
}
manage_paths preflight
# Conferir a sintaxe/configuração SSH existente antes de qualquer alteração.
sshd -T -C "user=$deploy_user,host=github-actions,addr=192.0.2.1" >/dev/null

if ! getent passwd "$deploy_user" >/dev/null; then
  adduser --disabled-password --gecos '' --shell /bin/bash --home "$deploy_home" "$deploy_user"
fi
usermod -aG docker "$deploy_user"
manage_paths apply

printf '%s\n' "Preparado: usuário=$deploy_user base=$base domínio=$app_domain Compose=$compose_version"
printf '%s\n' 'SSH efetivo para endereço de exemplo 192.0.2.1; confirmar uma conexão real do runner:'
ssh_metadata=$(sshd -T -C "user=$deploy_user,host=github-actions,addr=192.0.2.1" | awk '$1 ~ /^(port|usepam|pubkeyauthentication|passwordauthentication|kbdinteractiveauthentication|authorizedkeysfile|authenticationmethods|allowusers|allowgroups|denyusers|denygroups|forcecommand|chrootdirectory|strictmodes)$/')
printf '%s\n' "$ssh_metadata"
if awk '$1 ~ /^(allowusers|allowgroups|denyusers|denygroups)$/ || ($1 == "usepam" && $2 == "no") || ($1 == "pubkeyauthentication" && $2 == "no") || (($1 == "forcecommand" || $1 == "chrootdirectory") && $2 != "none") { found=1 } END { exit !found }' <<< "$ssh_metadata" ||
   ! grep -Eq '^authenticationmethods (any|publickey)$' <<< "$ssh_metadata" ||
   ! grep -Eq '^authorizedkeysfile (.* )?\.ssh/authorized_keys( |$)' <<< "$ssh_metadata"; then
  printf '%s\n' 'Aviso: há política SSH personalizada; verificar autenticação real antes do deploy. Nenhuma política foi alterada.' >&2
fi
printf '%s\n' 'Host key pública para VPS_KNOWN_HOSTS (conferir fingerprint neste terminal):'
printf '[%s]:%s %s %s\n' "$vps_host" "$vps_port" "$host_key_type" "$host_key_blob"
ssh-keygen -l -f "$host_public_key"
printf '%s\n' 'Bootstrap concluído. Nenhum container foi iniciado; configuração privada existente foi preservada.'
