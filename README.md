<div align="center">

# MT6899 Manager

### Gerenciamento de CPU, GPU, temperatura, tela e rede com acesso root

<p>
  <img src="https://img.shields.io/badge/vers%C3%A3o-0.4.0-2563eb?style=for-the-badge" alt="Versão 0.4.0">
  <img src="https://img.shields.io/badge/Android-10%2B-3DDC84?style=for-the-badge&logo=android&logoColor=white" alt="Android 10 ou superior">
  <img src="https://img.shields.io/badge/Kotlin-2.0.21-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white" alt="Kotlin">
  <img src="https://img.shields.io/badge/Jetpack_Compose-Material_3-4285F4?style=for-the-badge&logo=jetpackcompose&logoColor=white" alt="Jetpack Compose">
  <img src="https://img.shields.io/badge/ROOT-obrigat%C3%B3rio-red?style=for-the-badge" alt="Root obrigatório">
</p>

**Um painel para visualizar e ajustar parâmetros de desempenho em dispositivos MediaTek compatíveis.**

*Projeto independente, em desenvolvimento e adaptado de uma base inicialmente voltada à HyperOS para também investigar compatibilidade com Hello UI da Motorola.*

</div>

---

## Sobre o projeto

O **MT6899 Manager** é um aplicativo Android desenvolvido em Kotlin e Jetpack Compose para monitorar e controlar parâmetros de baixo nível do sistema por meio de acesso root.

A proposta é reunir em uma única interface controles que normalmente exigiriam comandos ADB/Termux ou edição manual de arquivos em `/sys` e `/proc`: frequências e governors da CPU, limites da GPU, sensores térmicos, taxa de atualização da tela, tecnologias de rede permitidas e alguns parâmetros de memória, armazenamento e scheduler.

O aplicativo detecta os recursos expostos pelo kernel e apresenta os controles que consegue localizar. Isso significa que **a disponibilidade e o resultado de cada ajuste dependem do aparelho, do kernel, da ROM e dos drivers**. O nome do projeto não significa compatibilidade universal com todos os aparelhos MediaTek.

> [!IMPORTANT]
> O aplicativo exige root. Ele não desbloqueia recursos que o kernel ou a ROM não disponibilizam e não garante aumento de FPS, redução de temperatura ou economia de bateria. Ajustes agressivos podem causar instabilidade, aquecimento e maior consumo.

## Índice

