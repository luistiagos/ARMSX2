# TASK-0035: retirar da tela Sobre os cards Repositório no GitHub, Projeto PCSX2 e Créditos

- **Status:** concluída
- **Criada em:** 2026-08-27
- **Concluída em:** 2026-08-27
- **Feature:** [FEAT-0001](../features/FEAT-0001-sync-upstream-oficial.md)
- **Bugs que resolve:** nenhum
- **Commit:** — (o vínculo é o prefixo `TASK-0035:` no assunto)
- **Revertida por:** —
- **Publicado em:** —

## Origem

Pedido do usuário: no menu hambúrguer → Sobre o aplicativo, retirar o card **Repositório no
GitHub**, o card **Projeto PCSX2** e o card **Créditos**.

## Escopo

**Dentro:** os três cards em `AboutScreen.kt` — dois `ProjectCard` (abre `github.com/ARMSX2/ARMSX2`
e `github.com/PCSX2/pcsx2` no navegador) e o `GlassPanel` de créditos da equipe (adicionado em
[TASK-0028](TASK-0028-creditos-na-tela-sobre.md)) — e o que existia só para servi-los.

**Fora, deliberadamente:**

- Os textos de atribuição da música e dos efeitos sonoros do menu (`app.credits.music`,
  `app.credits.sfx`), logo abaixo na mesma tela. Não são "o card Créditos" — são blocos de texto
  soltos, sem `GlassPanel`, e o pedido não os cita; são atribuição de licença CC0, não crédito de
  equipe.
- Os painéis de Overview / Compilação / Hardware, que ficam intactos.
- As chaves de i18n (`about.repository.*`, `about.pcsx2.*`, `about.credits.*`) em `I18n.kt` e nos
  JSONs de tradução — ficam órfãs, mas são dado, não código; mesma decisão da
  [TASK-0033](TASK-0033-enxugar-menu-lateral.md) para as chaves do menu.

## O que saiu

Em `platforms/android/app/src/main/java/com/armsx2/ui/about/AboutScreen.kt`:

1. As duas chamadas a `ProjectCard` (variante compacta e a `Row` lado a lado) e o `GlassPanel` de
   créditos que vinha logo depois.
2. O composable privado `ProjectCard` inteiro — sem chamador depois do item 1.
3. As constantes `RepositoryUrl` / `Pcsx2RepositoryUrl` e o `val uriHandler = LocalUriHandler.current`
   — só existiam para abrir os dois links.
4. Seis imports que ficaram órfãos: `BorderStroke`, `Box`, `layout.size`, `RoundedCornerShape`,
   `material3.Surface`, `platform.LocalUriHandler`. Conferidos um a um antes de remover — `Surface`
   e `Box`, por exemplo, aparecem em outras linhas do arquivo só como `MaterialTheme.colorScheme.
   onSurfaceVariant` (substring, não o composable) ou dentro do próprio `ProjectCard` que saiu.

`TextOverflow` e `FontWeight` continuam importados: `InfoRow` e `PanelTitle`, que ficam de pé,
também os usam.

## Como validar

`./gradlew :app:compileGithubDebugKotlin` — o risco aqui era import ou função órfã, que o Kotlin
recusa a compilar; rodei e voltou `BUILD SUCCESSFUL`. Na tela (menu → Sobre), a lista de painéis
passa de Overview/Compilação/Hardware/GitHub/PCSX2/Créditos/música/sfx para
Overview/Compilação/Hardware/música/sfx.

## Resultado

Entregue. Três cards a menos e nenhum símbolo órfão no arquivo.
