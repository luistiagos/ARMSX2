# TASK-0088: um build de debug **não-`debuggable`**, para medir sem o interpretador do ART no caminho

- **Status:** em andamento
- **Criada em:** 2026-09-05
- **Concluída em:** —
- **Feature:** nenhuma
- **Bugs que resolve:** nenhum diretamente — desbloqueia a medição pendente de
  [digitar-custa-97-a-450ms](../bugs/open/armsx2-fork/digitar-custa-97-a-450ms-por-tecla-na-thread-da-ui_2026-08-31T21-30.md)
  e de [anr-ao-abrir-o-menu-de-pausa-sob-angle](../bugs/open/armsx2-fork/anr-ao-abrir-o-menu-de-pausa-sob-angle_2026-09-05T01-51.md)
- **Commit:** — (o vínculo é o prefixo `TASK-0088:` no assunto)
- **Revertida por:** —
- **Publicado em:** — (não chega ao cliente)

## O problema, e ele contamina três registros

A [TASK-0086](TASK-0086-eco-da-busca-nao-recompoe-a-biblioteca.md) mediu que **44,8% da CPU da
thread da UI é o interpretador do ART**, porque o APK é `debuggable` e **o ART recusa AOT para
esses**: o `dexopt` fica em `status=run-from-apk`, e `cmd package compile -m speed` cai para
`verify` — a própria documentação do runtime diz que ele *ignora o código compilado* de um app
debuggable.

Isso não é um detalhe de laboratório. Três registros abertos hoje dependem de números tirados nesse
regime:

- a **digitação**, cujo perfil inteiro saiu de um APK `debuggable`;
- o **ANR sob ANGLE**, cuja plausibilidade cai muito se metade da CPU da UI for interpretador;
- e, por tabela, qualquer conclusão futura de desempenho medida neste aparelho.

## Por que não basta usar a APK de release

Duas paredes, as duas já batidas nesta série:

1. **A release não instala neste aparelho.** Ela é assinada com a chave de produção e o que está no
   `SM-A127M` é um build de **debug** — `INSTALL_FAILED_UPDATE_INCOMPATIBLE`.
2. **Desinstalar não é opção.** A pasta de dados tem **14 GB de ROMs**, que um `adb uninstall`
   levaria junto.

E há uma terceira, menos óbvia: a release liga R8 e `isShrinkResources`, então ela mede *outra
coisa* — código encolhido e otimizado. Para isolar **o interpretador**, a única variável que pode
mudar é o flag `debuggable`.

## Escopo

**Entra:**

- Propriedade Gradle **`-Parmsx2.debug.debuggable=false`** que desliga `isDebuggable` no build type
  de debug. Segue o padrão das outras (`armsx2.recTestHooks`, `armsx2.pgo`): opt-in, default
  inalterado, documentada no ponto de uso.
- O APK continua **assinado com a chave de debug**, então instala por cima do que está no aparelho
  sem desinstalar nada. É essa a razão de a propriedade viver no build type de debug e não numa
  variante nova.

**NÃO entra:**

- Mudar o default. Sem a flag, `assembleGithubDebug` produz exatamente o que produzia.
- Assinar release com chave de debug para contornar a instalação. O `CLAUDE.md` registra isso como
  defeito conhecido do build deles, e reproduzi-lo de propósito criaria um APK que parece
  distribuível e não é.
- Refazer, aqui, as medições que isto desbloqueia. Cada uma pertence ao seu próprio registro.

## O custo, que precisa estar escrito

**Um APK não-`debuggable` não aceita `run-as`.** Todo o fluxo de manipulação de preferências usado
nas validações desta série (`run-as ... cat/cat >` sobre `shared_prefs/ARMSX2.xml`) **para de
funcionar** nesse build. Medições que precisem trocar preferências têm de fazê-lo pela interface, ou
voltar ao build `debuggable` para preparar o estado e então trocar.

## Como validar

```powershell
cd platforms/android
./gradlew.bat :app:assembleGithubDebug -Parmsx2.debug.debuggable=false
adb install -r app/build/outputs/apk/github/debug/app-github-debug.apk
```

1. `adb shell dumpsys package come.nanodata.armsx2 | grep -i flags` — **sem** `DEBUGGABLE`.
2. `adb shell dumpsys package come.nanodata.armsx2 | grep -i status` — o dexopt sai de
   `run-from-apk` para um estado compilado de verdade.
3. A instalação acontece **sem desinstalar**, provando que a assinatura continua a de debug.

## Resultado

A propriedade existe, faz o que diz, e o default não mudou — verificado sem depender de aparelho:

```
aapt2 dump badging   com a flag  -> sem a linha `application-debuggable`
aapt2 dump xmltree   com a flag  -> o atributo nao esta no manifesto
manifesto mesclado   sem a flag  -> android:debuggable="true"
```

### A premissa, confirmada em aparelho

O que faltava era mostrar que um APK não-`debuggable` de fato sai de `run-from-apk`. Medido no
**moto g86 5G**, que tem uma build de release instalada:

```
pkgFlags=[ HAS_CODE ALLOW_CLEAR_USER_DATA ALLOW_BACKUP LARGE_HEAP ]   <- sem DEBUGGABLE
arm64: [status=speed-profile] [reason=cmdline] [primary-abi]
```

`speed-profile` é código compilado de verdade. Contra o `run-from-apk` que a
[TASK-0086](TASK-0086-eco-da-busca-nao-recompoe-a-biblioteca.md) mediu no A12 com o APK
`debuggable`, a diferença de regime está demonstrada.

### O limite desta confirmação, e ele é real

**São dois aparelhos, não um A/B controlado.** O `speed-profile` veio do g86 com uma build de
release; o `run-from-apk` veio do A12 com `githubDebug`. As duas pontas diferem em aparelho **e** em
tipo de build.

E o APK que esta task produz — debug **com** a flag — **não foi instalado em aparelho nenhum**:

- no **A12**, que é onde as medições pendentes precisam ser refeitas, ele instalaria (mesma
  assinatura de debug), mas o aparelho não estava conectado;
- no **g86** ele foi recusado com `INSTALL_FAILED_UPDATE_INCOMPATIBLE` — lá há uma build de
  **produção**, e a assinatura não bate. A tentativa foi feita e falhou sem consequência: uma
  instalação recusada não toca em dados.

Ou seja: o mecanismo está pronto e provado no artefato; falta o A12 para usá-lo.
