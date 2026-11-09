# VPS e retirada do FocusLab

Inventário fornecido pelo operador em 06/11/2026:

| Item | Valor |
| --- | --- |
| Sistema | Ubuntu 22.04.5 LTS x86_64 |
| CPU | 1 vCPU, host AMD EPYC |
| RAM | 1,9 GiB; 1,1 GiB disponíveis com FocusLab ativo |
| Swap | 4 GiB; 96 MiB em uso |
| Disco | 50 GB; 31 GB usados; 17 GB livres |
| Docker | 29.1.3 |
| Compose | docker-compose standalone 2.24.5; plugin ausente |
| SSH | Porta 22022 |

O hostname do processador não significa que a VPS tem os 128 núcleos físicos:
`lscpu` confirmou somente um processador virtual disponível.

## Retirada autorizada

O usuário confirmou descarte dos dados do FocusLab, dispensando backup dessa
aplicação antiga. Isso é diferente da política de backups semanais da plataforma
nova. O workflow `deploy.yml`, ID 246306575, de `Vitormwxm/focuslab-principal`
foi desativado via API e verificado como `disabled_manually`; não havia deploy
da main em andamento. Nenhum push foi feito no repositório antigo.

Aplicação ativa identificada pelo projeto Compose `focuslab-principal`:

- `focuslab-frontend`, imagem focuslab-frontend:8f9744c6b77d0ced8b19e645b4433cefc238f35f.
- `focuslab-backend`, imagem focuslab-backend:8f9744c6b77d0ced8b19e645b4433cefc238f35f.
- `focuslab-postgres`, imagem postgres:15.
- Volume `focuslab-principal_postgres_data`, rede `focuslab-principal_default`.
- Diretório `/var/www/apps/focuslab-principal` (452 MB).

Legado: diretório `/var/www/apps/focuslab`, rede `focuslab_default` e volumes
`focuslab_caddy_config`, `focuslab_caddy_data`, `focuslab_flashcards_data`,
`focuslab_postgres_data`. Validar labels e consumidores antes de removê-los.

Preservar recursos sem vínculo confirmado: containers `sweet_golick` e
`hardcore_mcnulty` (imagem f98ac8855de9), Python PID655 na porta 8443,
SSH, Docker, Certbot, `/etc/letsencrypt` e `/var/www/certbot`.
Certificado focuslabapp.com válido até 26/12/2026; a configuração TLS global
não deve ser removida junto do aplicativo.

`infra/scripts/retire-focuslab.sh` gera plano por padrão. Aplicação exige flags
explícitas de descarte/deploy suspenso e revalida recursos antes de remover.
Não utiliza limpeza global de volumes, sistema ou imagens compartilhadas.
O plano precisa ser executado na VPS; não houve conexão SSH a partir deste PC.

O operador executou a retirada da revisão 2f78c17556d22270bb93c155a5930f92218cc0db:
três containers, cinco volumes, duas redes e os dois diretórios foram retirados;
duas imagens da release ativa também. Permaneceram apenas os dois containers
desconhecidos parados e imagens/caches sem prova de propriedade. Disco livre
passou de 17 para 18 GB; RAM disponível de 1,1 para 1,4 GiB (dados arredondados
do terminal). TLS/SSH/Python preservados. Não há container FocusLab em execução.

Após a retirada principal, `infra/scripts/retire-focuslab-images.sh` planeja e
remove somente imagens locais comprovadamente FocusLab com SHA de release,
sem consumidores e sem tags compartilhadas. O padrão também é somente plano.
Imagens desconhecidas/base, caches globais e a imagem dos dois containers
desconhecidos são preservados. Scripts usam Python 3.10+ sem dependências pip.
O inventário posterior identificou duas referências legadas, adicionadas à
allowlist somente com ID imutável + tag exata: focuslab-principal-backend:latest
e focuslab_app:latest. Uma tag latest com outro ID permanece protegida.
O operador confirmou depois a remoção dessas duas imagens legadas. O disco
continuou com 18 GB livres; as demais 135 imagens ficaram fora da allowlist.

