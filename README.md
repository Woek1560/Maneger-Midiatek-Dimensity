# MT6899 Manager (v0.3.0)

Gerenciador de kernel com root para uso pessoal em aparelhos MediaTek da família MT6899. Projeto Kotlin + Jetpack Compose. O objetivo é controlar parâmetros reais do kernel com segurança; não é um app genérico de otimização.

## Build pelo GitHub Actions

1. Envie o conteúdo do repositório para GitHub.
2. Abra **Actions → Build APK** e execute o workflow (ou faça push).
3. Baixe os artefatos `MT6899-Manager-apk` e `MT6899-Helper-module`.

### Assinatura do APK

Por segurança, nenhuma keystore privada é incluída no repositório. Sem secrets de assinatura, o workflow usa a chave debug do runner: serve para testar/instalar, mas uma nova execução pode gerar uma chave diferente e exigir desinstalar o APK anterior antes de atualizar.

Para manter atualizações instaláveis por cima da versão anterior, configure estes GitHub Actions Secrets:

- `RELEASE_KEYSTORE_BASE64`: keystore privada codificada em Base64
- `RELEASE_STORE_PASSWORD`: senha da keystore
- `RELEASE_KEY_ALIAS`: alias da chave
- `RELEASE_KEY_PASSWORD`: senha da chave

Não publique a keystore nem suas senhas. Para codificar a keystore em Base64 no Linux/macOS, use `base64 -w 0 sua-chave.jks` (no macOS, `base64 sua-chave.jks`). No PowerShell: `[Convert]::ToBase64String([IO.File]::ReadAllBytes("sua-chave.jks"))`.

Se você já instalou uma versão assinada com a keystore antiga deste projeto, use **a mesma keystore antiga** nos secrets para permitir atualização por cima; uma chave nova não substitui a assinatura anterior.

## Revisão de autonomia e segurança

- **Economia:** não fixa teto/piso de CPU ou GPU; mantém DVFS/OPPs sob controle do kernel.
- Não altera PELT nem fixa frequência mínima de UFS nesse perfil.
- Não força taxa de atualização nem modo de rede; deixa tela adaptativa e modem sob controle da ROM.
- Mantém `THERMAL_AP` habilitado. Não pausa `thermald`/`mi_thermald`.
- O watchdog passa a ter intervalo padrão de 30 s (configurável entre 10 e 120 s). Durante um app classificado pelo Android como jogo, ele não reaplica CPU/GPU, evitando disputar com GameTime/Power HAL; a guarda térmica continua ativa. Jogos que não estejam classificados pelo Android como `CATEGORY_GAME` podem não ser detectados.
- O serviço só inicia controladores de tela/rede quando esses recursos estão realmente habilitados.
- O Helper não mantém loop root permanente e não adiciona o app à whitelist do Doze nem concede isenções permanentes de execução em segundo plano.

**Importante:** não há promessa de ganho percentual de bateria. O resultado depende do kernel, firmware, sinal, tela, temperatura e padrão de uso. Compare com o estado original em testes controlados. Salve um snapshot antes de aplicar ajustes.

## Estrutura

```
app/src/main/java/dev/leonardo/mt6899/
  core/      Engine.kt, Models.kt, Parse.kt
  Automation.kt
  GuardService.kt
  Infra.kt
  MainViewModel.kt, MainActivity.kt
  ui/        Components.kt, Screens.kt, NewScreens.kt
module/     Módulo Magisk/KernelSU opcional
.github/    Workflow de build
```
