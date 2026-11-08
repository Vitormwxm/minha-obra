# Arquitetura da plataforma

A área Minha obra reúne o cadastro de um financiamento CAIXA e o histórico
mensal de pagamentos por conta aprovada. O simulador estático da raiz continua
como referência para as próximas etapas de cálculo e projeção.

## Decisões

- Nome provisório Minha Obra, independente do FocusLab.
- Monólito modular Java/Spring Boot: contas, administração, financiamento,
  lançamentos, cálculo, índices, exportação e notificações separados por módulo.
- Organização obrigatória com controllers, services e repositories. O fluxo é
  Controller → Service → Repository: HTTP e DTOs na borda, regras e transações
  no service, persistência no repository. Entidades e contratos de API separados;
  controllers não acessam repositories diretamente. O motor financeiro não depende
  de HTTP ou banco, permitindo testar arredondamentos e regras isoladamente.
- Um frontend Angular responsivo com Poppins, compartilhado com a WebView Java
  do Android. Pastas `backend/`, `frontend/`, `android/`, `infra/` e `docs/`.
- PostgreSQL como fonte de verdade; valores monetários em centavos/decimal,
  jamais ponto flutuante para persistir dinheiro. Competência, vencimento e data
  de pagamento são conceitos separados.
- A aplicação usa role PostgreSQL sem superuser, sem criação de bancos ou roles.
  Credencial administrativa é separada e não fornecida à API.
- Kafka desde a primeira versão. Negócio e outbox na mesma transação; publicação
  assíncrona, consumidor idempotente e entregas persistidas antes do envio FCM.
  Repetições são esperadas; não prometer entrega push exatamente uma vez.
- Idempotency keys para repetições da mesma operação e versões para impedir
  sobrescrita por edições concorrentes. São proteções complementares.
- Cálculo financeiro centralizado em Java. A simulação anônima é permitida no
  site e usa internet. APK exige conta aprovada. Offline autenticado
  é evolução futura, não funcionalidade da base atual.
- Um financiamento CAIXA de apartamento por usuário inicialmente, com vínculo
  conta → financiamentos que permite expansão sem misturar proprietários.

## Acesso e operação

Administrador inicial único, segundo fator TOTP e códigos de recuperação.
Cada pessoa solicita a conta com a própria senha e aguarda a aprovação.
Uma senha provisória é usada na redefinição administrativa e precisa ser
trocada no próximo acesso.
Perfil administrativo gerencia contas, aprovações, bloqueios e exclusões; não
consulta financeiro de outros usuários. O operador da VPS tem acesso técnico
ao banco. Senhas serão hashes de verificação, sem senha original armazenada.

Solicitações de exclusão suspendem lançamentos e lembretes; mantêm status,
exportação e cancelamento antes da execução. Análise administrativa com meta
operacional de resposta em sete dias. Bloquear conta preserva o histórico.
Backups da plataforma: **manuais semanais**, por decisão do usuário. Não há
agendamento automático ou retenção de 30 dias aprovada.

## Controle de pagamentos

Em **Minha obra**, cada pessoa informa o valor financiado, o saldo liberado,
os juros nominais anuais, a entrega prevista, o início do acompanhamento e,
se houver, o subsídio. Os lançamentos mensais aceitam vencimentos separados
para CAIXA e construtora, saldo liberado, seguro, taxas e pagamentos por data.
Um pagamento pode incluir encargos de atraso já contabilizados no valor pago.

A planilha mostra o valor pago a cada parte, o total do mês quando ambos os
valores são conhecidos, os acumulados e a variação entre meses consecutivos
concluídos. É possível registrar explicitamente que uma parte ficou sem cobrança,
ou deixar a situação em aberto. O aviso de lacunas vai do início informado
até o último mês cadastrado. A API associa o financiamento à conta da sessão.

## Regras financeiras aprovadas

Uma linha por mês de vencimento, com múltiplos pagamentos CAIXA/construtora,
totais, acumulados do período acompanhado e variação entre meses concluídos.
Valor efetivamente pago prevalece. Juros/multa são detalhes do total pago,
sem soma duplicada. Subsídio é benefício do financiamento separado do desembolso.
Lacunas no meio do período geram alerta; desconhecido não vira zero. O início
do acompanhamento é escolhido pelo usuário, sem histórico anterior obrigatório.

TR única com origem Oficial/Estimada/Manual e identificação por cor e texto.
Escolha manual prevalece. Estimativa exige saldo-base mensal, juros nominais,
seguro, taxas e segregação de encargos de atraso; sem dados não inventar TR.
Índice oficial segue o período contratual, sem usar indiscriminadamente TR do
primeiro dia do mês para todo contrato. INCC parametrizado por versão, base,
mês-base, periodicidade e defasagem; cada cobrança pode ser indexada ou fixa.
Configuração pendente mantém base fixa identificada. Futuro é hipótese editável.
Fontes de índices devem ser verificadas; não prometer republicação automática
de dados de terceiros sem verificar as condições da fonte.

Liberações futuras igualmente distribuídas entre saldo liberado mais recente e
financiamento final até a entrega prevista. Alterar entrega recalcula previsões,
preservando pagamentos reais; data prevista não confirma entrega. Despesas
futuras desconhecidas tornam o total projetado parcial. Exportação CSV mensal
e detalhada, PDF da projeção; sem importação de arquivos na primeira versão.

## Notificações e Android

FCM entrega os avisos processados via Kafka. Padrão configurável: 3/1/0 dias
antes, 9h local do celular; um dia após o vencimento e repetição semanal opcional
desativada inicialmente. Quitação manual cancela avisos futuros; parcial mantém.
Dia inexistente na recorrência usa o último dia do mês; data específica prevalece.
Valores visíveis por padrão. Todos os Android conectados e autorizados recebem;
sair da conta remove o vínculo. Permissão, conectividade e condições do Android
podem atrasar entrega: horário programado não é garantia de recebimento exato.

APK distribuído pelo site, com aviso e download manual das atualizações.
Preservar `br.com.minhaobra.app` e a chave de assinatura existente; jamais
publicar keystore, senha de assinatura ou configuração privada no Git.

## Dimensionamento real

Inventário do usuário em 06/11/2026: Ubuntu 22.04.5, x86_64, **1 vCPU,
1,9 GiB RAM, 4 GiB swap, disco 50 GB com 17 GB livres**. Docker 29.1.3 e
`docker-compose` standalone 2.24.5, sem plugin `docker compose`.

Essa capacidade tem pouca folga para PostgreSQL + duas JVMs (API e broker).
Usar uma JVM para API e consumidor; Angular é estático no Nginx. Builds Maven,
Angular, Android e Docker acontecem no GitHub Actions. A VPS somente baixa e
inicia imagens já prontas. Limites do Compose são orçamento inicial para ensaio,
não capacidade de produção comprovada. Medir RSS, OOM/restarts, swap, latência,
lag Kafka, disco e resposta sob carga antes de liberar uso público.

Kafka KRaft de um nó combinado e uma única VPS não fornecem alta disponibilidade.
A própria Apache desaconselha modo combinado para ambientes críticos.
Referência: [KRaft](https://kafka.apache.org/44/operations/kraft/).

## Entregas

CI obrigatória valida legado, backend real com PostgreSQL, Angular, Android e
scripts. Imagens da aplicação são publicadas no GHCR por commit e identificadas
por digest. Deploy de produção é manual, somente de commit da `main` aprovado
pela CI, sem recompilar na VPS e sem remover volumes. A publicação usa o
domínio e o certificado da VPS. As credenciais FCM pertencem à etapa de
notificações.
