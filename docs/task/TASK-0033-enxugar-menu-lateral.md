# TASK-0033: retirar do menu lateral as seis linhas que não são do produto

- **Status:** concluída
- **Criada em:** 2026-08-27
- **Concluída em:** 2026-08-27
- **Feature:** [FEAT-0001](../features/FEAT-0001-sync-upstream-oficial.md)
- **Bugs que resolve:** nenhum
- **Commit:** — (o vínculo é o prefixo `TASK-0033:` no assunto)
- **Revertida por:** —
- **Publicado em:** —

## Origem

Pedido do usuário: no menu hambúrguer (canto superior esquerdo), retirar **BIOS de inicialização,
Discord, GitHub, Site, O que há de novo** e **Amigos**.

As três do meio apontam para a comunidade do upstream — `discord.gg/2Tynvwhc4A`,
`github.com/ARMSX2/ARMSX2`, `armsx2.net`. Não são os nossos canais, e o fork é distribuído como
RetroSystem PS2; mandar o usuário para lá é mandá-lo para o suporte de outro projeto.

## Escopo

**Dentro:** as seis linhas de `DrawerContent`, em `NavigationDrawer.kt`, e o código que existia
só para servi-las.

**Fora, deliberadamente:**

- As telas `NewsScreen` e `FriendsScreen` continuam compiladas, e as rotas `AppRoute.News` /
  `AppRoute.Friends` continuam no `when` de `sameDestination` — que é exaustivo sobre `AppRoute`,
  então tirar os ramos quebraria a compilação. O menu era o **único** ponto de entrada das duas
  (`AppNavigation.kt:110-111` é o outro lado da rota, não uma entrada), logo elas ficam
  inalcançáveis. Isso é o que foi pedido; apagar as telas é outra decisão, e outra task.
- `MainActivityRuntime.startBios()` continua existindo e sendo chamado de
  `MainActivityRuntime.kt:2022`. Some o atalho no menu, não a função.
- As chaves `about.discord` / `about.github` / `about.website` continuam no `I18n.kt` e nos 20+
  JSONs de tradução. Ficam órfãs, mas são dados, não código: removê-las custaria um passe por cada
  idioma para não ganhar nada.
- `FriendsCountBadge` continua pública — `FriendsScreen.kt:318` também a usa.

## O que saiu

Em `platforms/android/app/src/main/java/com/armsx2/navigation/NavigationDrawer.kt`:

1. A linha `bios.boot.title` da seção **primary**.
2. As linhas `about.discord`, `about.github`, `about.website`, `news.title` e `friends.title` da
   seção **about** — que fica só com `about.title` (Sobre).
3. O que passou a não ter chamador: as constantes `DiscordUrl`/`GithubUrl`/`WebsiteUrl`, a função
   `openExternalUrl` e o `val context = LocalContext.current`.
4. O parâmetro `friendsBadge` de `DrawerItem` e de `DrawerRow`, e o ramo que desenhava o contador
   sobre o glifo 👥. Nada mais o marcava como `true`.
5. Os imports que caíram junto: `ActivityNotFoundException`, `Context`, `Intent`, `Uri`, `Toast`,
   `LocalContext`, `I18n` e `layout.offset`.

## Como validar

`./gradlew :app:compileGithubDebugKotlin` — o risco real aqui é import ou símbolo órfão, e é
exatamente isso que o compilador Kotlin recusa. Depois, abrindo o menu no aparelho:

```
BIBLIOTECA   🎮 Jogos · 🏆 Conquistas · ⚙️ Configurações
GERENCIAR    (inalterado)
SOBRE        ℹ️ Sobre
APLICATIVO   ⏻ Sair
```

A seção BIBLIOTECA perde só o ▶️; a seção SOBRE passa de seis linhas para uma.

## Resultado

Entregue. Menu com seis linhas a menos e nenhum símbolo órfão no arquivo.
