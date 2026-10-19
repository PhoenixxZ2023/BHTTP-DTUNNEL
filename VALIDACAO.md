# Validação do APK 1.0.1

Data: 19 de agosto de 2026

## Resultado do build

- hotfix Android: o socket BHTTP agora recebe `bind()` antes de `protect()`;
- retorno `false` de `VpnService.protect()` não é mais fatal, pois o pacote já
  está excluído do TUN por `addDisallowedApplication()`;
- o aviso de `protect()` é emitido apenas uma vez, impedindo spam de log;

- Java 8 compilado sem erros;
- D8 gerou o DEX principal sem referências ausentes;
- runtime Trilead incluído como `classes2.dex`;
- `apktool` decodificou os dois DEX sem erro;
- classes `MainActivity`, `DtunnelVpnService`, `BhttpBridge` e `DnsProxy`
  confirmadas no DEX principal;
- `com.trilead.ssh2.Connection` e APIs usadas confirmadas no DEX secundário;
- `libtun2socks.so` ARM64 e ARMv7 presentes e marcados como executáveis;
- `zipalign -c 4` aprovado;
- teste completo de integridade ZIP do APK aprovado;
- assinatura APK v1, v2 e v3 aprovada;
- manifesto confirma `BIND_VPN_SERVICE`, serviço não exportado,
  `extractNativeLibs=true` e tráfego cleartext para BHTTP:80;
- vetor criptográfico BHTTP Java/Python aprovado:
  `1185931614521a40b981`.

## Hash final

```text
863f59c42f823ef57ff6a5c59b00f47e3d922d46e4f9076cb35a98ecc8ecac4b
DTunnel-BHTTP-VPN-Test-v1.0.1.apk
```

## Validação funcional relacionada

O servidor e o mesmo algoritmo BHTTP foram anteriormente validados com:

- 10/10 conexões SSH completas com PAM e comando remoto;
- 5/5 canais SSH `direct-tcpip`;
- zero falhas de KEX/autenticação nesses testes.

A execução do bridge Java contra a porta local foi tentada neste ambiente, mas
o sandbox da sessão bloqueou criação de sockets com `Operation not permitted`.
Isso não é uma resposta do servidor. Não havia ADB/emulador conectado para
executar o `VpnService`; portanto, instalação e tráfego TUN ainda precisam ser
confirmados em um aparelho ARM real.

## Limites desta versão de teste

- IPv4 apenas;
- TCP completo via SOCKS/SSH;
- DNS UDP convertido para DNS/TCP;
- UDP genérico/QUIC não é encaminhado sem um servidor BadVPN UDPGW;
- host key aceita sem validação;
- assinatura de debug;
- ABIs: `arm64-v8a` e `armeabi-v7a`.
