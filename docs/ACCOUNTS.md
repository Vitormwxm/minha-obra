# Contas e acesso — entrega atual

Decisão do usuário em 06/11/2026: contas criadas pelo administrador, sem
cadastro público, e-mail ou confirmação por e-mail nesta etapa. O próprio
operador entrega a senha provisória fora do app. Troca obrigatória no primeiro
acesso. Essa decisão substitui o fluxo anterior de auto cadastro/aprovação.

## Contrato compartilhado

API JSON em `/api`, erros `{code,message,fieldErrors?}` e Cache-Control no-store.
IDs UUID; datas ISO-8601 UTC; username normalizado, único, 3–40 caracteres
ASCII minúsculos, números, ponto, hífen ou sublinhado. Nome 2–120 caracteres.
Conta `{id,username,displayName,role,status,mustChangePassword,version,createdAt}`.
Role ADMIN/USER; status ACTIVE/BLOCKED. Nenhum hash, segredo MFA ou senha em DTO
de conta, logs, eventos, URL ou armazenamento do navegador.

Sessão `{user: conta,stage}`. Stage CHANGE_PASSWORD, MFA_SETUP, MFA_VERIFY ou
AUTHENTICATED. Não há acesso administrativo/de negócio antes da etapa final.

- GET `/auth/csrf` → `{token,headerName}`; XSRF-TOKEN cookie e X-XSRF-TOKEN header.
- POST `/auth/login` `{username,password}` → sessão. Erro genérico de credenciais.
- GET `/auth/me` → sessão; 401 sem sessão.
- POST `/auth/change-password` `{currentPassword,newPassword}` → sessão rotacionada.
- POST `/auth/logout` → 204.
- POST `/auth/mfa/setup` → `{secret,otpauthUri}` para admin sem MFA, após trocar senha.
- POST `/auth/mfa/confirm` `{code}` → `{session,recoveryCodes}`; códigos exibidos uma vez.
- POST `/auth/mfa/verify` `{code}` → sessão (TOTP ou recovery code).
- GET `/admin/users?page=0&size=20&search=...` → `{items,page,size,total}`.
- POST `/admin/users` `{username,displayName,temporaryPassword}` → conta, 201.
- POST `/admin/users/{id}/block` `{version}` → conta.
- POST `/admin/users/{id}/unblock` `{version}` → conta.
- POST `/admin/users/{id}/temporary-password` `{version,temporaryPassword}` → conta.

Criação/reset/bloqueio/desbloqueio usam Idempotency-Key UUID por operação e
versão para concorrência onde indicada. Replay com outro payload → 409.
Não guardar senha provisória no registro de idempotência: fingerprint com HMAC
da chave privada do app; resultado contém apenas metadados. Reset não desbloqueia.
O painel só cria USER e não bloqueia/redefine o próprio administrador.

## Segurança e operação

Controller → Service → Repository. JDBC/PostgreSQL e migrations Flyway;
Spring Session JDBC, sem schema automático ou H2. Cookie de sessão HttpOnly,
Secure em produção, SameSite=Lax. CSRF também no login e operações administrativas.
Rotacionar sessão e renovar CSRF depois de autenticar/trocar senha/logout.
Conferir status/auth_version no banco em cada request; reset/bloqueio/troca
revogam sessões, inclusive diante de requests concorrentes.

BCrypt strength12; senha de 12–64 caracteres, máximo 72 bytes UTF-8, sem trim
ou normalização silenciosa. Throttling antes do hash por IP e username/MFA,
memória limitada, respostas sem enumeração. Nenhum password/token em logs.

Admin exige TOTP antes de obter permissão ADMIN. Segredo AES-256-GCM com nonce
aleatório, chave persistente `APP_TOTP_ENCRYPTION_KEY` base64 de 32 bytes na
configuração privada. TOTP de 6 dígitos/30s, janela curta e contador consumido
atomicamente. Recovery codes aleatórios, hashes e consumo único.

Bootstrap do primeiro admin somente pelo operador, sem endpoint público nem
senha original persistida. Preferir CLI da imagem: `--bootstrap-admin <username>`,
senha lida sem eco por Console, transação com lock, somente quando não há admin.
Não promover/resetar usuário existente ao iniciar o servidor. Documentar comando
exato; se CLI isolada exigir ajuste, alinhar antes de mudar esse contrato.

Ações de conta gravam outbox/auditoria na mesma transação, sem segredos. Kafka
recebe eventos técnicos de conta; notificações FCM e lembretes são entrega futura.
Não colocar senha/segredo/token de recuperação no payload do evento.

Interface Angular/Poppins responsiva: login, troca obrigatória, MFA/setup e
recovery codes, área inicial autenticada, painel de contas e logout. Não inventar
totais financeiros. Não persistir credenciais em localStorage/sessionStorage.
APK mantém o bundle Angular e origem HTTPS do serviço para API de mesma origem,
sem CORS aberto, acesso a arquivos ou cookies de terceiros. Rotas API absolutas.

Testes reais de autorização/CSRF/revogação, bootstrap, senha provisória, replay MFA
e recuperação, idempotência e isolamento; executar contra PostgreSQL 18. Provar
o fluxo na interface antes de propor publicação. Não enviar senha administrativa
ou chave privada pelo chat ou Git.
