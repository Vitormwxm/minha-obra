# Android Minha Obra

Host Java com WebView para o build Angular. A atualização 1.1.0 usa
`br.com.minhaobra.app` e `versionCode 3`.

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

## Build assinado

Gere primeiro o build Angular. Na pasta Android, execute
`./gradlew --no-daemon assembleRelease -PminhaObraSigningFile=/caminho/privado/signing.properties`.
No PowerShell, use `.\gradlew.bat`. O arquivo privado deve conter
`storeFile`, `storePassword`, `keyAlias` e `keyPassword`. Um caminho
relativo em `storeFile` parte da pasta do arquivo de propriedades.
Guarde a chave original e as propriedades em armazenamento privado fora do Git.

Antes de distribuir, use `apksigner verify --print-certs` no APK novo e no
APK anterior. Confirme que o SHA-256 do certificado de assinatura coincide.
A saída assinada é `app/build/outputs/apk/release/app-release.apk`.
A pipeline continua gerando o APK de depuração para validar cada alteração.

## Conteúdo e origem

O APK carrega os arquivos Angular empacotados pelo endereço HTTPS
`https://focuslabapp.com/assets/index.html`. O manifest concede acesso à rede
e a WebView permite chamadas apenas para a API na mesma origem. A API usa o
login e os cookies de sessão do serviço. Arquivos locais, cookies de terceiros
e conteúdo misto permanecem bloqueados.

Valide a atualização sobre a versão anterior em um aparelho com WebView
atualizado. Confira login, telas pequenas, navegação, rotação, retomada e
telas que dependem da API. O lint e a verificação criptográfica do APK
validam o build e a assinatura. [Assinatura no Android](https://developer.android.com/studio/publish/app-signing)
