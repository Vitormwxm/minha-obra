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

## Backups da plataforma nova

O operador escolheu backups **manuais semanais**. Documentar geração de dump
consistente, conferência de retorno/arquivo e restauração em ambiente isolado.
Proteger os dumps e a configuração privada; não colocá-los no Git. Ponto de
recuperação corresponde ao último backup manual válido. Destino e retenção
dessas cópias serão definidos pelo operador, sem agendamento automático nesta base.