## Nova plataforma

Endereço confirmado pelo operador: **129.121.49.54**, SSH **22022**.
Usaremos provisoriamente **focuslabapp.com**; em 06/11/2026 tanto o domínio
principal como `www.focuslabapp.com` resolveram para esse IP. O certificado
preservado cobre os dois nomes. A hospedagem anterior na Vercel continua
independente: o código está no GitHub e a pipeline entrega imagens à VPS.

O diretório da nova aplicação será `/var/www/apps/minha-obra`, com usuário
dedicado `minhaobra-deploy`. A preparação não clona o repositório nem compila
na VPS. O arquivo privado fica em `/var/www/apps/minha-obra/.env`, modo 600;
senhas do PostgreSQL e identidade persistente do Kafka são geradas no servidor.
Não sobrescrever esse arquivo em novas execuções nem trocar o cluster ID depois
da criação do volume Kafka.

`infra/scripts/bootstrap-vps.sh --public-key-file <arquivo-publico-ed25519>`
prepara esses diretórios, usuário, grupo Docker e chave autorizada com `restrict`.
Deve ser executado como root na VPS após conferir a revisão e o checksum.
Não instala pacotes, modifica SSH/firewall, inicia containers ou exibe segredos.
Uma segunda execução preserva o `.env` e não duplica a chave. A saída mostra a
chave pública do host e a fingerprint para confirmação pela sessão existente.
As políticas efetivas do SSH são apenas diagnosticadas; o acesso só estará
confirmado após uma conexão nova com a chave dedicada. O grupo Docker permite
administrar o daemon, por isso essa chave deve ficar exclusiva da publicação.

O operador executou o bootstrap; host key e acesso SSH foram confirmados.
A PR #1 foi integrada e a release `4b32ca593d89831ccf4fe1184d5ae4cb921907f3`
foi publicada pelo workflow manual. Nginx, backend, PostgreSQL e Kafka ficaram
saudáveis, sem reinícios/OOM na observação inicial. Somente 80/443 estão
publicadas pela nova aplicação; a role de banco `minhaobra` não é superusuária
nem pode criar bancos ou roles.

Observação inicial, sem carga: cerca de 1,1 GiB RAM disponíveis; containers
consumiram aproximadamente 5 MiB (Nginx), 218 MiB (API), 40 MiB (PostgreSQL)
e 206 MiB (Kafka). Restaram 17 GB em disco após o pull das imagens. Esses
números variam e não substituem teste de capacidade.

O diagnóstico de renovação encontrou o webroot antigo removido:
`/var/www/apps/focuslab-principal/frontend/dist/frontend/browser`.
O destino correto para o Nginx novo é `/var/www/certbot`; a correção exige
renovação real pelo Certbot 1.21 e um deploy hook root que recarregue somente
o frontend do projeto `minha-obra`. O timer existente está ativo/habilitado.
O operador criou o hook root `/etc/letsencrypt/renewal-hooks/deploy/50-minha-obra-nginx`,
modo 755. Seu conteúdo foi conferido por SHA256 e a validação/recarga do Nginx
foi testada no container real. O hook seleciona somente o frontend desse projeto.

Para o Certbot 1.21, testar o novo webroot e persistir por uma renovação real,
como root. A última simulação omite o override para conferir a opção salva:

```bash
certbot renew --cert-name focuslabapp.com --webroot-path /var/www/certbot --dry-run --non-interactive --no-random-sleep-on-renew
certbot renew --cert-name focuslabapp.com --webroot-path /var/www/certbot --force-renewal --non-interactive --no-random-sleep-on-renew
certbot renew --cert-name focuslabapp.com --dry-run --non-interactive --no-random-sleep-on-renew
```

