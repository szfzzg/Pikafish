package com.pikafish.xiangqi;

import android.os.Handler;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 通过 UCI 协议驱动 Pikafish 可执行文件。
 */
public class UciEngine {

    public interface Listener {
        void onReady();

        void onError(String message);
    }

    public interface MoveListener {
        void onInfo(Info info);

        void onBest(String move);
    }

    public interface AnalysisListener {
        void onInfo(Info info);

        void onDone(List<Info> lines);
    }

    public static class Info {
        public int depth;
        public int multipv = 1;
        public String scoreType;
        public int scoreValue;
        public String pv = "";
    }

    private static final Object EOF = new Object();

    private final BlockingQueue<Object> lines = new LinkedBlockingQueue<>();
    private final BlockingQueue<Runnable> jobs = new LinkedBlockingQueue<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<String> recent = Collections.synchronizedList(new ArrayList<>());
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private Process process;
    private BufferedWriter writer;
    private Listener listener;

    public void start(String enginePath, String netPath, Listener listener) {
        this.listener = listener;
        Thread worker = new Thread(() -> runEngine(enginePath, netPath), "pikafish-engine");
        worker.start();
    }

    public void newGame() {
        enqueue(() -> {
            send("ucinewgame");
            send("isready");
            while (!"readyok".equals(nextLine(10))) {
                // 等待引擎清空状态
            }
        });
    }

    public void search(String fen, int movetimeMs, MoveListener listener) {
        enqueue(() -> {
            send("setoption name MultiPV value 1");
            send("position fen " + fen);
            send("go movetime " + movetimeMs);

            long timeout = movetimeMs / 1000L + 20;
            int lastDepth = -1;
            while (true) {
                String line = nextLine(timeout);
                if (line.startsWith("info ")) {
                    Info info = parseInfo(line);
                    if (info != null && info.depth != lastDepth) {
                        lastDepth = info.depth;
                        final Info out = info;
                        main.post(() -> listener.onInfo(out));
                    }
                } else if (line.startsWith("bestmove")) {
                    String[] parts = line.split("\\s+");
                    final String best = parts.length > 1 ? parts[1] : "(none)";
                    main.post(() -> listener.onBest(best));
                    return;
                }
            }
        });
    }

