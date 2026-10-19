package com.dtunnel.bhttptest;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Host-side regression entry point for the exact BHTTP bridge used by Android. */
public final class HostBridgeMain {
    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "127.0.0.1";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 80;
        CountDownLatch failed = new CountDownLatch(1);
        BhttpBridge bridge = new BhttpBridge(host, port, new BhttpBridge.Listener() {
            @Override public void log(String message) {
                System.out.println("LOG " + message);
                System.out.flush();
            }
            @Override public void fatal(Throwable error) {
                System.out.println("FATAL " + error);
                System.out.flush();
                failed.countDown();
            }
        }, socket -> true);
        int localPort = bridge.start();
        System.out.println("LOCAL_PORT=" + localPort);
        System.out.flush();
        failed.await(120, TimeUnit.SECONDS);
        bridge.close();
    }
}

