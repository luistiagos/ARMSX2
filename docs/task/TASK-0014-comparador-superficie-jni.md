# TASK-0014: tornar a comparação da superfície JNI um comando, não uma contagem à mão

- **Status:** concluída
- **Criada em:** 2026-08-26
- **Concluída em:** 2026-08-26
- **Feature:** [FEAT-0001](../features/FEAT-0001-sync-upstream-oficial.md)
- **Bugs que resolve:** nenhum
- **Commit:** — (o vínculo é o prefixo `TASK-0014:` no assunto)
- **Revertida por:** —
- **Publicado em:** — (não altera o aplicativo)

## Objetivo

`scripts/compare_jni_surface.py`: comparar a superfície JNI `Java_kr_co_iefriends_pcsx2_*` entre a
nossa `main.cpp` e a `native-lib.cpp` do upstream, por **nome e assinatura**, com um comando.

## Motivação

O número muda, e mudou de forma que importa. Medido contra `be72a8e1eb` (18/08) dava **31 idênticos
e 2 divergentes**. Contra `662b114168` (26/08), oito dias depois, dá **30 e 3** — e a divergência
nova é `getGameCRC`, que retorna `jint` do nosso lado e `jstring` do deles.

Essa é a pior forma da divergência. **JNI liga por nome**: mesmo nome com tipos diferentes compila,
linka e roda. O `int` declarado no Java receberia os 32 bits baixos de um ponteiro `jstring` — não
crasha, produz um CRC errado em silêncio, e o CRC alimenta busca de capas, overrides de GameDB e a
chave de decisão do `GraphicsHealthMonitor`.

Uma contagem feita à mão numa sessão envelhece sem avisar. Um comando não.

## Escopo

**Entra:**
- `scripts/compare_jni_surface.py`. Extrai declarações `JNIEXPORT ... JNICALL Java_..._X(...)`,
  normaliza os parâmetros para tipos (descartando nomes e `const`), descarta os dois primeiros
  (`JNIEnv*`, `jclass`/`jobject`) e compara.

**NÃO entra:**
- Portão de CI. É medição, não regra — sai sempre com código 0. Transformá-lo em portão exigiria
  fixar um ref do upstream, e o ponto é justamente que o ref se move.
- Comparar semântica. `clearAchievementsHostOverride` tem a mesma aridade nos dois lados sob outra
  leitura e faz coisas diferentes; nenhum script alcança isso.

## Como validar

```
python scripts/compare_jni_surface.py app/src/main/cpp/main.cpp \
    D:/projects/play2/ARMSX2-upstream-spike/platforms/android/app/src/main/cpp/native-lib.cpp
```

Deve reproduzir 56 / 143 / 33 comuns / 30 idênticos / 3 divergentes / 23 só nossos / 110 só deles —
os mesmos números do [spike](../spike-transplante-upstream-2026-08-26.md). **Executado, confere.**

## Resultado

Entregue. O achado que a task pagou por si mesma para encontrar é a divergência de `getGameCRC`, que
não estava em nenhum registro anterior.
