package com.gohj99.galaxywatchhelper.utils;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Bounded, read-only service query. Retains received output even on failure/timeout. */
final class WearVpnServiceCheck {
    static final class Result {
        final int exitCode;
        final String output;

        Result(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }
    }

    static Result run() {
        return capture(Arrays.asList("su", "-c", "service check vpn_management"), 15_000);
    }

    // Kept free of Android dependencies so output/error handling can be verified locally.
    static Result capture(List<String> command, long timeoutMillis) {
        StringBuffer output = new StringBuffer();
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            InputStream stream = process.getInputStream();
            Thread reader = new Thread(() -> {
                try (InputStreamReader input = new InputStreamReader(stream)) {
                    char[] buffer = new char[4096];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        output.append(buffer, 0, count);
                    }
                } catch (IOException error) {
                    output.append("\nOutput read failed: ").append(error);
                }
            }, "GWWearVpnServiceCheck");
            reader.setDaemon(true);
            reader.start();
            boolean completed = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS);
            if (!completed) process.destroyForcibly();
            reader.join(2_000);
            if (reader.isAlive()) {
                output.append("\nOutput capture incomplete: stream did not close in time.");
                // Some process streams block close() behind a pending read.
                // Keep that cleanup off the bounded query's calling thread.
                Thread closer = new Thread(() -> {
                    try {
                        stream.close();
                    } catch (IOException error) {
                        output.append("\nOutput close failed: ").append(error);
                    }
                }, "GWWearVpnOutputClose");
                closer.setDaemon(true);
                closer.start();
            }
            if (!completed) output.append("\nCommand timed out; received output retained.");
            return new Result(completed ? process.exitValue() : -1, output.toString());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            output.append("\nCommand interrupted: ").append(error);
            return new Result(-1, output.toString());
        } catch (Exception error) {
            output.append("\nCommand failed: ").append(error);
            return new Result(-1, output.toString());
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
        }
    }
}