A renovação forçada é para gravar a alteração inicial; não usá-la como rotina
de diagnóstico. Em execuções manuais sem TTY, a opção final evita a espera
aleatória de até oito minutos por chamada. O timer de renovação normal pode
manter seu atraso padrão. `--dry-run` não executa o deploy hook nessa versão;
conferir também o certificado realmente servido por HTTPS após a renovação real.

Referências: [alteração de configuração em versões antigas](https://eff-certbot.readthedocs.io/en/stable/using.html#modifying-the-renewal-configuration-of-existing-certificates)
e [espera no Certbot 1.21](https://github.com/certbot/certbot/blob/v1.21.0/certbot/certbot/_internal/renewal.py#L391-L436).

Orçamento inicial dos containers: backend 640 MiB, Kafka 640 MiB,
PostgreSQL 256 MiB e Nginx 64 MiB. São tetos de teste, não consumo observado.
Heap das JVMs limitado inicialmente a 256 MiB por processo. Há pouca margem
para sistema operacional/page cache quando todos se aproximarem do teto.
Swap não substitui RAM para manter latência estável. Publicação depende de
medições de carga, memória, OOM/restarts, swap, disco e lag Kafka.

Banco e broker ficam em rede interna sem portas publicadas. A API usa uma
JVM para módulos e consumidor; frontend estático servido no Nginx. Nenhum
build de aplicação ocorre na VPS. Imagens, logs Kafka e logs Docker têm limites;
espaço realmente liberado será medido após a retirada, sem estimativa garantida.

## Envio de avisos Android

Habilite a API Firebase Cloud Messaging v1 no projeto Firebase. Use uma conta
de serviço dedicada com o papel `roles/firebasecloudmessaging.admin` no mesmo
projeto. A credencial do servidor é separada da configuração do APK.

Guarde a credencial JSON na VPS em um diretório privado com modo `0700`, por
exemplo `/var/www/apps/minha-obra/push-secrets`. O backend precisa ler o arquivo
como UID `10001`. A montagem do arquivo no container é somente leitura.
Configure `APP_PUSH_ENABLED`, `APP_PUSH_PROJECT_ID` e
`APP_PUSH_CREDENTIALS_HOST_FILE` no `.env` privado e aplique a release pelo
fluxo de deploy. A autenticação da API usa a credencial montada e o escopo
`firebase.messaging`.

[Autorização da API HTTP v1](https://firebase.google.com/docs/cloud-messaging/send/v1-api)
e [papéis do Cloud Messaging](https://docs.cloud.google.com/iam/docs/roles-permissions/firebasecloudmessaging).

## Backups da plataforma nova

### Cópia manual na VPS

Execute semanalmente como usuário de deploy, fora da janela de publicação.
O arquivo em formato customizado inclui o esquema `minhaobra` e seus dados. As tabelas de sessão entram apenas
com a estrutura, para que uma recuperação exija novo login.

```bash
(
  set -euo pipefail
  umask 077
  unset DOCKER_CONTEXT
  export DOCKER_HOST=unix:///var/run/docker.sock
  app=/var/www/apps/minha-obra
  read -r release_sha < "$app/.current-release"
  [[ $release_sha =~ ^[0-9a-f]{40}$ ]]
  release="$app/releases/$release_sha"
  if docker compose version >/dev/null 2>&1; then
    compose=(docker compose)
  elif docker-compose version >/dev/null 2>&1; then
    compose=(docker-compose)
  else
    echo 'Docker Compose indisponível.' >&2
    exit 1
  fi
  args=(-p minha-obra --env-file "$app/.env" --env-file "$release/release.env" -f "$release/compose.prod.yml")
  "${compose[@]}" "${args[@]}" config --quiet
  install -d -m 700 "$HOME/minha-obra-backups"
  backup=$(mktemp -d "$HOME/minha-obra-backups/$(date -u +%Y%m%dT%H%M%SZ)-XXXXXX")
  "${compose[@]}" "${args[@]}" exec -T postgres pg_dump --username=postgres \
    --dbname=minhaobra --no-password --format=custom --compress=1 --schema=minhaobra \
    --exclude-table-data='minhaobra.spring_session*' \
    --exclude-table-data='minhaobra.notification_device' \
    --exclude-table-data='minhaobra.notification_delivery' > "$backup/postgres.dump.part"
  test -s "$backup/postgres.dump.part"
  "${compose[@]}" "${args[@]}" exec -T postgres pg_restore --list < "$backup/postgres.dump.part" >/dev/null
  mv "$backup/postgres.dump.part" "$backup/postgres.dump"
  install -m 600 "$app/.env" "$backup/app.env"
  install -m 600 "$release/release.json" "$backup/release.json"
  (cd "$backup" && sha256sum postgres.dump app.env release.json > SHA256SUMS)
  printf 'Backup conferido em %s\n' "$backup"
)
```

Os vínculos de aparelhos e a fila de avisos entram apenas com a estrutura. Os
aparelhos voltam a se vincular quando abrem o aplicativo após a recuperação.

Guarde o diretório concluído em armazenamento privado e criptografado fora
da VPS. Ele contém dados financeiros e credenciais, inclusive a chave que
protege o TOTP. O checksum confere a integridade da cópia. O ponto de
recuperação é a data do último backup válido. A retenção dessas cópias é
definida pelo operador. Kafka e certificados são administrados separadamente.

### Ensaio de restauração fora da VPS

Em uma estação Linux separada, com Docker local, execute dentro da cópia
verificada. O teste usa um PostgreSQL 18 descartável, sem rede, volumes
compartilhados, portas publicadas ou credenciais de produção.

```bash
(
  set -euo pipefail
  sha256sum --check --status SHA256SUMS
  docker_local=(docker --host unix:///var/run/docker.sock)
  image=postgres:18.6-alpine@sha256:300ebd02c6d4de9ee27ad7f6855d5d21b4d3a4a5117767c1197d284c635d9736
  container=$("${docker_local[@]}" run --detach --rm --network none --memory=256m \
    -e POSTGRES_HOST_AUTH_METHOD=trust -e POSTGRES_DB=minhaobra_restore "$image")
  [[ $container =~ ^[0-9a-f]{64}$ ]]
  trap '"${docker_local[@]}" stop "$container" >/dev/null' EXIT
  for attempt in {1..60}; do
    if "${docker_local[@]}" exec "$container" pg_isready -h 127.0.0.1 -U postgres -d minhaobra_restore >/dev/null; then
      break
    fi
    sleep 1
  done
  "${docker_local[@]}" exec "$container" pg_isready -h 127.0.0.1 -U postgres -d minhaobra_restore >/dev/null
  "${docker_local[@]}" exec -i "$container" pg_restore --username=postgres --dbname=minhaobra_restore \
    --exit-on-error --single-transaction --no-owner --no-privileges < postgres.dump
  "${docker_local[@]}" exec "$container" psql -U postgres -d minhaobra_restore -v ON_ERROR_STOP=1 \
    -c 'SELECT count(*) AS contas FROM minhaobra.app_account' \
    -c 'SELECT count(*) AS pagamentos FROM minhaobra.finance_payment' \
    -c 'SELECT count(*) AS sessoes FROM minhaobra.spring_session'
)
```

Confira as contagens e o número de sessões igual a zero. Foi validada uma
restauração com PostgreSQL 18.6, dados fictícios e migrações V1 a V7.
A recuperação real deve usar uma instância preparada para o papel
`minhaobra` e o arquivo privado preservado, após conferir o backup isolado.
[Documentação do pg_dump](https://www.postgresql.org/docs/18/app-pgdump.html)
e [documentação do pg_restore](https://www.postgresql.org/docs/18/app-pgrestore.html).
