package com.dtunnel.bhttptest;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.SocketException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Converts DNS/UDP from tun2socks into DNS/TCP through the SSH SOCKS proxy. */
public final class DnsProxy implements AutoCloseable {
    public interface Logger { void log(String message); }

    private final int socksPort;
    private final String upstreamDns;
    private final Logger logger;
    private final DatagramSocket udp;
    private final ExecutorService workers = Executors.newFixedThreadPool(4);
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicBoolean firstTunQueryLogged = new AtomicBoolean(false);
    private final Thread receiver;

    public DnsProxy(int socksPort, String upstreamDns, Logger logger) throws Exception {
        this.socksPort = socksPort;
        this.upstreamDns = upstreamDns;
        this.logger = logger;
        this.udp = new DatagramSocket(new InetSocketAddress(
                InetAddress.getByName("127.0.0.1"), 0));
        this.receiver = new Thread(this::receiveLoop, "DT-DNS-receiver");
        this.receiver.setDaemon(true);
        this.receiver.start();
    }

    public int getPort() { return udp.getLocalPort(); }

    /** Verifies DNS/TCP through the same SOCKS/direct-tcpip path used by VPN DNS. */
    public void testUpstream() throws Exception {
        byte[] query = new byte[] {
                0x42, 0x49, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00,
                0x00, 0x00, 0x00, 0x00,
                0x07, 'e', 'x', 'a', 'm', 'p', 'l', 'e',
                0x03, 'c', 'o', 'm', 0x00,
                0x00, 0x01, 0x00, 0x01
        };
        byte[] response = queryTcp(query);
        if (response.length < 12 || response[0] != 0x42 || response[1] != 0x49) {
            throw new IOException("resposta DNS de teste inválida");
        }
    }

    private void receiveLoop() {
        while (running.get()) {
            try {
                byte[] storage = new byte[4096];
                DatagramPacket request = new DatagramPacket(storage, storage.length);
                udp.receive(request);
                byte[] query = new byte[request.getLength()];
                System.arraycopy(request.getData(), request.getOffset(), query, 0, query.length);
                if (firstTunQueryLogged.compareAndSet(false, true)) {
                    logger.log("Primeira consulta DNS recebida do TUN: "
                            + query.length + "B");
                }
                InetAddress clientAddress = request.getAddress();
                int clientPort = request.getPort();
                workers.execute(() -> resolve(query, clientAddress, clientPort));
            } catch (SocketException error) {
                if (running.get()) logger.log("DNS socket: " + error.getMessage());
                return;
            } catch (Throwable error) {
                logger.log("DNS receive: " + concise(error));
            }
        }
    }

    private void resolve(byte[] query, InetAddress clientAddress, int clientPort) {
        try {
            byte[] response = queryTcp(query);
            DatagramPacket reply = new DatagramPacket(
                    response, response.length, clientAddress, clientPort);
            synchronized (udp) { udp.send(reply); }
        } catch (Throwable error) {
            logger.log("DNS query falhou: " + concise(error));
        }
    }

    private byte[] queryTcp(byte[] query) throws Exception {
        Proxy proxy = new Proxy(Proxy.Type.SOCKS,
                new InetSocketAddress("127.0.0.1", socksPort));
        try (Socket socket = new Socket(proxy)) {
            socket.connect(new InetSocketAddress(upstreamDns, 53), 8000);
            socket.setSoTimeout(8000);
            DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            output.writeShort(query.length);
            output.write(query);
            output.flush();

            DataInputStream input = new DataInputStream(socket.getInputStream());
            int length = input.readUnsignedShort();
            if (length < 12 || length > 65535) throw new IOException("DNS length " + length);
            byte[] response = new byte[length];
            input.readFully(response);
            return response;
        }
    }

    @Override
    public void close() {
        if (!running.compareAndSet(true, false)) return;
        udp.close();
        receiver.interrupt();
        workers.shutdownNow();
    }

    private static String concise(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }
}
