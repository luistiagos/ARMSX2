# Bug (investigação): ANR — `HIDDeviceUSB.close()` roda no `onReceive` do BroadcastReceiver (UI thread)

- **Detectado em:** 2026-07-20 02:13 (telemetria de produção)
- **Origem:** telemetria `armsx2/anr`
  (`UsbDeviceConnection.java::android.hardware.usb.UsbDeviceConnection.native_release_interface`)
- **Errors (serviço):** 537 (**1 ocorrência**)
- **Classe:** fail (ANR — `main thread unresponsive >5000ms`)
- **Reincidência:** primeira vez; Android 15 (sdk 35), `app_version 1.0.8`
- **Confiança:** baixa (ocorrência única) — registrado como **investigação**, não como fix pronto

## Sintoma

```
at android.hardware.usb.UsbDeviceConnection.native_release_interface(Native Method)
...
at kr.co.iefriends.pcsx2.hid.HIDDeviceUSB.close(HIDDeviceUSB.java:272)
at kr.co.iefriends.pcsx2.hid.HIDDeviceUSB.open(HIDDeviceUSB.java:148)
at kr.co.iefriends.pcsx2.hid.HIDDeviceManager.handleUsbDevicePermission(HIDDeviceManager.java:333)
at kr.co.iefriends.pcsx2.hid.HIDDeviceManager$1.onReceive(HIDDeviceManager.java:75)
```

O usuário concedeu permissão a um controle USB; o `open()` falhou por endpoint ausente e chamou
`close()` no caminho de erro, que travou a main thread.

## Causa raiz (provável — confirmada por leitura, não por repro)

`HIDDeviceManager$1.onReceive` roda na **UI thread** (BroadcastReceiver registrado sem handler
próprio). Dali sai a cadeia inteira até
[`HIDDeviceUSB.close()`](../../../app/src/main/java/kr/co/iefriends/pcsx2/hid/HIDDeviceUSB.java#L257),
que tem **dois** pontos de bloqueio sem timeout:

```java
public void close() {
    mRunning = false;
    if (mInputThread != null) {
        while (mInputThread.isAlive()) {     // laço sem limite
            mInputThread.interrupt();
            try { mInputThread.join(); }      // join sem timeout
            catch (InterruptedException e) { /* Keep trying until we're done */ }
        }
        mInputThread = null;
    }
    if (mConnection != null) {
        UsbInterface iface = mDevice.getInterface(mInterfaceIndex);
        mConnection.releaseInterface(iface);   // :272 — bloqueou aqui
        mConnection.close();
        mConnection = null;
    }
}
```

O frame do ANR é `releaseInterface` → `native_release_interface`, que pode bloquear enquanto houver
transferência USB pendente. O caminho chegou aqui via
[`open()` linha 148](../../../app/src/main/java/kr/co/iefriends/pcsx2/hid/HIDDeviceUSB.java#L148) —
o `close()` de cleanup quando `mInputEndpoint`/`mOutputEndpoint` vêm nulos.

Esse arquivo é código herdado do SDL (`HIDDeviceUSB`/`HIDDeviceManager`), o que explica o padrão
`while (isAlive()) { interrupt(); join(); }` — mas o problema é nosso: a chamada acontece na UI
thread.

## Como reproduzir

Não reproduzido. Hipótese: conectar um dispositivo USB que se enumere como HID mas **sem** os
endpoints IN/OUT esperados (hub, adaptador, dock, ou controle em modo errado), conceder a permissão
no diálogo e observar o `open()` cair no caminho de erro.

## Próximos passos

1. Mover `handleUsbDevicePermission` (e o `open()`/`close()` que ele dispara) para fora da UI
   thread — registrar o `BroadcastReceiver` com um `Handler` de background, ou despachar o trabalho.
2. Dar timeout ao `join()` em `close()` em vez do laço `while (isAlive())` infinito.
3. Como é ocorrência única, **priorizar abaixo** dos outros ANRs — mas confirmar se reaparece na
   próxima triagem antes de fechar.

## Resolução (CONFIRMADA e corrigida — 2026-08-19)

1. **Timeout Delimitado no `InputThread.join` e Liberação Segura (`HIDDeviceUSB.java`):**
   O loop infinito de espera foi substituído por uma espera com deadline (timeout de 250ms por iteração e limite total de 1s). Além disso, `releaseInterface` e `mConnection.close()` foram envolvidos em blocos `try/catch` para evitar bloqueios ou exceções durante desmontagem de interfaces USB ocupadas.
2. **Despacho Assíncrono de Permissão e Desconexão (`HIDDeviceManager.java`):**
   Criado o `mUsbExecutor` (`Executors.newSingleThreadExecutor()`). As operações de permissão (`handleUsbDevicePermission`), abertura de dispositivo (`device.open()`) e desconexão (`handleUsbDeviceDetached`) agora executam em background sem reter o thread principal do `BroadcastReceiver`.

Status: **Corrigido no código local (2026-08-19).**

---

> **Rastreabilidade:** `sem-task-legado` — bug fechado antes de o sistema de tasks existir (ver [`docs/README.md`](../../README.md)). Não há task associada.
