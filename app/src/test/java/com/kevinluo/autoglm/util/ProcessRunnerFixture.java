package com.kevinluo.autoglm.util;

/** Real child process fixture: independent pipe producers reproduce sequential-read deadlocks. */
public final class ProcessRunnerFixture {
    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "normal":
                System.out.print("hello\n");
                System.err.print("warning\n");
                System.exit(7);
                break;
            case "sleep":
                System.out.flush();
                Thread.sleep(10000);
                break;
            case "flood":
            case "limit":
                final int count = args[0].equals("flood") ? 200000 : 2000000;
                Thread stdout = new Thread(() -> write(System.out, count, 'o'));
                Thread stderr = new Thread(() -> write(System.err, count, 'e'));
                stdout.start();
                stderr.start();
                stdout.join();
                stderr.join();
                break;
            default:
                throw new IllegalArgumentException("Unknown fixture mode");
        }
    }

    private static void write(java.io.OutputStream stream, int count, char letter) {
        byte[] bytes = new byte[4096];
        java.util.Arrays.fill(bytes, (byte) letter);
        try {
            while (count > 0) {
                int size = Math.min(count, bytes.length);
                stream.write(bytes, 0, size);
                count -= size;
            }
            stream.flush();
        } catch (java.io.IOException ignored) {
            // The parent deliberately closes the process on timeout/output budget exhaustion.
        }
    }
}
