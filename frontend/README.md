# Frontend Minha Obra

Base Angular 22.2.5, standalone, com TypeScript estrito e um shell responsivo em português. O nome Minha Obra é provisório. Não há autenticação, cadastro, planilhas, integração com API, notificações ou persistência implementados nesta etapa.

## Desenvolvimento e validação

Requer Node.js 24.15 ou posterior da linha 24 e npm 11. A versão usada na validação local foi Node 24.22.2; `.node-version` registra essa versão sem alterar o Node global da máquina.

```bash
cd frontend
npm ci
npm run typecheck
npm run build
npm start
```

`typecheck` executa o compilador Angular com `--noEmit`, incluindo verificação estrita de templates. `build` gera a aplicação otimizada em `dist/minha-obra/browser`. Ainda não existe uma suíte de testes funcional do produto; esses comandos não devem ser apresentados como cobertura de testes.

## Publicação e Android

O mesmo artefato estático usa `baseHref: "./"`, servindo tanto no site quanto em `https://appassets.androidplatform.net/assets/index.html` dentro do APK. Não há roteador nesta base. Se o roteamento for introduzido, usar hash no Android para manter os caminhos dos assets locais e adaptar o comportamento de voltar.

As fontes Poppins Regular, Medium, SemiBold e Bold são locais e idênticas às do site anterior. O Angular as inclui em `media/` com nomes de arquivo calculados pelo build; a licença OFL acompanha o artefato em `assets/fonts/OFL.txt`. O frontend não depende de CDN para fontes, scripts ou estilos. A otimização de CSS crítico inline está desabilitada para evitar o script inline de carregamento incompatível com a CSP do Android; a minificação de CSS e JavaScript continua habilitada.

O projeto Android exige este build antes de `lintDebug` e `assembleDebug`. Os assets gerados, `node_modules` e caches não são versionados.

## Limites desta entrega

A base compilável não substitui QA de navegação e acessibilidade em navegadores reais, nem QA do WebView em aparelho Android. Conferir layout em telas pequenas, fontes, barras do sistema e abertura offline antes de uma release funcional. O APK mantém bloqueio de rede externa; login e FCM exigirão uma etapa explícita de integração.
