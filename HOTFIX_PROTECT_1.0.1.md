# Hotfix Android 1.0.1 — loop em `VpnService.protect()`

Data: 19 de agosto de 2026

## Sintoma confirmado no aparelho

O cliente concluía a criação do SID, mas reiniciava antes do probe BHP1:

```text
BHTTP SID ...
Aviso: protect(socket) retornou false
Conexão perdida: VpnService.protect falhou para socket BHTTP
Reconectando em 5s
```

## Causa exata

O retorno booleano de `VpnService.protect(Socket)` foi transformado pelo cliente
1.0.0 em uma `IOException` fatal. Isso encerrava o bridge no primeiro socket e o
supervisor executava corretamente a política de reconexão, criando o loop de
cinco segundos.

Essa política não reproduzia o aplicativo de referência. No `bhttp.apk`, a
falha de `protect()` é usada para diagnóstico, mas o fluxo continua até a
criação da interface. Além disso, o cliente já chama
`Builder.addDisallowedApplication(getPackageName())`; portanto, todos os
sockets do próprio processo ficam fora da VPN e não podem recursar para o TUN.

O socket BHTTP também era passado ainda não ligado. Dependendo da implementação
Android, isso pode fazer `protect()` observar um descritor ainda não
materializado ou ocorrer antes da interface VPN estar estabelecida.

## Correção aplicada

1. Executar `socket.bind(new InetSocketAddress(0))` antes de `protect()`.
2. Manter `protect()` como proteção adicional.
3. Não transformar `false` em falha de transporte.
4. Registrar o aviso uma única vez para evitar spam.
5. Manter a exclusão do pacote como regra autoritativa contra recursão.

O erro antigo `VpnService.protect falhou para socket BHTTP` foi removido do DEX.

## Comportamento esperado

Em aparelhos onde `protect()` retornar `false`, o log poderá mostrar uma vez:

```text
Aviso: protect(socket) retornou false; continuando porque o pacote está excluído da VPN
```

Depois disso, o fluxo deve prosseguir para:

```text
Probe BHP1: todos os modos aceitos
Registro BHTTP seq=0 aceito
Negociando SSH...
```

Se houver uma falha posterior, ela será uma falha real de BHTTP, SSH,
autenticação, tun2socks ou rede, e não mais o retorno booleano de `protect()`.

## Validação do artefato

- APK versionCode 2 / versionName 1.0.1;
- compilação Java/D8 concluída;
- assinatura v1, v2 e v3 válida;
- `zipalign -c 4` aprovado;
- integridade ZIP aprovada;
- DEX decodificado e confirmado com `Socket.bind()` antes de `protect()`;
- DEX confirmado com `addDisallowedApplication()`;
- mensagem/throw fatal antiga ausente.
