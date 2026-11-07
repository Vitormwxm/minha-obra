# CI e publicação

## CI obrigatória

`.github/workflows/ci.yml` roda em PRs para main, pushes em main e
acionamento manual. Não usa credenciais de produção nos testes.

1. Preserva a referência de 30 testes do simulador estático da raiz.
2. Compila Java 25 e executa `backend/mvnw verify` contra PostgreSQL 18 real,
   com credenciais efêmeras `TEST_DATABASE_*` e schema isolado por execução.
3. Instala Angular por lockfile, verifica tipos e gera o build de produção.
4. Compila e executa lint do APK debug, usando Java 17/SDK35 e o build Angular.
5. Verifica scripts em Python 3.10/3.12 no Linux sem aceitar testes pulados,
   protege arquivos privados e valida Compose. O Compose standalone 2.24.5
   é baixado e conferido por SHA256 para reproduzir a versão instalada na VPS.
   Um smoke test inicia PostgreSQL, Kafka, API e Nginx/HTTPS reais no runner.
6. O check `Required checks` exige sucesso de todos os jobs; módulo ausente,
   job pulado ou cancelado não conta como sucesso.

Em pushes da main com esses checks aprovados, a CI empacota os mesmos binários
testados em imagens GHCR de API e frontend, com tag sha-<commit> e digest.
`release.json` registra commit, execução CI e as duas referências imutáveis.
JAR/APK/dist de teste ficam sete dias; o manifesto pequeno fica trinta dias.
Isso não define retenção de backups de dados do aplicativo.

## Deploy manual

`.github/workflows/deploy-production.yml` roda somente por acionamento manual
na main e recebe SHA completo. Confere ancestralidade na main, CI bem-sucedida
do mesmo commit e manifesto da mesma execução. Não recompila na VPS.

Criar ambiente GitHub `production`, restrito à main, e configurar:

| Tipo | Nome | Finalidade |
| --- | --- | --- |
| Secret | VPS_HOST | IP/hostname da VPS |
| Secret | VPS_USER | Usuário de deploy com acesso ao projeto e Docker |
| Secret | VPS_SSH_KEY | Chave SSH dedicada, somente no secret |
| Secret | VPS_KNOWN_HOSTS | Host key conhecida do servidor, conferida pelo operador |
| Variable | VPS_PORT | 22022, conforme inventário atual |
| Variable | PRODUCTION_URL | Origem HTTPS do domínio definitivo |

Estado de produção em 06/11/2026: ambiente `production` restrito à main;
`VPS_PORT=22022`, `PRODUCTION_URL=https://focuslabapp.com` e secrets
`VPS_HOST`, `VPS_USER`, `VPS_SSH_KEY` e `VPS_KNOWN_HOSTS` configurados.
A chave é exclusiva da pipeline; sua parte privada não está no Git. A chave
pública do host foi conferida pelo operador e uma nova conexão SSH validou o
usuário, o acesso ao Docker e a configuração privada com modo 600.

A [CI da main](https://github.com/Vitormwxm/minha-obra/actions/runs/42329017505)
publicou as imagens e o [primeiro deploy](https://github.com/Vitormwxm/minha-obra/actions/runs/42329402075)
concluiu para `4b32ca593d89831ccf4fe1184d5ae4cb921907f3`.
O pull das duas imagens foi conferido na VPS sem credenciais de registry.
Os quatro serviços ficaram saudáveis; HTTPS respondeu 200 e `/healthz` retornou
`UP`. Esse estado confirma a publicação da base, sem implementar as futuras
funcionalidades de contas/finanças ou comprovar capacidade sob carga.

Não usar SSH sem verificação de host nem colar chaves no chat/Git. Os secrets do
repositório FocusLab não são automaticamente compartilhados com este repositório.

As imagens GHCR devem estar públicas para pull anônimo pela VPS. Essa é uma
configuração do pacote após a primeira publicação; não enviar tokens de registry
no manifesto ou em argumentos SSH. Se optar por pacotes privados no futuro,
configurar credencial de leitura protegida no servidor antes de publicar.

Preparação da VPS, antes do primeiro deploy:

- Nome/domínio definitivo, DNS e certificado válido para esse domínio.
- Diretório real `/var/www/apps/minha-obra` pertencente ao usuário de deploy.
- Arquivo `.env` privado com modo 600, baseado em `infra/.env.example`, com
  senhas aleatórias distintas de aplicação/admin e cluster ID Kafka gerados para a nova instalação.
- Docker existente e Compose standalone 2.24.5. Script detecta ambas as formas,
  verifica `up --wait/--wait-timeout` e exige daemon local e espaço disponível.
- Certificados somente leitura em `/etc/letsencrypt` e webroot `/var/www/certbot`.

O deploy copia apenas manifesto, Compose e scripts para releases/<SHA>, faz
pull/up --no-build e espera healthchecks. Uma única execução por vez é permitida.
Depois verifica `/healthz` pelo HTTPS público. Falha não remove volumes e não
faz restauração automática de banco. Rollback de aplicação depende da
compatibilidade das migrações; restauração de banco é operação separada.

## APK e estado desta entrega

CI produz APK **debug para validação**, sem assinatura privada. Distribuição de
produção no site exigirá a mesma chave já usada pelo APK 1.0.1 e incremento de
versão. A chave, senhas e configurações locais não foram importadas para o Git.
Login, projeção Java, planilha e FCM ainda não estão implementados na foundation.
Não publicar esse shell como se fosse o aplicativo financeiro concluído.

Referências: [GHCR](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-container-registry),
[ambientes](https://docs.github.com/en/actions/deployment/targeting-different-environments/managing-environments-for-deployment).
