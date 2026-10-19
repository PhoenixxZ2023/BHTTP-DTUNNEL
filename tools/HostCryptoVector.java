package com.dtunnel.bhttptest;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Cross-checks the Java cipher against the Python/server reference vector. */
public final class HostCryptoVector {
    public static void main(String[] args) throws Exception {
        BhttpBridge bridge = new BhttpBridge("127.0.0.1", 80,
                new BhttpBridge.Listener() {
                    public void log(String message) {}
                    public void fatal(Throwable error) {}
                }, socket -> true);
        Field sidField = BhttpBridge.class.getDeclaredField("sid");
        sidField.setAccessible(true);
        byte[] sid = (byte[]) sidField.get(bridge);
        for (int index = 0; index < sid.length; index++) sid[index] = (byte) index;

        Method crypt = BhttpBridge.class.getDeclaredMethod(
                "crypt", byte[].class, int.class, long.class, boolean.class);
        crypt.setAccessible(true);
        byte[] clear = new byte[]{'B','H','P','1',1,3,0,0,1,0};
        byte[] encrypted = (byte[]) crypt.invoke(bridge, clear, 0, 0L, false);
        StringBuilder actual = new StringBuilder();
        for (byte value : encrypted) actual.append(String.format("%02x", value & 0xff));
        String expected = "1185931614521a40b981";
        if (!expected.equals(actual.toString())) {
            throw new AssertionError("expected=" + expected + " actual=" + actual);
        }
        System.out.println("PASS BHTTP cipher vector " + actual);
    }
}

