# Minha Obra — plataforma

Base de arquitetura e CI/CD da plataforma independente do FocusLab.
Java/Spring Boot, Angular, PostgreSQL, Kafka, FCM e Android Java/WebView.
Nome comercial provisório. As funcionalidades novas aprovadas ainda serão
implementadas; esta entrega prepara os projetos compiláveis e a operação.

- [Arquitetura e regras aprovadas](docs/ARCHITECTURE.md)
- [Pipelines e configuração de publicação](docs/PIPELINES.md)
- [Inventário da VPS e retirada do FocusLab](docs/VPS.md)
- [Backend](backend/README.md)
- [Frontend](frontend/README.md)
- [Android](android/README.md)

Diretórios: `backend/` (controllers, services, repositories e módulos),
`frontend/` (Angular/Poppins), `android/`, `infra/` e `docs/`.
A CI compila os projetos reais e executa integração contra PostgreSQL.
A VPS de 1 vCPU/2 GiB apenas recebe imagens prontas; usa
**docker-compose v2.24.5 standalone**, com detecção compatível nos scripts.
Deploy é manual a partir de imagens de commit da main aprovado pela CI.

## Referência do simulador estático anterior

Os arquivos estáticos da raiz continuam preservados como referência para
migrar e validar as regras financeiras. As instruções abaixo descrevem esse
legado, não a nova aplicação com servidor e contas.

Site feito em HTML, CSS e JavaScript, com a mesma fonte Poppins e o mesmo motor de cálculo do aplicativo Android. Os arquivos deste diretório são independentes do projeto Android e estão prontos para publicação na Vercel.

## Publicar na Vercel

1. Extraia o ZIP do site e coloque o conteúdo em um repositório Git próprio.
2. Na Vercel, escolha **Add New → Project** e importe esse repositório.
3. Use **Framework Preset: Other** e a raiz que contém `index.html` e `vercel.json`.
4. **Build Command** e **Install Command** ficam vazios. **Output Directory: `.`**.
5. Publique com **Deploy**.

O arquivo `vercel.json` já declara essas opções. O site não precisa de servidor próprio, banco de dados, variáveis de ambiente ou pacotes npm para funcionar. Também pode ser publicado usando a Vercel CLI a partir deste diretório.

Referências oficiais: [configuração estática](https://vercel.com/docs/project-configuration/vercel-json) e [deploy](https://vercel.com/docs/deployments).

## Funcionalidades

- Saldo atual liberado, saldo final do financiamento, juros nominais, TR projetada, seguro, taxas e período da projeção.
- Saldo final inicial de R$ 192.000,00, editável. A diferença para o saldo atual é distribuída pelos meses até a entrega, com ajuste final em centavos.
- Resumo, gráfico, detalhamento e ajustes mensais, como no APK.
- Exportação CSV para planilha, impressão/PDF, salvar e abrir cenários JSON.
- Poppins incluída nos arquivos, sem Google Fonts ou outro CDN durante o uso.
- Dados salvos neste navegador somente se a opção de lembrar dados for ativada.
- Uso offline depois da primeira visita completa, quando o cabeçalho indicar **Pronto offline**. A primeira abertura precisa de internet para carregar e guardar os arquivos.

As versões Android e web usam arquivos JSON compatíveis. Para transferir um cenário, salve o arquivo em uma versão e abra na outra. Não há sincronização automática de dados entre dispositivos ou entre o APK e o site.

## Arquivos

`index.html` contém as telas; `styles.css`, o visual responsivo e as fontes; `app.js`, os controles, arquivos e gráficos; `calculator.js`, o cálculo; `web.js` e `sw.js`, a inicialização web e o cache offline; `assets/fonts/`, a Poppins e sua licença.

O motor mantém as premissas do APK: TR paga no encargo, juros nominais anuais divididos por 12, seguros e taxas informados pelo usuário, sem amortização durante a obra nem cálculo proporcional por dia. A TR futura é uma hipótese, e os pagamentos são estimativas.

## Testar e manter

Para abrir localmente com cache offline, sirva este diretório por HTTP em localhost, por exemplo `python -m http.server 8080 --bind 127.0.0.1`, e abra `http://127.0.0.1:8080`. Abrir `index.html` diretamente também permite calcular, mas não ativa o service worker.

Com Node.js, `npm test` verifica os cálculos e `npm run check` verifica a sintaxe. Os testes opcionais de interface usam JSDOM fornecido no ambiente de desenvolvimento. Não é necessário instalar dependências para publicar.

Ao alterar arquivos para uma nova publicação, atualize `VERSION` em `sw.js`. O servidor tem preferência para carregar arquivos atualizados; os arquivos em cache são usados na ausência de conexão. Reabra a página para carregar uma nova versão.
