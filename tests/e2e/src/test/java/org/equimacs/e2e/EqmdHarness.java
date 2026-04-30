package org.equimacs.e2e;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import com.google.gson.JsonObject;
import org.equimacs.protocol.Request;

final class EqmdHarness implements AutoCloseable {
    private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(90);
    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(10);

    private final Path home;
    private final Path log;
    private final Process process;
    private final EqmdRpc rpc;

    private EqmdHarness(Path home, Path log, Process process, EqmdRpc rpc) {
        this.home = home;
        this.log = log;
        this.process = process;
        this.rpc = rpc;
    }

    static EqmdHarness start() throws Exception {
        return startWithFixture(null);
    }

    static EqmdHarness startWithFixture(String fixtureName) throws Exception {
        Path repo = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path workspaces = repo.resolve("tests/e2e/workspaces");
        Files.createDirectories(workspaces);

        Path home = Files.createTempDirectory(workspaces, "eqmd-");
        Path workspace = home.resolve("workspace");
        Path socket = home.resolve("equimacs.sock");
        Path log = home.resolve("eqmd.log");
        if (fixtureName != null && !fixtureName.isBlank()) {
            copyDir(repo.resolve("tests/e2e/fixtures").resolve(fixtureName), workspace.resolve(fixtureName));
        }

        String eclipseHome = requireEclipseHome(repo);
        ProcessBuilder pb = new ProcessBuilder(eqmdCommand(repo));
        Map<String, String> env = pb.environment();
        env.put("ECLIPSE_HOME", eclipseHome);
        env.put("EQUIMACS_HOME", home.toString());
        env.put("EQUIMACS_WORKSPACE", workspace.toString());
        env.put("EQUIMACS_SOCKET", socket.toString());
        pb.directory(repo.toFile());
        pb.redirectErrorStream(true);
        pb.redirectOutput(log.toFile());

        Process process = pb.start();
        EqmdHarness harness = new EqmdHarness(home, log, process, new EqmdRpc(socket));
        harness.awaitReady();
        return harness;
    }

    EqmdRpc rpc() {
        return rpc;
    }

    @Override
    public void close() throws Exception {
        try {
            if (process.isAlive()) {
                rpc.request(new Request.Shutdown());
                if (!process.waitFor(SHUTDOWN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                    throw new AssertionError("eqmd did not exit after shutdown. Log:\n" + logTail());
                }
            }
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
            deleteDir(home);
        }
    }

    void destroyForcibly() throws Exception {
        try {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(SHUTDOWN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            }
        } finally {
            deleteDir(home);
        }
    }

    void awaitReady() throws Exception {
        long deadline = System.nanoTime() + STARTUP_TIMEOUT.toNanos();
        Exception last = null;
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) {
                throw new AssertionError("eqmd exited during startup. Log:\n" + logTail());
            }
            try {
                JsonObject response = rpc.request(new Request.WaitEvent(1));
                if (response.has("result")
                    && response.getAsJsonObject("result").has("event")
                    && response.getAsJsonObject("result").get("event").getAsString().equals("Timeout")) {
                    return;
                }
            } catch (Exception e) {
                last = e;
            }
            Thread.sleep(1_000);
        }
        throw new AssertionError("eqmd did not become ready. Last error: "
            + (last == null ? "(none)" : last.getMessage()) + "\nLog:\n" + logTail());
    }

    private String logTail() {
        try {
            List<String> lines = Files.exists(log) ? Files.readAllLines(log) : List.of();
            int from = Math.max(0, lines.size() - 80);
            return String.join(System.lineSeparator(), lines.subList(from, lines.size()));
        } catch (IOException e) {
            return "(unable to read log: " + e.getMessage() + ")";
        }
    }

    private static List<String> eqmdCommand(Path repo) {
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            return List.of("cmd.exe", "/c", repo.resolve("tools/eqmd/eqmd.cmd").toString());
        }
        return List.of(repo.resolve("tools/eqmd/eqmd").toString());
    }

    private static String requireEclipseHome(Path repo) throws IOException {
        String env = System.getenv("ECLIPSE_HOME");
        if (env != null && !env.isBlank()) return env;

        Path dotEnv = repo.resolve(".env");
        if (Files.exists(dotEnv)) {
            for (String line : Files.readAllLines(dotEnv)) {
                if (line.startsWith("ECLIPSE_HOME=")) {
                    return line.substring("ECLIPSE_HOME=".length()).trim();
                }
            }
        }
        throw new IllegalStateException("ECLIPSE_HOME is not set and .env does not define it");
    }

    private static void deleteDir(Path path) throws IOException {
        if (!Files.exists(path)) return;
        IOException last = null;
        for (int attempt = 0; attempt < 5; attempt++) {
            try (Stream<Path> stream = Files.walk(path)) {
                for (Path p : stream.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(p);
                }
                return;
            } catch (IOException e) {
                last = e;
                try {
                    Thread.sleep(500);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
        System.err.println("Warning: could not delete temporary eqmd workspace "
            + path + ": " + last.getMessage());
    }

    private static void copyDir(Path source, Path target) throws IOException {
        if (!Files.exists(source)) {
            throw new IOException("Fixture not found: " + source);
        }
        try (Stream<Path> stream = Files.walk(source)) {
            for (Path src : stream.toList()) {
                Path dest = target.resolve(source.relativize(src));
                if (Files.isDirectory(src)) {
                    Files.createDirectories(dest);
                } else {
                    Files.createDirectories(dest.getParent());
                    Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }
}