- [Funcionalidades](#funcionalidades)
- [Perfis de desempenho](#perfis-de-desempenho)
- [Serviço em segundo plano](#serviço-em-segundo-plano)
- [Compatibilidade e limitações](#compatibilidade-e-limitações)
- [Instalação e primeiros passos](#instalação-e-primeiros-passos)
- [Compilação](#compilação)
- [Estrutura do projeto](#estrutura-do-projeto)
- [Segurança e cuidados](#segurança-e-cuidados)
- [Módulo Helper](#módulo-helper-opcional-e-legado)
- [Estado do desenvolvimento](#estado-do-desenvolvimento)

---

## Funcionalidades

O aplicativo é dividido em nove abas.

### 1. Painel

Visão geral do estado atual do aparelho:

- Modelo, codinome do dispositivo, SoC e versão do kernel identificados pelo Android.
- Clusters/policies da CPU, frequência atual, governor e limites mínimo/máximo.
- Frequência e utilização da GPU, quando expostas pelo driver.
- Temperaturas disponíveis do SoC, CPU, GPU, bateria e outros sensores reconhecidos.
- Percentual e temperatura da bateria, quando fornecidos pelo sistema.
- Perfil ativo, estado do serviço, taxa de atualização e situação do controle de rede.

Os dados dinâmicos são atualizados periodicamente enquanto as telas correspondentes estão abertas.

### 2. CPU

Controles individuais para cada policy/cluster detectado pelo kernel:

- Visualizar frequência atual e limites de frequência.
- Consultar os governors que o kernel anuncia para aquela policy.
- Selecionar manualmente um governor disponível.
- Ajustar frequência mínima e máxima usando os valores reconhecidos pelo kernel.
- Restaurar a faixa de frequência de fábrica detectada para o cluster.
- Acessar parâmetros adicionais de `core_ctl` e do scheduler, caso existam no aparelho.

O aplicativo não cria governors. Ele só pode selecionar opções realmente anunciadas em `scaling_available_governors`.

### 3. GPU

Controles e informações disponibilizados pelos drivers compatíveis:

- Frequência e utilização atual da GPU.
- OPP atual e tabela de frequências detectada.
- Teto e piso de frequência controlados pelos nós GED reconhecidos.
- Opção de fixar uma OPP específica ou devolver a seleção ao controle automático do driver.
- Estado dos limitadores PPM/driver encontrados em `limit_table`.
- Parâmetros adicionais de GED e `devfreq`, quando disponíveis.

A interface confirma as escritas quando consegue reler o valor esperado. Alguns drivers podem aceitar uma escrita e, ainda assim, impor outro limite por política térmica ou de desempenho.

### 4. Térmica

Reúne leituras de sensores e ferramentas de proteção:

- Temperaturas das zonas térmicas disponíveis.
- Temperatura da bateria.
- Estados de limitadores térmicos expostos pelo kernel.
- Informações de `cooling_device`, quando presentes.
- Configuração da **Guarda térmica** por temperatura de SoC, superfície e bateria.
- Ação manual para voltar ao perfil Equilibrado e reativar proteções.
- Controle experimental para parar/iniciar `mi_thermald` e `thermald`.

A guarda térmica pode acionar o modo seguro quando identifica uma condição de risco. O modo seguro tenta reativar limitadores desabilitados, retomar os serviços térmicos pausados e aplicar o perfil **Equilibrado**.

> [!CAUTION]
> Pausar `mi_thermald` ou `thermald`, desativar limitadores térmicos ou alterar parâmetros de proteção pode permitir temperaturas perigosas. Essas opções não são recomendadas para uso diário. A guarda térmica depende do serviço do aplicativo estar ativo e não substitui as proteções físicas/firmware do aparelho.

### 5. Sistema

Exibe parâmetros avançados encontrados durante a detecção do dispositivo. Entre os grupos possíveis estão:

| Grupo | Exemplos de parâmetros |
|---|---|
| Scheduler | PELT multiplier e Energy Aware Scheduling (EAS) |
| CPU / `core_ctl` | Ativação, quantidade mínima/máxima de núcleos e thresholds |
| Memória | `swappiness`, `vfs_cache_pressure`, `dirty_ratio`, `page-cluster` e outros `vm` disponíveis |
| I/O | Scheduler de cada bloco e `read_ahead_kb` |
| DDR / UFS | Governor e limites de frequência via `devfreq`, quando detectados |
| GPU / GED | Margens DVFS, janela de carga, stride, boost e demais nós encontrados |
| Térmica | Parâmetros específicos do driver MediaTek que estejam presentes |

Os controles são montados a partir dos nós existentes no aparelho. Um parâmetro que não aparece na interface normalmente não foi encontrado ou não é legível naquele kernel.

A opção **Travar valores** aplica permissões de leitura para impedir que determinados limites mínimo/máximo da CPU sejam sobrescritos facilmente. Ao desativá-la, o aplicativo tenta restaurar as permissões que salvou antes do bloqueio.

A tela também permite salvar um snapshot dos nós identificados e tentar restaurá-los posteriormente. O snapshot não é uma imagem completa do sistema e não garante restaurar todo estado mantido por drivers ou serviços externos.

### 6. Perfis

Inclui quatro perfis integrados:

| Perfil | Objetivo e comportamento geral |
|---|---|
| **Economia** | Limita a frequência máxima de cada cluster de CPU a 60%, prioriza `powersave` e limita a GPU a 40% da frequência máxima disponível. |
| **Equilibrado** | Mantém livre o cluster de menor capacidade, limita o segundo a 85% e os seguintes a 80% (usando `cpu_capacity`, com id da policy como desempate) e limita a GPU a 75%. Usa governor dinâmico. |
| **Desempenho** | Usa limites mais altos, piso de frequência para CPU/GPU e tela fixa em 120 Hz, quando suportado. |
| **Máximo ⚠** | Configuração mais agressiva, com pisos elevados e alteração de limitador térmico. Não recomendado para uso contínuo. |

Os valores exatos são calculados com base nas policies e frequências detectadas. Portanto, podem variar entre aparelhos e kernels.

**Sobre governors:** Economia prioriza `powersave`; se ele não existir em uma policy, usa `schedutil`, depois `sugov_ext` ou outra opção anunciada. Equilibrado prioriza `schedutil`. Desempenho e Máximo mantêm a prioridade original (`sugov_ext`, depois `schedutil` e, por fim, a primeira opção disponível). A escolha final sempre respeita os governors que o kernel realmente disponibiliza; os limites de frequência são aplicados separadamente.

Também é possível:

- Editar os perfis integrados e restaurar seus valores originais; as alterações ficam salvas como overrides locais.
- Ajustar teto/piso e governor de cada policy da CPU, além de teto/piso da GPU.
- Salvar, editar e excluir perfis personalizados.
- Criar regras por aplicativo: escolher qualquer app inicializável e associar a ele um perfil, por exemplo **Desempenho** para jogos ou Câmera. Ao sair do app, o perfil base escolhido manualmente é restaurado.
- Permitir que os perfis controlem também as configurações das abas Tela e Rede.
- Ativar o watchdog para reaplicar o perfil quando os valores monitorados forem alterados por outros componentes do sistema.
- Solicitar a aplicação do perfil base durante a inicialização do Android.

> **Importante sobre os percentuais:** o limite de CPU representa teto de frequência em relação ao máximo reportado pelo kernel; não é um limitador de utilização em 60% do scheduler. O valor efetivo é arredondado para uma frequência/OPP aceita pelo kernel. Se `powersave` não existir em uma policy, Economia usa o governor alternativo disponível e mantém o teto de frequência.

### 7. Tela

Controle da taxa de atualização do display:

- **Sistema:** devolve o controle da taxa de atualização ao Android.
- **Fixo:** tenta manter uma taxa selecionada entre as opções reportadas pelo aparelho.
- **Auto (toque/ocioso):** usa uma taxa maior durante interação e reduz para outra taxa depois de um período sem toques.
- **Regras por aplicativo:** define uma taxa específica ou o modo automático para cada aplicativo, com prioridade sobre o modo global.
- Intervalo de inatividade configurável.
- Estado atual, taxa reportada pelo sistema, leitor de toque e aplicativo em primeiro plano.

O modo automático depende de o aplicativo conseguir identificar o dispositivo touchscreen e ler os eventos de toque. As regras por aplicativo usam acesso a estatísticas de uso; quando possível, há uma alternativa baseada em `dumpsys` com root.

O controle altera as configurações de taxa de atualização do Android. A taxa efetiva ainda depende do display, do firmware, do aplicativo em primeiro plano e das políticas da ROM.

### 8. Rede

Oferece quatro modos:

| Modo | Comportamento pretendido |
|---|---|
| **Sistema** | Não mantém uma política própria; devolve o controle ao sistema. |
| **Dinâmico (DNS)** | Permite 5G quando o DNS Privado está configurado com hostname/provedor e bloqueia NR quando ele é desligado. |
| **Sempre 4G** | Tenta bloquear NR (5G), mantendo as demais tecnologias incluídas na máscara configurada. |
| **Permitir 5G** | Permite LTE e NR; não obriga o modem a se conectar ao 5G. |

O modo Dinâmico observa a configuração de DNS Privado do Android. Existe uma opção para considerar o modo **Automático** como ligado; por padrão, o gatilho principal é o DNS Privado configurado com hostname.

A implementação utiliza o comando `cmd phone set-allowed-network-types-for-users`, envia a máscara de tecnologias em formato binário e tenta confirmar o resultado por meio de uma leitura posterior. Se o comando não estiver disponível ou a leitura não confirmar a alteração, o aplicativo registra a falha em vez de apresentar um sucesso não verificado.

> [!NOTE]
> Permitir 5G não significa forçar uma conexão 5G. A conexão real depende de cobertura, operadora, SIM, modem e políticas da ROM. O comando de controle também pode variar ou estar bloqueado entre fabricantes; a compatibilidade precisa ser validada em cada dispositivo.

### 9. Logs

Ferramentas para entender o que o aplicativo conseguiu ou não aplicar:

- Indicadores de disponibilidade de root e atividade do serviço.
- Estado reportado pelos controladores de tela e rede.
- Verificação de acesso ao uso de aplicativos.
- Diagnóstico específico da rede, incluindo comandos e leitura dos tipos de rede permitidos.
- Auto-teste geral.
- Filtro por categoria e opção para exibir apenas avisos/erros.
- Copiar, compartilhar e limpar os registros.

As escritas em nós do kernel são registradas com o valor solicitado, valor relido e resultado da verificação quando essa leitura é possível.

---

## Perfis de desempenho

Os perfis integrados combinam vários ajustes em uma única ação. Dependendo dos recursos encontrados, podem alterar:

- Governor e limites mínimo/máximo de cada policy da CPU.
- Teto/piso de frequência da GPU.
- Alguns parâmetros de scheduler, DDR/UFS e limitadores do driver.
- Taxa de atualização e política de rede, **somente** quando a opção **Perfis controlam tela e rede** estiver ativada.

Os perfis não são presets universais. Frequências, governors e nós disponíveis são lidos do próprio aparelho. Por isso, duas unidades com o mesmo SoC, mas kernels diferentes, podem apresentar resultados diferentes.

> [!WARNING]
> O perfil **Máximo ⚠** desativa o limitador `THERMAL_AP` na configuração integrada e aplica pisos mais altos. Isso pode aumentar consumo e temperatura. Use apenas para testes controlados e mantenha a guarda térmica ativa.

---

## Serviço em segundo plano

O aplicativo inclui um serviço Android em primeiro plano (`GuardService`) que executa as automações necessárias:

- Reaplica o perfil ativo no boot, quando essa opção está habilitada.
- Monitora e reaplica o perfil quando o watchdog está ativo.
- Executa a automação de tela e a observação do DNS Privado/rede quando esses recursos estão configurados.
- Monitora condições térmicas quando a guarda/watchdog ou estados de risco exigem isso.
- Mantém um indicador de atividade visível na aba Logs.

O serviço usa `START_STICKY` e o aplicativo possui um `BootReceiver` para solicitar a inicialização após o boot, respeitando as configurações escolhidas. O Android e a ROM ainda podem impor restrições de execução em segundo plano.

O serviço é iniciado quando há uma função que precisa dele e pode encerrar quando não existe automação ativa.

---

## Compatibilidade e limitações

### Plataforma

- **Android mínimo declarado:** Android 10 (API 29).
- **SDK de compilação/alvo:** API 35.
- **Alvo de desenvolvimento atual:** dispositivos MediaTek com nós de kernel compatíveis; a adaptação atual está sendo direcionada também à Hello UI da Motorola.
- **Root:** obrigatório para os controles de baixo nível.

### O que determina a compatibilidade?

O aplicativo procura dinamicamente várias interfaces do kernel, como:

- `/sys/devices/system/cpu/cpufreq/policy*`
- `/sys/class/devfreq`
- `/sys/kernel/ged`
- `/proc/gpufreqv2`
- `/sys/class/thermal`
- `/proc/sys`
- `/sys/block`

Nem todo aparelho implementa todos esses caminhos. A ausência de um nó significa que o respectivo controle pode não aparecer ou não funcionar. Drivers também podem ignorar, limitar ou sobrescrever valores escritos.

### Observações importantes para Hello UI / Motorola

A base do projeto começou com foco em HyperOS. A adaptação para Hello UI está em andamento; não se deve assumir que toda função herdada da HyperOS funciona na Motorola. Em especial:

- Os dados específicos de `thermal_message` podem existir em uma ROM e não em outra.
- A disponibilidade de governors depende do kernel do aparelho.
- O comando de rede depende da implementação de telefonia exposta pela ROM.
- O controle de taxa de atualização depende dos modos reportados pelo display e das políticas do sistema.
- O Helper antigo foi criado para contornar restrições específicas da HyperOS e não é requisito para os controles principais do APK.

**Compatibilidade parcial é esperada.** Os Logs são a principal ferramenta para confirmar o que foi detectado e aplicado em cada aparelho.

---

## Instalação e primeiros passos

1. Instale o APK gerado pelo projeto.
2. Abra o aplicativo e conceda a solicitação de superusuário pelo Magisk, KernelSU, APatch ou outro gerenciador compatível com `su`.
3. Na aba **Sistema**, use **Salvar estado atual** antes de testar ajustes avançados.
4. Comece pelo perfil **Equilibrado** e valide os valores na aba CPU/GPU.
5. Ative somente as automações que pretende usar: Tela, Rede, watchdog ou aplicação no boot.
6. Consulte a aba **Logs** depois de cada alteração para verificar o resultado.

Para regras de taxa por aplicativo, conceda o acesso ao uso de aplicativos solicitado pela própria tela. No Android 13 ou superior, permita também a notificação do serviço em primeiro plano.

Se o root não for detectado, confira se o gerenciador concedeu a permissão ao pacote `dev.leonardo.mt6899` e use **Tentar de novo** na tela inicial.

---

## Compilação

### Requisitos

- JDK 17.
- Android SDK com plataforma de compilação API 35 e Build Tools compatíveis.
- Gradle 8.9.
- Conexão com a internet para baixar dependências na primeira compilação.

### Compilar localmente

Na raiz do projeto:

```bash
gradle :app:assembleDebug
```

Para gerar a versão release:

```bash
gradle :app:assembleRelease
```

O APK release fica em:

```text
app/build/outputs/apk/release/
```

### GitHub Actions

O workflow em `.github/workflows/build.yml` executa automaticamente em:

- Push para `main` ou `master`.
- Pull requests.
- Execução manual pela aba **Actions** (*Run workflow*).

O workflow configura JDK 17, Android SDK e Gradle 8.9, compila o APK release e publica os artefatos:

| Artefato | Conteúdo |
|---|---|
| `MT6899-Manager-apk` | APK release do aplicativo |
| `MT6899-Helper-module` | ZIP do módulo Helper legado (opcional) |

O Helper é empacotado pelo workflow por compatibilidade com o projeto original; isso **não significa que seja obrigatório instalar o módulo**.

> [!CAUTION]
> **A configuração de assinatura presente nesta cópia do projeto ainda usa um arquivo de keystore dentro de `app/` e credenciais fixas no Gradle. Não publique essa chave em um repositório público.** Antes de abrir o repositório, mova a assinatura para GitHub Actions Secrets/variáveis locais, remova o arquivo de keystore do controle de versão e adapte `app/build.gradle.kts` e o workflow para ler os segredos. Se a chave já foi publicada, considere-a exposta.

---

## Estrutura do projeto

```text
MT6899Manager/
├── .github/
│   └── workflows/
│       └── build.yml              # Build automático no GitHub Actions
├── app/
│   ├── src/main/
│   │   ├── java/dev/leonardo/mt6899/
│   │   │   ├── core/
│   │   │   │   ├── Engine.kt      # Detecção, leitura e escrita dos nós do kernel
│   │   │   │   ├── Models.kt      # Modelos e estruturas de dados
│   │   │   │   └── Parse.kt       # Conversão e interpretação das leituras
│   │   │   ├── ui/                # Telas e componentes Jetpack Compose
│   │   │   ├── Automation.kt      # Serviço de tela, toque, apps e rede
│   │   │   ├── GuardService.kt    # Serviço em primeiro plano e boot receiver
│   │   │   ├── Infra.kt           # Root shell, preferências e logs
│   │   │   ├── MainActivity.kt
│   │   │   └── MainViewModel.kt
│   │   └── AndroidManifest.xml
│   └── build.gradle.kts
├── module/                        # Helper Magisk/KernelSU legado e opcional
├── build.gradle.kts
├── gradle.properties
├── settings.gradle.kts
└── README.md
```

### Tecnologias utilizadas

- Kotlin 2.0.21.
- Jetpack Compose e Material 3.
- Android Gradle Plugin 8.7.2.
- AndroidX e Kotlin Coroutines.
- [libsu](https://github.com/topjohnwu/libsu) para execução de comandos com privilégios de superusuário.

---

## Segurança e cuidados

Este aplicativo altera parâmetros sensíveis do sistema. Antes de usar:

- Faça backup dos dados importantes.
- Salve um snapshot antes de mudar valores avançados.
- Não desative proteções térmicas para uso cotidiano.
- Evite manter pisos de frequência elevados sem necessidade.
- Acompanhe temperatura, estabilidade e consumo após cada mudança.
- Faça uma alteração por vez e consulte os Logs.
- Não aplique valores copiados de outro aparelho sem confirmar que os nós e faixas são equivalentes.

O snapshot é uma ajuda para voltar aos valores previamente lidos, não uma garantia de recuperação de qualquer estado do kernel. Uma reinicialização também pode restaurar automaticamente parte dos parâmetros temporários.

---

## Módulo Helper (opcional e legado)

A pasta `module/` contém um módulo instalável por Magisk/KernelSU que foi criado para a versão inicialmente voltada à HyperOS. Ele tenta:

- Aplicar exceções de economia/execução em segundo plano para o APK.
- Solicitar a inicialização do `GuardService` no boot quando o aplicativo habilita essa opção.
- Verificar periodicamente se o serviço continua ativo e tentar iniciá-lo novamente.

O script inclui comandos específicos da MIUI/HyperOS, incluindo a operação de início automático `10008`. Esses comandos podem não existir ou não ter efeito na Hello UI.

**O Helper não é necessário para abrir o APK, conceder root, aplicar ajustes manualmente ou utilizar os controladores internos.** O próprio aplicativo contém `GuardService` e `BootReceiver`. Na Motorola/Hello UI, prefira testar sem o Helper e só considere instalá-lo se houver evidência nos Logs de que a ROM encerra o serviço e a recuperação adicional é realmente necessária.

---

## Estado do desenvolvimento

**Versão documentada: 0.4.0**

Principais alterações desta versão:

- Novos limites de Economia (CPU 60%, governor `powersave` quando suportado e GPU 40%) e Equilibrado (CPU 100/85/80% por capacidade dos clusters e GPU 75%).
- Editor de perfis integrados e personalizados, com restauração dos presets originais.
- Regras de perfil por aplicativo, incluindo retorno automático ao perfil base ao sair do aplicativo.
- Integração das regras por aplicativo ao serviço e à proteção térmica, sem alterar os parâmetros originais de Desempenho.
- Ajuste do controle 4G/5G para enviar a máscara de tecnologias em binário e verificar o estado relido.
- Remoção do fallback baseado em `preferred_network_mode`, que não comprovava uma alteração real do modem.
- Seleção de governor baseada nas opções anunciadas por cada CPU policy.
- Registro dos governors detectados para facilitar o diagnóstico.
- Correção do workflow de CI para usar `android-actions/setup-android@v4` e instalar `platform-tools`, sem solicitar o pacote legado `tools`.

Essas mudanças corrigem a lógica identificada no código, mas **não substituem testes no aparelho real**. A confirmação de funcionamento em cada ROM, kernel, modem e variante de hardware continua sendo necessária.

---

## Aviso legal

MT6899 Manager é um projeto independente e não é afiliado, patrocinado ou endossado pela Motorola, Xiaomi, MediaTek, Google, Magisk ou KernelSU. Marcas e nomes citados pertencem aos respectivos proprietários.

Nenhuma licença de distribuição foi identificada nesta cópia do projeto. Até que um arquivo `LICENSE` seja adicionado ao repositório, não presuma que o código esteja liberado para redistribuição ou uso sob uma licença open source específica.

<div align="center">

**Feito para explorar, monitorar e entender melhor o comportamento do kernel Android.**

</div>
