# MIG-0002: Seletor de Ícones Alternativos do App (AppIconManager)

- **Prioridade:** Média (Funcionalidade de Customização Visual)
- **Status:** Concluído
- **Origem:** `version1` (`app/src/main/java/kr/co/iefriends/pcsx2/utils/AppIconManager.java` + `layout/include_settings_card_app_icon.xml`)
- **Documento de referência:** [`docs/plano-fork-sobre-upstream.md`](../../plano-fork-sobre-upstream.md) §5

---

## 1. Contexto e Objetivo

Na `version1`, o usuário podia escolher entre múltiplos temas de ícones para o aplicativo no launcher do Android (Clássico, Dourado, Retrô, Minimalista, etc.). O recurso foi implementado em `AppIconManager.java` (355 linhas) e utilizava `PackageManager.setComponentEnabledSetting` / activity-aliases para alternar os ícones na tela inicial do sistema.

No fork, a infraestrutura visual do app foi reescrita em **Jetpack Compose**, mas a opção de personalização do ícone ainda não havia sido exposta na nova aba de Configurações.

---

## 2. Análise Técnica

- **Lógica Kotlin:** `AppIconManager.kt` gerencia a lista de aliases do `AndroidManifest.xml`, salva o ícone ativo em SharedPreferences (com suporte à migração automática das preferências da v1), e aplica a alteração no launcher dinamicamente via `PackageManager.setComponentEnabledSetting(..., DONT_KILL_APP)`.
- **Adaptação para o Fork:**
  1. Portado `AppIconManager` para `com.armsx2.utils.AppIconManager` em Kotlin.
  2. Declarados os `<activity-alias>` necessários no `platforms/android/app/src/main/AndroidManifest.xml` (`BootSplashActivityClassic`, `BootSplashActivityGold`, `BootSplashActivityRetro`, `BootSplashActivityMinimal`).
  3. Criada a UI em Jetpack Compose dentro da aba de Aparência/Interface (`platforms/android/app/src/main/java/com/armsx2/ui/settings/AppTab.kt`) com preview de ícones, estado selecionado destacado e suporte à navegação por controle físico.
  4. Gerados os conjuntos de ícones nas densidades `mipmap-mdpi`, `mipmap-hdpi`, `mipmap-xhdpi`, `mipmap-xxhdpi`, `mipmap-xxxhdpi` (com versões padrão e circulares).

---

## 3. Escopo da Implementação

**Arquivos criados/modificados:**
- `platforms/android/app/src/main/java/com/armsx2/utils/AppIconManager.kt`
- `platforms/android/app/src/main/AndroidManifest.xml`
- `platforms/android/app/src/main/java/com/armsx2/runtime/MainActivityRuntime.kt`
- `platforms/android/app/src/main/java/com/armsx2/ui/settings/AppTab.kt`
- `platforms/android/app/src/main/java/com/armsx2/i18n/I18n.kt` & `pt-BR.json`
- Recursos de imagem/ícones correspondentes em `platforms/android/app/src/main/res/mipmap-*/`

---

## 4. Como Validar

1. Abrir o aplicativo no Android, ir em Configurações → App.
2. Selecionar um ícone alternativo na seção de Ícones do Aplicativo (Padrão, Clássico, Dourado, Retrô, Minimalista).
3. Voltar para a tela inicial do Android e verificar se o ícone do launcher foi atualizado corretamente sem fechar o app de forma anormal.

