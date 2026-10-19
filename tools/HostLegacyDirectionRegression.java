package com.dtunnel.bhttptest;

import java.lang.reflect.Field;
import java.util.Arrays;

/** Verifies editable flow counts and collision-free sparse download sequences. */
public final class HostLegacyDirectionRegression {
    public static void main(String[] args) throws Exception {
        requireStaticInt("BATCH_COUNT", 8);

        BhttpBridge defaults = new BhttpBridge(
                "127.0.0.1", 80, null, null);
        verify(defaults, 16, 32);

        BhttpBridge custom = new BhttpBridge(
                "127.0.0.1", 80, null, null, 7, 13);
        verify(custom, 7, 13);

        BhttpBridge clampedLow = new BhttpBridge(
                "127.0.0.1", 80, null, null, 0, 0);
        verify(clampedLow, 1, 1);

        System.out.println("PASS editable slots: default=16/32 custom=7/13 minimum=1/1");
    }

    private static void verify(BhttpBridge bridge, int upload, int download)
            throws Exception {
        requireInstanceInt(bridge, "uploadConnections", upload);
        requireInstanceInt(bridge, "downloadConnections", download);
        long stride = requireInstanceLong(
                bridge, "windowStride", (long) download * 8L);

        Field basesField = BhttpBridge.class.getDeclaredField("windowBases");
        basesField.setAccessible(true);
        long[] actual = (long[]) basesField.get(bridge);
        long[] expected = new long[download];
        for (int index = 0; index < expected.length; index++) {
            expected[index] = index * 8L;
        }
        if (!Arrays.equals(actual, expected)) {
            throw new AssertionError("bases incorretas: " + Arrays.toString(actual));
        }
        for (int first = 0; first < actual.length; first++) {
            for (int second = first + 1; second < actual.length; second++) {
                if (Math.floorMod(actual[first] - actual[second], stride) == 0L) {
                    throw new AssertionError("janelas sobrepostas");
                }
            }
        }
    }

    private static void requireStaticInt(String name, int expected) throws Exception {
        Field field = BhttpBridge.class.getDeclaredField(name);
        field.setAccessible(true);
        int actual = field.getInt(null);
        if (actual != expected) fail(name, actual, expected);
    }

    private static void requireInstanceInt(Object target, String name, int expected)
            throws Exception {
        Field field = BhttpBridge.class.getDeclaredField(name);
        field.setAccessible(true);
        int actual = field.getInt(target);
        if (actual != expected) fail(name, actual, expected);
    }

    private static long requireInstanceLong(Object target, String name, long expected)
            throws Exception {
        Field field = BhttpBridge.class.getDeclaredField(name);
        field.setAccessible(true);
        long actual = field.getLong(target);
        if (actual != expected) fail(name, actual, expected);
        return actual;
    }

    private static void fail(String name, long actual, long expected) {
        throw new AssertionError(name + "=" + actual + ", esperado=" + expected);
    }
}
