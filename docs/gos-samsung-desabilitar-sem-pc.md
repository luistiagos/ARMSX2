# Desabilitar o Game Optimizing Service da Samsung (com e sem PC)

**Para quem atende suporte, e para o usuário avançado.** Não é material para o cliente final: o
caminho sem PC pede opções do desenvolvedor e pareamento por código, e isso não é instrução para
quem é leigo — é abandono. O que o cliente leigo recebe é o aviso dentro do app.

Registro técnico completo, com as medições:
[`bugs/open/gos-samsung-limita-clock-a-metade-em-jogo`](bugs/open/gos-samsung-limita-clock-a-metade-em-jogo_2026-08-29T12-40.md).

---

## O problema, em uma linha

Em aparelhos Samsung, o **Game Optimizing Service** (pacote `com.samsung.android.game.gos`) trava a
CPU enquanto o nosso app está em primeiro plano. Medido no Galaxy A12 (`SM-A127M`, Exynos 850,
Android 13):

| | clock dos núcleos | velocidade da emulação (alvo 50 fps) |
|---|---|---|
| GOS ativo | **1053 MHz** de 2002 | **8,5 fps** |
| GOS desabilitado | **2002 MHz** | **49,8 fps** |

Não é o emulador. Não é o aparelho estar fraco. É uma política da Samsung para o que ela classifica
como jogo — e o mesmo telefone deixa o Candy Crush rodar a 2002 MHz.

---

## Caminho A — com um PC (1 comando)

Precisa de: cabo USB, `adb` instalado, **Depuração USB** ligada nas Opções do desenvolvedor.

```
adb shell pm disable-user --user 0 com.samsung.android.game.gos
```

É isso. Vá para [Conferir](#conferir-que-funcionou).

---

## Caminho B — sem PC nenhum, pelo próprio telefone

Usa o **LADB**, um app que abre um shell adb dentro do próprio aparelho, aproveitando a *depuração
sem fio* do Android 11+. O comando executado é o mesmo do caminho A.

> **Por que o LADB, e não o Shizuku ou o Brevent** — os dois mais citados em vídeos e fóruns:
>
> - **Shizuku** resolve, mas dá mais trabalho: ele só *empresta privilégio*, e você ainda precisa de
>   um segundo app cliente para executar o comando. O LADB já é o shell.
> - **Brevent** é a escolha mais arriscada, e o motivo é sutil: a função nativa dele é **forçar
>   parada e pôr em standby**, não desabilitar o pacote. Quem seguir um tutorial até "encontre o GOS
>   na lista e congele" cai no force-stop — que **nós medimos ser desfeito** assim que o usuário
>   volta ao jogo. Ele *tem* como executar o comando certo (o `Exec Command`, desde a 2.6.6), mas
>   isso é uma API por Intent, não um terminal, e há relatos de suportar só "simple shell script".
>
> Regra prática para validar qualquer tutorial: **se ele não terminar num `pm disable-user`, não
> resolve.** Congelar, hibernar e forçar parada são todos o mesmo beco.

### 1. Liberar as Opções do desenvolvedor

Ajustes → **Sobre o telefone** → **Informações de software** → tocar **7 vezes** em
**Número de compilação**.

### 2. Ligar a depuração sem fio

Ajustes → **Opções do desenvolvedor** → **Depuração sem fio** → ligar.

O aparelho precisa estar numa rede **Wi-Fi** (não precisa ter internet).

### 3. Instalar o LADB

Google Play (versão paga) ou as builds gratuitas no GitHub do projeto.

### 4. Parear

Ainda em **Depuração sem fio** → **Parear dispositivo com código de pareamento**. Aparecem um
**código de 6 dígitos** e uma **porta**. Abra o LADB e informe os dois.

> **O código expira rápido e a tela some se você sair dela.** Use **tela dividida** ou janela
> flutuante, com o LADB e os Ajustes lado a lado. É o erro nº 1 de quem tenta pela primeira vez.

### 5. Executar

No shell do LADB:

```
pm disable-user --user 0 com.samsung.android.game.gos
```

---

## Conferir que funcionou

```
pm list packages -d | grep gos
```

Se o pacote aparecer na lista (`-d` = *disabled*), está desabilitado.

**Conferência que vale mais:** abra um jogo e deixe rodar ~1 minuto. Sem o GOS, o aparelho de teste
foi de 8,5 para 49,8 fps. Se o app mostrar o aviso de limite de CPU, é porque **não** funcionou.

## Reverter

```
pm enable com.samsung.android.game.gos
```

---

## O que esperar depois

- **A desabilitação persiste** entre reinícios — você faz uma vez só. (A *depuração sem fio* é que
  costuma se desligar ao reiniciar; isso não afeta o pacote já desabilitado.)
- **Uma atualização de sistema da Samsung pode reabilitar** o pacote. Se a lentidão voltar depois de
  uma atualização, repita.
- Nada mais no telefone muda: o GOS não é usado por outra função do sistema.

---

## O que NÃO funciona (não perca tempo)

Cada linha abaixo foi testada no aparelho, com medição:

| Tentativa | Por que não resolve |
|---|---|
| **"Desativar"** na tela de informações do GOS | O botão está **esmaecido e morto**. Tocar nele não abre diálogo nem muda nada — verificado. |
| **"Forçar parada"** na mesma tela | Funciona **e é desfeito**: para executá-la o usuário sai do app, e a volta ao jogo ressuscita o GOS. Medido: 2 s depois da transição de foco o teto de 1053 MHz voltou. Serve como alívio, não como conserto. |
| **Game Booster / seletor de desempenho** | **Não existe** nesta linha de aparelho. As Configurações do Game Launcher do A12 só trazem visor, notificações, privacidade, publicidade, sobre e ajuda; não aparece ícone flutuante nem notificação do Game Booster com o jogo rodando. |
| **Game Mode API do Android** (`performance`, opt-out de intervenções) | Não alcança frequência de CPU: o framework só governa *downscaling* de backbuffer e *override* de fps. Testado com o modo aplicado — clock não se mexeu. |
| **Tirar `isGame` / `appCategory="game"` / `category.GAME`** do app | O app saiu da lista de jogos do Android e o GOS continuou cortando, inclusive com o banco dele zerado. |
| **`sem_enhanced_cpu_responsiveness=1`** | Sem efeito. |
| **Desinstalar** o GOS (`pm uninstall`) | Ele é app de sistema (`/system/priv-app`) e volta. Só **desabilitar** segura. |
| **Congelar / hibernar** pela lista de um app como o Brevent | É force-stop com outro nome, e cai na linha acima: desfeito ao voltar ao jogo. |

---

## Procedência das informações

- **Medido** neste projeto, no `SM-A127M`: todos os números de clock e fps, o comportamento dos
  botões da tela do GOS, o efeito da parada forçada e da volta ao app, e o resultado do
  `pm disable-user` por USB.
- **Vem da documentação dos projetos e da comunidade**, não executado por nós: o pareamento da
  depuração sem fio pelo LADB e a persistência do estado desabilitado entre reinícios. A diferença
  entre o caminho A e o B é só *como o shell chega até você* — o comando é o mesmo.

Fontes: [LADB (GitHub)](https://github.com/tytydraco/ladb) ·
[XDA — rodar comandos adb sem PC](https://www.xda-developers.com/debloat-your-phone-run-adb-shell-commands-no-root-no-pc/) ·
[droidwin — desabilitar o GOS](https://droidwin.com/disable-uninstall-samsung-game-optimizing-service-app-via-adb/) ·
[Game Optimizing Service (NamuWiki)](https://en.namu.wiki/w/Game%20Optimizing%20Service)