    public void analyze(String fen, int movetimeMs, int multipv, AnalysisListener listener) {
        enqueue(() -> {
            send("setoption name MultiPV value " + multipv);
            send("position fen " + fen);
            send("go movetime " + movetimeMs);

            Map<Integer, Info> results = new TreeMap<>();
            long timeout = movetimeMs / 1000L + 20;
            int lastDepth = -1;
            while (true) {
                String line = nextLine(timeout);
                if (line.startsWith("info ")) {
                    Info info = parseInfo(line);
                    if (info == null) {
                        continue;
                    }
                    results.put(info.multipv, info);
                    if (info.multipv == 1 && info.depth != lastDepth) {
                        lastDepth = info.depth;
                        final Info out = info;
                        main.post(() -> listener.onInfo(out));
                    }
                } else if (line.startsWith("bestmove")) {
                    final List<Info> out = new ArrayList<>(results.values());
                    out.sort(Comparator.comparingInt(a -> a.multipv));
                    main.post(() -> listener.onDone(out));
                    return;
                }
            }
        });
    }

    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        jobs.add(() -> {
        });
        try {
            if (writer != null) {
                writer.write("quit\n");
                writer.flush();
            }
        } catch (Exception ignored) {
            // 引擎可能已经退出
        }
        final Process p = process;
        if (p != null) {
            new Thread(() -> {
                try {
                    p.waitFor(1, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                if (p.isAlive()) {
                    p.destroy();
                }
            }, "pikafish-reaper").start();
        }
    }

    // ---------------------------------------------------------------------
    // 内部实现
    // ---------------------------------------------------------------------

    private void enqueue(Runnable job) {
        if (!closed.get()) {
            jobs.add(job);
        }
    }

    private void runEngine(String enginePath, String netPath) {
        try {
            if (closed.get()) {
                return;
            }
            ProcessBuilder pb = new ProcessBuilder(enginePath);
            pb.redirectErrorStream(true);
            File dir = new File(netPath).getParentFile();
            if (dir != null) {
                pb.directory(dir);
            }
            process = pb.start();
            writer = new BufferedWriter(
                    new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));

            Thread reader = new Thread(this::readLoop, "pikafish-reader");
            reader.setDaemon(true);
            reader.start();

            send("uci");
            while (true) {
                if ("uciok".equals(nextLine(20))) {
                    break;
                }
            }
            send("setoption name EvalFile value " + netPath);
            send("setoption name Threads value 2");
            send("setoption name Hash value 64");
            send("isready");
            while (true) {
                if ("readyok".equals(nextLine(30))) {
                    break;
                }
            }

            if (closed.get()) {
                return;
            }
            main.post(() -> {
                if (!closed.get()) {
                    listener.onReady();
                }
            });

            while (!closed.get()) {
                Runnable job = jobs.take();
                if (closed.get()) {
                    break;
                }
                job.run();
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            if (!closed.get()) {
                final String message = e.getMessage() == null ? e.toString() : e.getMessage();
                main.post(() -> listener.onError(message));
            }
        } finally {
            destroyProcess();
        }
    }

    private void readLoop() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("info string")) {
                    recent.add(line);
                    if (recent.size() > 8) {
                        recent.remove(0);
                    }
                }
                lines.put(line);
            }
        } catch (Exception ignored) {
            // 进程退出
        } finally {
            try {
                lines.put(EOF);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void send(String command) {
        if (writer == null) {
            throw new IllegalStateException("引擎未启动");
        }
        try {
            writer.write(command);
            writer.newLine();
            writer.flush();
        } catch (Exception e) {
            throw new IllegalStateException("无法向引擎写入命令：" + e.getMessage(), e);
        }
    }

    private String nextLine(long timeoutSeconds) {
        Object item;
        try {
            item = lines.poll(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("引擎线程被中断", e);
        }
        if (item == null) {
            throw new IllegalStateException("等待引擎响应超时");
        }
        if (item == EOF) {
            StringBuilder sb = new StringBuilder("引擎进程已退出");
            synchronized (recent) {
                if (!recent.isEmpty()) {
                    sb.append('\n');
                    for (String line : recent) {
                        sb.append(line).append('\n');
                    }
                }
            }
            throw new IllegalStateException(sb.toString().trim());
        }
        return (String) item;
    }

    private void destroyProcess() {
        if (process != null) {
            process.destroy();
            process = null;
        }
        writer = null;
    }

    private static Info parseInfo(String line) {
        String[] tokens = line.split("\\s+");
        Info info = new Info();
        boolean hasDepth = false;
        boolean hasScore = false;

        for (int i = 0; i < tokens.length; i++) {
            switch (tokens[i]) {
                case "depth":
                    if (i + 1 < tokens.length) {
                        info.depth = parseInt(tokens[i + 1], -1);
                        hasDepth = info.depth >= 0;
                    }
                    break;
                case "multipv":
                    if (i + 1 < tokens.length) {
                        info.multipv = parseInt(tokens[i + 1], 1);
                    }
                    break;
                case "score":
                    if (i + 2 < tokens.length) {
                        info.scoreType = tokens[i + 1];
                        info.scoreValue = parseInt(tokens[i + 2], 0);
                        hasScore = "cp".equals(info.scoreType) || "mate".equals(info.scoreType);
                    }
                    break;
                case "pv":
                    StringBuilder pv = new StringBuilder();
                    for (int j = i + 1; j < tokens.length; j++) {
                        if (pv.length() > 0) {
                            pv.append(' ');
                        }
                        pv.append(tokens[j]);
                    }
                    info.pv = pv.toString();
                    i = tokens.length;
                    break;
                default:
                    break;
            }
        }
        if (!hasDepth || !hasScore) {
            return null;
        }
        return info;
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
