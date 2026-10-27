package com.alfaa.chessoverlay;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;

/**
 * Stockfish engine wrapper. Runs the engine as a subprocess using a bundled
 * binary extracted from app assets/libs (stockfish-android arm64 binary).
 * Speaks UCI: "position fen ..." then "go movetime 1000", parses "bestmove".
 *
 * If no binary is bundled for the device ABI, falls back to a simple built-in
 * one-ply material evaluator so the overlay still shows a legal-ish move
 * rather than nothing.
 */
public class StockfishEngine {

    private Process proc;
    private BufferedReader out;
    private DataOutputStream in;
    private boolean nativeOk = false;

    public interface Logger { void log(String s); }

    /**
     * Try to start stockfish from libDir (jniLibs native lib shipped as
     * libstockfish.so) or app files dir.
     */
    public boolean start(File nativeLibDir, Logger logger) {
        File[] candidates = new File[]{
                new File(nativeLibDir, "libstockfish.so"),
        };
        for (File f : candidates) {
            if (f.exists() && f.canExecute()) {
                if (launch(f.getAbsolutePath(), logger)) return true;
            }
        }
        // try PATH
        if (launch("stockfish", logger)) return true;
        logger.log("Stockfish binary not available; using fallback evaluator");
        return false;
    }

    private boolean launch(String path, Logger logger) {
        try {
            ProcessBuilder pb = new ProcessBuilder(path);
            pb.redirectErrorStream(true);
            proc = pb.start();
            out = new BufferedReader(new InputStreamReader(proc.getInputStream()));
            in = new DataOutputStream(proc.getOutputStream());
            // verify UCI handshake
            send("uci");
            String line;
            long deadline = System.currentTimeMillis() + 3000;
            while (System.currentTimeMillis() < deadline) {
                line = out.readLine();
                if (line == null) break;
                if (line.contains("uciok")) {
                    nativeOk = true;
                    send("setoption name Skill Level value 18");
                    send("isready");
                    waitReady(2000);
                    return true;
                }
            }
            proc.destroy();
        } catch (IOException e) {
            logger.log("launch failed: " + e.getMessage());
        }
        return false;
    }

    private void waitReady(long timeoutMs) {
        try {
            send("isready");
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                String l = out.readLine();
                if (l == null) return;
                if (l.contains("readyok")) return;
            }
        } catch (IOException ignored) {}
    }

    private void send(String cmd) {
        try {
            in.writeBytes(cmd + "\n");
            in.flush();
        } catch (IOException ignored) {}
    }

    /** Get best move for a FEN within movetime ms. Returns e.g. "e2e4" or null. */
    public String bestMove(String fen, int movetimeMs) {
        if (nativeOk) {
            synchronized (this) {
                send("position fen " + fen);
                send("go movetime " + movetimeMs);
                long deadline = System.currentTimeMillis() + movetimeMs + 10000;
                try {
                    while (System.currentTimeMillis() < deadline) {
                        String line = out.readLine();
                        if (line == null) break;
                        if (line.startsWith("bestmove")) {
                            return line.split("\\s+")[1];
                        }
                    }
                } catch (IOException ignored) {}
                return null;
            }
        }
        return FallbackEvaluator.bestMove(fen);
    }

    public void stop() {
        try { send("quit"); } catch (Exception ignored) {}
        if (proc != null) proc.destroy();
        nativeOk = false;
    }
}
