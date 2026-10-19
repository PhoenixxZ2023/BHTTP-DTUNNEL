# DTunnel BHTTP VPN Test

Cliente Android VPN baseado no comportamento observado no `bhttp.apk` 4.6.6.
Ele não é apenas um verificador de porta: estabelece uma VPN Android e roteia
o tráfego IPv4 do aparelho através do servidor DTunnel/SSH.

## Caminho de dados

```text
Aplicativos Android
  -> VpnService / TUN 10.10.0.2/24
  -> tun2socks original do bhttp.apk
  -> SOCKS5 local
  -> SSH direct-tcpip
  -> oito janelas Binary BHTTP concorrentes
  -> servidor:80
  -> OpenSSH/PAM
  -> Internet
```

DNS UDP é recebido pelo `tun2socks`, enviado a um proxy DNS local e convertido
para DNS/TCP. A conexão TCP segue pelo mesmo SOCKS/SSH até `1.1.1.1:53`.

## Compatibilidade reproduzida

- probe BHP1 em todos os modos;
- registro vazio `upload seq=0`;
- upload SSH começando novamente em `seq=0`;
- núcleo de transporte restaurado integralmente do Bilola Tunnel 1.0.11;
- batch fixo de 8 slots e chunk máximo de download de 1399 bytes;
- slots de download editáveis de 1 a 32 (padrão 32);
- slots de upload editáveis de 1 a 16 (padrão 16);
- bases e stride são recalculados automaticamente conforme os slots de download;
- reassemblagem numérica compartilhada;
- ACK cumulativo a cada 187 slots;
- autenticação SSH por senha/PAM;
- SOCKS5 usando canais SSH `direct-tcpip`;
- TUN `10.10.0.2/24`, MTU 1500;
- entrega do FD ao tun2socks por LocalSocket e byte `0x2a`;
- keepalive SSH a cada 30 segundos;
- reconnect somente após falha real, com intervalo de 5 segundos.

## Segurança do APK de teste

- A senha é salva nas preferências privadas do aplicativo, conforme solicitado.
- A host key SSH é aceita sem validação, intencionalmente, para diagnóstico.
- O APK é assinado com a chave Android de debug e não deve ser publicado como
  aplicativo de produção.
- O próprio pacote é excluído da VPN e seus sockets BHTTP usam
  `VpnService.protect`, evitando loop de roteamento.
- O socket é ligado a um descritor local antes de `protect()`. Um retorno
  `false` é apenas registrado, não derruba o SSH, pois a exclusão do pacote no
  `VpnService.Builder` já garante o bypass. Esse comportamento acompanha o
  tratamento não fatal observado no `bhttp.apk` de referência.

## Instalação e teste

1. Instale o APK em dispositivo ARM64 ou ARMv7.
2. Abra o app e informe servidor, porta 80, usuário e senha PAM.
3. Toque em **CONECTAR VPN** e aceite a autorização do Android.
4. Aguarde `VPN conectada e roteando tráfego`.
5. Abra um navegador ou verifique o IP público.
6. Deixe conectado para validar keepalive e ausência de reconnects.

O log na tela separa probe, KEX, autenticação, SOCKS, DNS, TUN e tun2socks.

## Build local

O build usa somente ferramentas já instaladas em `/opt/android-sdk`. A pilha
SSH está em `vendor/trilead-runtime.dex`; o tun2socks ARM original está em
`vendor/<abi>/libtun2socks.so`.

```bash
chmod +x build.sh
./build.sh
```

Saída: `dist/Bilola-Tunnel-v1.0.20.apk`.
Pacote fonte: `/root/Bilola-Tunnel-v1.0.20-source.zip`.
