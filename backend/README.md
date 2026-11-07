# Backend — foundation

Java 25, Spring Boot 4.1.2, Maven Wrapper e PostgreSQL 18. Esta base publica somente os endpoints de saúde do Actuator. Não contém login, cadastros, cálculos ou envio de notificações.

Os pacotes estabelecem as camadas `controller → service → repository`: controllers validam contratos HTTP/DTOs e chamam services; services concentram regras e transações; repositories cuidam da persistência. `dto` e `domain` permanecem separados. Os `package-info.java` documentam essas fronteiras sem criar classes de negócio fictícias.

## Verificação obrigatória

O comando da CI é `./mvnw -B -ntp verify` (`.\mvnw.cmd -B -ntp verify` no Windows). Ele compila, empacota e executa quatro testes de integração contra PostgreSQL 18 real. Os testes exigem estas variáveis:

| Variável | Exemplo para um serviço efêmero da CI |
| --- | --- |
| `TEST_DATABASE_URL` | `jdbc:postgresql://localhost:5432/minhaobra_ci` |
| `TEST_DATABASE_USERNAME` | `minhaobra` |
| `TEST_DATABASE_PASSWORD` | Senha efêmera configurada no serviço PostgreSQL da CI |

A conta precisa poder criar schemas no banco de testes. Cada execução cria um schema aleatório `it_...`, aplica e valida o Flyway e remove somente esse schema ao final. Falta de configuração, indisponibilidade do PostgreSQL ou versão diferente de 18 falha a verificação; não existe substituição por H2 nem skip automático. Não use um banco de produção nos testes.

Os testes verificam a versão do PostgreSQL, a migração técnica, os probes HTTP reais e a ausência de detalhes de infraestrutura ou endpoints de configuração publicados. Kafka não é necessário para esses testes: esta base configura o cliente, mas ainda não tem tópico, listener ou fluxo de negócio.

`./mvnw -B -ntp package` verifica compilação e gera o JAR, mas **não executa a integração** e não substitui `verify` na CI.

## Executar

Em desenvolvimento, disponibilize uma instância dedicada de PostgreSQL 18 e execute `./mvnw spring-boot:run -Dspring-boot.run.profiles=dev`. O perfil `dev` espera banco `minhaobra`, usuário `minhaobra` e senha local `minhaobra_local`, em `localhost:5432`; as variáveis padrão `SPRING_DATASOURCE_*` permitem substituir esses valores. Esta base não inclui um Compose de desenvolvimento. No PowerShell, coloque `'-Dspring-boot.run.profiles=dev'` entre aspas.

Em produção, use `SPRING_PROFILES_ACTIVE=prod` e forneça `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD` e `SPRING_KAFKA_BOOTSTRAP_SERVERS` externamente. As credenciais não são incorporadas no JAR nem na imagem. O perfil de produção não fornece credenciais padrão. Flyway gerencia o schema `minhaobra`, com validação e limpeza desabilitada.

- `GET /actuator/health/liveness`: processo e estado da aplicação.
- `GET /actuator/health/readiness`: estado da aplicação e conectividade com o PostgreSQL.
- `GET /actuator/health`: saúde agregada sem componentes nem detalhes públicos.

A configuração Kafka mantém commits manuais do consumidor, isolamento `read_committed` e produtor idempotente com `acks=all`. Isso não implementa outbox nem garante entrega de eventos; esses fluxos terão testes próprios quando forem implementados. Tópicos não são criados automaticamente pela aplicação.

## Imagem

A CI gera `target/minha-obra-backend.jar` e só então executa `docker build -t <imagem>:sha-<commit> backend/`, a partir da raiz do monorepo. O Dockerfile copia esse JAR, disponibiliza `curl` para os probes do Compose e executa como usuário numérico sem privilégios. Nenhuma compilação ocorre na VPS. Publique e implante pelo digest da imagem; o Compose define limites e opções de memória da JVM. O contexto Docker permite somente o Dockerfile e o JAR esperado. Na VPS atual, os comandos operacionais usam `docker-compose` standalone v2.24.5, com projeto, arquivo e `--env-file` explícitos; o deploy deve validar as opções de espera disponíveis antes de usá-las.

Referências oficiais: [requisitos Spring Boot](https://docs.spring.io/spring-boot/system-requirements.html), [BOM de dependências](https://docs.spring.io/spring-boot/appendix/dependency-versions/coordinates.html), [Maven Wrapper](https://maven.apache.org/wrapper/), [PostgreSQL suportado](https://www.postgresql.org/support/versioning/).
