# Bug: o toggle de log será silenciosamente revertido no próximo bump do perfil de desempenho

- **Detectado em:** 2026-08-24 17:10 (leitura de código durante teste no Galaxy A12)
- **Origem:** `main.cpp::MigrateAndroidPerformanceDefaults`
- **Errors (serviço):** nenhum — defeito latente, ainda não observado em campo
- **Classe:** fail (latente)
- **Reincidência:** primeira vez
- **Feature:** [FEAT-0001](../../features/FEAT-0001-sync-upstream-oficial.md)
- **Tasks que o resolvem:** — (a definir)

## Correção de rumo — o que eu observei NÃO era este bug

Durante o teste no A12 eu editei `RecordAndroidLog = true` e `EnableSystemConsole = true` direto no
`PCSX2-Android.ini` por `adb`, abri um jogo e vi os dois de volta em `false`. Concluí que o perfil de
dispositivo revertia a escolha do usuário em todo boot. **Estava errado**, e a verificação no
aparelho derrubou a hipótese:

- `AndroidPerformanceProfileVersion` no aparelho é `7`, igual a `ANDROID_PERFORMANCE_PROFILE_VERSION`
  ([main.cpp:128](../../../app/src/main/cpp/main.cpp#L128)), então `MigrateAndroidPerformanceDefaults`
  retorna logo no começo e as linhas 249–252 **não rodaram**.
- `ApplyAndroidPerformanceDefaults` ([main.cpp:134](../../../app/src/main/cpp/main.cpp#L134)), que zera
  as quatro chaves, só é chamada no ramo de INI vazio (linhas 724 e 819). O INI não estava vazio.

O que reverteu foi o **próprio app em execução**: eu editei um arquivo cujo conteúdo o processo já
tinha em memória, e o `Save()` seguinte reescreveu o arquivo inteiro a partir da memória. Erro de
método de teste, não defeito do produto. O teste válido é `am force-stop` **antes** de editar o INI,
ou usar o switch das Configurações, que é o caminho real do usuário.

## O defeito que sobra, este sim real

Mesmo assim, a leitura do código expôs uma armadilha latente em
[main.cpp:249-252](../../../app/src/main/cpp/main.cpp#L249):

```cpp
set_bool_if_missing_or("Logging", "EnableSystemConsole", true, false);
set_bool_if_missing_or("Logging", "EnableFileLogging",   true, false);
set_bool_if_missing_or("Logging", "EnableVerbose",       true, false);
set_bool_if_missing_or("Logging", "RecordAndroidLog",    true, false);
```

O lambda ([main.cpp:220](../../../app/src/main/cpp/main.cpp#L220)) grava `new_value` quando a chave está
ausente **ou quando o valor atual é igual a `old_value`**. Com `old_value = true` e
`new_value = false`, isso significa: *se o log estiver ligado, desligue*.

Hoje é inofensivo porque a migração está travada em `profile_version >= 7`. Mas ela roda de novo a
cada bump de `ANDROID_PERFORMANCE_PROFILE_VERSION` — e o próximo bump vai **desligar o log de todo
usuário que o tinha ligado**, exatamente quando estivermos pedindo log para diagnosticar algo.

O mesmo padrão vale para as outras dez chaves que usam essa variante: um bump de perfil reverte
qualquer usuário cuja configuração coincida com o `old_value`.

## Como reproduzir

1. Ligar "gravar logs" nas Configurações e confirmar `RecordAndroidLog = true` no INI com o app parado.
2. Incrementar `ANDROID_PERFORMANCE_PROFILE_VERSION` (simula a próxima release que ajustar o perfil).
3. Abrir o app e reler o INI: voltou para `false`.

## Correção proposta

Preferências que o usuário controla explicitamente não devem participar da migração de perfil de
desempenho. As quatro chaves de `Logging` devem sair de `set_bool_if_missing_or` e passar a só
receber valor quando ausentes. Para as demais chaves, decidir caso a caso se o objetivo é "corrigir
um default antigo" (legítimo) ou "reverter escolha do usuário" (não é).

## Pendência de verificação separada

Continua **não verificado** se o switch de log das Configurações sobrevive a um restart. É
pré-requisito da [TASK-0004](../../task/TASK-0004-bloco-b2-log-boot-gs.md) e precisa de um teste
correto: `force-stop`, ligar pelo próprio app, reabrir, conferir se `<DataRoot>/logs/androidlog.txt`
é criado.
