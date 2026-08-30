# TASK-0052: avisar do limite do aparelho a cada jogo, e conferir que o GOS está mesmo ativo

- **Status:** em andamento
- **Criada em:** 2026-08-29
- **Concluída em:** —
- **Feature:** nenhuma
- **Bugs que resolve:** [gos-samsung-limita-clock-a-metade-em-jogo](../bugs/open/gos-samsung-limita-clock-a-metade-em-jogo_2026-08-29T12-40.md)
- **Commit:** — (o vínculo é o prefixo `TASK-0052:` no assunto)
- **Revertida por:** —
- **Publicado em:** —

## Contexto

A [TASK-0050](TASK-0050-detectar-limite-de-clock-do-aparelho.md) entregou o aviso **uma vez por
instalação** — `throttle.warned` persistido, e `start()` nem sobe a thread se ele estiver ligado.
O raciocínio na época foi "é fato sobre o aparelho, repetir é ruído".

Errado na prática, e o relato que derrubou isso foi direto: o usuário abriu o 10 Pin, o jogo estava
a **15,0 fps de média** com o clock preso em 1053 MHz, e **nenhum aviso apareceu** — porque já
tinha aparecido uma vez, horas antes. Medido na mesma hora: GOS vivo no pid 11295, o mesmo processo
que havia voltado sozinho depois de uma parada forçada.

É esse o ponto: **o corte vai e volta**. O GOS reinicia por conta própria, então a condição não é
um fato estável do aparelho — é um estado que muda, e que o usuário precisa saber toda vez que
esbarra nele, porque a ação que o desarma também precisa ser refeita toda vez.

O segundo pedido do relato é de precisão: o aviso nomeia a Samsung a partir de
`Build.MANUFACTURER`, que diz o fabricante e **não** diz se o GOS está instalado e ativo naquele
aparelho.

## Objetivo

O aviso aparece **em toda sessão de jogo** em que o corte for medido, e só atribui a culpa ao GOS
quando o pacote dele estiver de fato presente e habilitado.

## Escopo

**Entra:**

- Sai o portão de uma vez por instalação: `start()` deixa de consultar `throttle.warned`, e a
  preferência sai junto — o aviso passa a ser um por sessão de emulação (a thread já morre depois
  de disparar, então dentro da mesma sessão ele não repete).
- `ThrottleWatcher.vendorThrottlerActive()` — consulta o `PackageManager` pelo pacote do GOS e
  exige `applicationInfo.enabled`. Um pacote desabilitado por `pm disable-user` cai em
  `NameNotFoundException` e conta como inativo, que é a resposta certa.
- `<queries><package android:name="com.samsung.android.game.gos" /></queries>` no manifesto — sem
  isso a visibilidade de pacotes do Android 11+ esconde o GOS e a consulta sempre falharia.
- A escolha do texto e o botão **Como resolver** passam a usar essa consulta no lugar de
  `Build.MANUFACTURER`.

**Não entra:**

- Repetir o aviso **dentro** da mesma sessão. "Assim que identificar a lentidão" é uma vez por
  jogo aberto; um banner reaparecendo a cada minuto sobre o jogo seria pior que o problema.
- Gatear o aviso em ser Samsung. Se um aparelho de outro fabricante segurar o clock, o usuário
  merece saber igual — o que muda é só o texto, que aí não acusa ninguém.
- `throttle.detected`, que continua grudento: ele responde "este aparelho já sofreu o corte" e é o
  que decide se a linha de conserto aparece em Configurações, não se o aviso dispara.

## Como validar

No SM-A127M, com o GOS ativo:

1. Abrir um jogo, esperar o corte ser medido → o aviso aparece.
2. **Fechar e abrir outro jogo** → o aviso aparece **de novo**. É o que a TASK-0050 não fazia.
3. Com o GOS desabilitado (`pm disable-user`), abrir um jogo → nenhum aviso, e a linha de conserto
   some de Configurações, porque `vendorThrottlerActive()` passa a ser falso.

## Resultado

Preenchido ao concluir.
