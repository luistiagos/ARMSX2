# Bug: cópia de assets assíncrona reescreve os shaders enquanto o emulador os lê

- **Detectado em:** 2026-08-21 03:40 (relato de cliente)
- **Origem:** `MainActivity.onCreate` + `DataDirectoryManager.copyFile`
- **Errors (serviço):** nenhum — não é crash, e o erro do GS era silencioso (ver [[gs-tela-preta-silenciosa-sem-diagnostico-a07]])
- **Classe:** fail (race condition)
- **Reincidência:** introduzido na 1.0.17

## Sintoma

Cliente jogava normalmente em 18/08, atualizou, e a partir de 20/08 passou a tomar tela preta ao
iniciar o jogo. **Trocar para OpenGL não resolveu** — foi essa informação que derrubou a hipótese
de renderer e levou à causa real.

## Causa raiz (confirmada no código)

Na 1.0.17 a cópia de assets saiu da thread de UI e passou a rodar concorrente com o boot
([MainActivity.onCreate](../../../app/src/main/java/kr/co/iefriends/pcsx2/activities/MainActivity.java#L433)):

```java
Executors.newSingleThreadExecutor().execute(() -> {
    DataDirectoryManager.copyAssetAll(getApplicationContext(), "resources");
});
Initialize();          // <-- antes, a copia terminava aqui
```

O que essa cópia faz com os shaders, em
[DataDirectoryManager.copyFile](../../../app/src/main/java/kr/co/iefriends/pcsx2/utils/DataDirectoryManager.java#L363):

```java
boolean exists = outFile.exists();
if (srcFile.contains("shaders")) {
    exists = false;                      // shaders sao SEMPRE recopiados
}
if (!exists) {
    os = new FileOutputStream(outFile);  // TRUNCA o arquivo para zero
    byte[] buffer = new byte[1024];      // e so reenche em ~8900 escritas de 1KB
```

E do outro lado, quem lê esses mesmos arquivos é o emulador, durante a criação do device gráfico
([GSDevice::ReadShaderSource](../../../app/src/main/cpp/pcsx2/GS/Renderers/Common/GSDevice.cpp#L315)):

```cpp
return FileSystem::ReadFileToString(Path::Combine(EmuFolders::Resources, filename).c_str());
```

`EmuFolders::Resources` é exatamente `<DataRoot>/resources`, o destino do `copyAssetAll`. Os dois
backends leem de lá na criação do device — [GSDeviceOGL](../../../app/src/main/cpp/pcsx2/GS/Renderers/OpenGL/GSDeviceOGL.cpp#L344)
(`shaders/opengl/*.glsl`) e [GSDeviceVK](../../../app/src/main/cpp/pcsx2/GS/Renderers/Vulkan/GSDeviceVK.cpp#L2176)
(`shaders/vulkan/*.glsl`).

Resultado: o emulador lê um shader vazio ou pela metade → compilação falha → criação do device
falha → tela preta. São 8,7 MB / 41 arquivos, com buffer de 1 KB, então a janela de corrupção é
larga. E como os shaders são recopiados **em toda abertura**, a corrida se repete sempre.

Isto explica as três coisas que nenhuma hipótese de renderer explicava:

| Sintoma | Por quê |
|---|---|
| Falha nos **dois** renderers | Ambos leem shaders do mesmo diretório sendo reescrito |
| Funcionava dia 18, quebrou depois | A cópia assíncrona é nova na 1.0.17 |
| Intermitente, só alguns aparelhos | É corrida: depende da velocidade do storage |

`Host::EnsureResourceSubdirectory` ([Host.cpp:41](../../../app/src/main/cpp/pcsx2/Host.cpp#L41)) não
protege: só checa se o **diretório** existe, nunca a integridade dos arquivos.

## Correção aplicada

Duas camadas, porque cada uma cobre um buraco diferente:

1. **Escrita atômica** — `copyFile` grava num `.part` e faz `renameTo` por cima do destino. Um
   leitor passa a ver ou o arquivo antigo completo ou o novo completo, nunca um truncado. Cobre
   também o `copyAssetAll` disparado pelo lado nativo (`ensureResourceSubdirectoryCopied`), que
   pode rodar a qualquer momento. Buffer de 1 KB → 64 KB de quebra (8900 syscalls → 136).
2. **Portão no boot** — `startAssetCopyAsync()` / `awaitAssetsReady(30s)`. A `onCreate` dispara a
   cópia sem travar a UI, e a **thread de emulação** espera por ela antes de `runVMThread`. É a
   thread certa: não é a de UI, então não há risco de ANR. A camada 1 sozinha não bastaria no
   primeiro boot, quando o arquivo pode simplesmente ainda não existir.

Sem `fsync`: o leitor com quem competimos é outra thread do mesmo processo, então basta os dados
chegarem ao kernel. Um fsync por arquivo somaria dezenas de ms num caminho que agora é crítico.

## Próximos passos

1. Publicar e confirmar com o cliente que reportou (jogava dia 18, quebrou dia 20).
2. **Reavaliar o relato do A07** ([[gs-tela-preta-silenciosa-sem-diagnostico-a07]]) — é o mesmo
   sintoma, na mesma versão, e a causa pode ser esta, não o renderer.
3. Considerar parar de recopiar shaders quando o `versionCode` não mudou; hoje são 31 arquivos
   reescritos em toda abertura sem necessidade.
