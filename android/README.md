# Android Minha Obra

Host Java com WebView para o build Angular local. Mantém `br.com.minhaobra.app`, `versionName 1.0.1` e `versionCode 2` como base técnica. Uma próxima release funcional deve avançar a versão e o código.

## Build de desenvolvimento

Ferramentas: JDK 17, Android SDK Platform 35, AGP 8.7.3 e wrapper Gradle 8.9. Configure `ANDROID_HOME` com o SDK instalado (ou crie `local.properties` apenas na sua máquina).

```bash
cd frontend
npm ci
npm run typecheck
npm run build
cd ../android
./gradlew --no-daemon lintDebug assembleDebug
```

No PowerShell, use `.\gradlew.bat --no-daemon lintDebug assembleDebug`. O wrapper contém o checksum SHA256 da distribuição Gradle. `verifyWebAssets` falha claramente se `../frontend/dist/minha-obra/browser/index.html` não existir; `syncWebAssets` sincroniza esse diretório antes de `preBuild`. A saída debug é `app/build/outputs/apk/debug/app-debug.apk`, assinada automaticamente com a chave debug local padrão do Android, sem a chave de produção.

## Conteúdo e origem

A aplicação carrega somente `https://appassets.androidplatform.net/assets/index.html` e os arquivos do APK, com MIME adequado para JavaScript, CSS, fontes e imagens. O frontend usa base relativa; roteamento futuro deve preservar essa origem, preferindo hash no APK.

O manifest não solicita INTERNET. O host bloqueia requisições, navegação e conteúdo externo; acesso a arquivos/content URI e conteúdo misto ficam desabilitados. Não há bridges JavaScript nativas, login ou FCM nesta etapa. A política de conteúdo admite scripts locais, estilos locais/inline e a base local exigida pelo Angular; conexões externas continuam bloqueadas.

## Origem da estrutura e limites

Foram importados apenas wrapper, configuração pública, manifest, recursos e fonte Java da estrutura anterior. Não foram importados keystores, propriedades de assinatura, SDKs, caches, builds ou propriedades locais. O fonte Java foi reduzido ao host local; funções antigas de importação/exportação e impressão não fazem parte desta base.

A pipeline executa lint e montagem debug, sem configurar assinatura de produção. Nenhuma chave de release é necessária ou está armazenada aqui. Esta etapa não publica APK na loja nem altera a instalação existente.

Antes de uma release, fazer QA em aparelho/emulador com WebView atualizado: abertura offline, renderização das fontes, telas pequenas, barras do sistema, rotação, voltar e restauração após pausa. Compilar e passar no lint não comprova esse comportamento em dispositivo. Ainda não há testes instrumentados; não tratar tarefas sem testes como cobertura.
