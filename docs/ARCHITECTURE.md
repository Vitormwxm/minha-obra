# Arquitetura da plataforma

A área Minha obra reúne o financiamento CAIXA, o histórico mensal de pagamentos,
a TR por competência e uma projeção até a entrega prevista. O simulador
estático da raiz continua disponível como referência do modelo de projeção.

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

## TR e projeção

A planilha mostra uma TR por mês. O valor manual tem prioridade. Ao consultar
a taxa oficial, o usuário informa o início e o fim do período conforme o
contrato. O servidor só usa a taxa da série SGS 226 que coincide com as duas
datas. As consultas bem-sucedidas ficam em cache no PostgreSQL por período.
A consulta externa envia apenas a data inicial e é feita sob demanda.
[Histórico da série SGS 226](https://www3.bcb.gov.br/sgspub/consultarvalores/consultarValoresSeries.do?method=consultarSeries&series=226).

Na falta de taxa manual ou oficial, a estimativa usa o total quitado à CAIXA
menos seguro, taxas e encargos de atraso, com o saldo-base daquele lançamento
e os juros nominais anuais cadastrados no financiamento. O modelo estima a TR
invertendo a soma da correção com os juros sobre o saldo corrigido. Pagamentos
em atraso precisam ter o encargo informado, inclusive quando for zero. A
estimativa fica vazia se faltarem dados ou a diferença for incompatível com
o modelo. Os encargos efetivos seguem as cláusulas do contrato.
[Cartilha da CAIXA sobre a fase de obras](https://www.caixa.gov.br/Downloads/habitacao-documentos-gerais/Cartilha_Juros_Fase_de_Obras.pdf).

A projeção abrange os encargos da CAIXA do mês atual até o mês previsto da
entrega. Ela distribui a diferença entre o saldo atual liberado e o valor
financiado, com ajuste de centavos no último mês. O modelo considera a
liberação no fim do mês e a correção da TR paga no encargo.
A TR futura é uma hipótese editável. O usuário pode reaproveitar como
hipótese o valor registrado no histórico. Pagamentos registrados reduzem
o saldo a pagar projetado, e meses quitados entram com saldo a pagar zero.
Campos em branco podem deixar a projeção parcial. Se o valor de um pagamento
destinado à evolução for incerto, o subtotal usa o maior abatimento possível.
O simulador público usa o mesmo motor com dados informados na tela de acesso.

A projeção da construtora recebe um cenário com uma parcela por mês, fixa ou
indexada. Os campos do INCC incluem versão, mês-base, periodicidade e defasagem.
O cálculo aplica uma hipótese mensal informada pelo usuário aos meses elegíveis
e considera pagamentos registrados. Parcelas indexadas com configuração
pendente exibem o valor base e deixam o total restante parcial. A FGV publica
resultados gerais e oferece a maior parte das séries detalhadas por contrato.
[INCC no FGV IBRE](https://portalibre.fgv.br/estudos-e-pesquisas/indices-de-precos/incc).

A versão web gera o PDF no navegador a partir do cenário calculado, com
as hipóteses e os encargos por mês. O histórico de pagamentos pode ser
baixado em CSV, com um arquivo para o resumo mensal e outro para os
pagamentos individuais, na área de exclusão da conta.


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

Cada aparelho Android pode autorizar avisos. O vínculo autenticado registra o
fuso local e acompanha a versão de segurança da conta. Sair da conta desvincula
o aparelho. O backend mantém até dez vínculos por conta e desconsidera os que
ficam mais de 90 dias sem atualização.

Os pedidos de cadastro chegam pelo Kafka. Um agendador calcula os lembretes de
pagamento e os dois fluxos gravam avisos em uma fila persistente no PostgreSQL.
Com `APP_PUSH_ENABLED=true`, o backend envia a fila ao Firebase Cloud Messaging.
Os lembretes usam a hora local do aparelho e o vencimento específico do mês ou o
dia recorrente, ajustado ao último dia do mês. Quitação e situação sem pagamento
cancelam lembretes. Os avisos indicam o vencimento quando conhecido e o texto do
cadastro é genérico. A conexão e a permissão do Android afetam o recebimento.

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
