# Contas e acesso — entrega atual

A pessoa solicita a conta com nome, usuário e uma senha escolhida por ela.
O acesso fica pendente até a aprovação de um administrador. Depois disso,
ela entra com a senha que escolheu. O fluxo desta etapa dispensa e-mail.

Quando precisar redefinir uma senha, o administrador entrega uma senha
provisória à pessoa. No próximo acesso, o app exige a troca dessa senha.

## Contrato compartilhado

API JSON em `/api`, erros `{code,message,fieldErrors?}` e Cache-Control no-store.
IDs UUID; datas ISO-8601 UTC; username normalizado, único, 3–40 caracteres
ASCII minúsculos, números, ponto, hífen ou sublinhado. Nome 2–120 caracteres.
Conta `{id,username,displayName,role,status,mustChangePassword,version,createdAt}`.
Role ADMIN/USER; status PENDING_APPROVAL/ACTIVE/BLOCKED. Nenhum hash, segredo MFA ou senha em DTO
de conta, logs, eventos, URL ou armazenamento do navegador.

Sessão `{user: conta,stage}`. Stage CHANGE_PASSWORD, MFA_SETUP, MFA_VERIFY ou
AUTHENTICATED. Não há acesso administrativo/de negócio antes da etapa final.

- GET `/auth/csrf` → `{token,headerName}`; XSRF-TOKEN cookie e X-XSRF-TOKEN header.
- POST `/auth/register` `{username,displayName,password}` → 201 `{status:"PENDING_APPROVAL"}`, sem sessão.
- POST `/auth/login` `{username,password}` → sessão. Com senha válida, conta pendente recebe 403 `ACCOUNT_PENDING_APPROVAL`.
- GET `/auth/me` → sessão; 401 sem sessão.
- POST `/auth/change-password` `{currentPassword,newPassword}` → sessão rotacionada.
- POST `/auth/logout` → 204.
- POST `/auth/mfa/setup` → `{secret,otpauthUri}` para admin sem MFA, após trocar senha.
- POST `/auth/mfa/confirm` `{code}` → `{session,recoveryCodes}`; códigos exibidos uma vez.
- POST `/auth/mfa/verify` `{code}` → sessão (TOTP ou recovery code).
- GET `/admin/users?page=0&size=20&search=...` → `{items,page,size,total}`.
- POST `/admin/users/{id}/approve` `{version}` → conta ativa.
- POST `/admin/users/{id}/block` `{version}` → conta.
- POST `/admin/users/{id}/unblock` `{version}` → conta.
- POST `/admin/users/{id}/temporary-password` `{version,temporaryPassword}` → conta.

Solicitação, aprovação, redefinição, bloqueio e desbloqueio usam `Idempotency-Key`
UUID e versão onde indicada. Repetir uma chave com outros dados retorna 409.
O registro de idempotência usa HMAC e guarda apenas metadados da resposta.
Solicitações criam apenas contas USER. Uma conta pendente só passa a ativa por
aprovação. Redefinir a senha de conta bloqueada preserva o bloqueio.

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

Interface Angular/Poppins responsiva com solicitação, login, troca obrigatória
após redefinição, configuração MFA, códigos de recuperação, área inicial,
aprovação no painel de contas e logout. Credenciais ficam fora do armazenamento
persistente do navegador.
APK mantém o bundle Angular e origem HTTPS do serviço para API de mesma origem,
sem CORS aberto, acesso a arquivos ou cookies de terceiros. Rotas API absolutas.

Testes reais de solicitação e aprovação, autorização/CSRF/revogação, bootstrap,
redefinição, replay MFA, recuperação, idempotência e isolamento contra PostgreSQL 18. Provar
o fluxo na interface antes de propor publicação. Não enviar senha administrativa
ou chave privada pelo chat ou Git.
