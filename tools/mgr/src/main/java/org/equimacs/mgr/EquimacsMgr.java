package org.equimacs.mgr;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Equimacs Manager CLI.
 * Agent-first CLI for build, deploy, and context tasks.
 */
public class EquimacsMgr {
    private static final Path REPO_ROOT = Paths.get(".").toAbsolutePath().normalize();
    private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private static String JAVA_HOME;

    public record Response(String status, String message, Object data) {}

    public static class Schema {
        public String name = "Equimacs Manager";
        public String description = "Workflow automation for Equimacs development.";
        public Map<String, String> commands = Map.of(
            "build", "Run the boring build (Build.java)",
            "deploy", "Build and deploy the bridge plugin to Eclipse dropins",
            "test-cli", "Run the Equimacs CLI (args: <cmd> [args...])",
            "sync-context", "Force-sync private context repository",
            "list-context", "Raw directory listing of context (bypasses gitignore)",
            "clean", "Remove all build artifacts",
            "all", "Build and deploy everything",
            "package", "Create standalone executables via jpackage",
            "reproduce", "Full deterministic cycle: clean -> build -> package"
        );
    }

    public static void main(String[] args) {
        try {
            loadEnv();
            
            if (args.length == 0 || isDiscovery(args[0])) {
                System.out.println(gson.toJson(new Schema()));
                return;
            }

            String cmd = args[0].toLowerCase();
            switch (cmd) {
                case "build" -> {
                    runBuild();
                    System.out.println("Build completed successfully.");
                }
                case "deploy" -> {
                    runBuild();
                    System.out.println("Deploy completed successfully.");
                }
                case "test-cli" -> {
                    runTestCli(Arrays.asList(args).subList(1, args.length));
                }
                case "sync-context" -> {
                    runSyncContext();
                    System.out.println("Context synced successfully.");
                }
                case "list-context" -> {
                    List<String> files = runListContext();
                    System.out.println(gson.toJson(new Response("success", "Context listing complete", files)));
                }
                case "clean" -> {
                    runClean();
                    System.out.println("Clean complete.");
                }
                case "all" -> {
                    runBuild();
                    System.out.println("All tasks complete.");
                }
                case "package" -> {
                    runPackage();
                    System.out.println("Packaging complete.");
                }
                case "reproduce" -> {
                    runClean();
                    runBuild();
                    runPackage();
                    System.out.println("Reproduction complete.");
                }
                default -> throw new IllegalArgumentException("Unknown command: " + cmd);
            }

        } catch (Exception e) {
            System.err.println("!!! Error: " + e.getMessage());
            if (!(e instanceof IllegalArgumentException)) {
                e.printStackTrace();
            }
            System.exit(1);
        }
    }

    private static boolean isDiscovery(String arg) {
        return List.of("--schema", "discovery", "help", "--help", "-h").contains(arg.toLowerCase());
    }

    private static void runBuild() throws Exception {
        runProcess(List.of(getJava(), "Build.java"), REPO_ROOT);
    }

    private static void runTestCli(List<String> args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of(
            getJava(),
            "-cp", String.join(File.pathSeparator, 
                "tools/cli/build/classes",
                "libs/cli/build/classes",
                "lib/gson.jar",
                "libs/protocol/build/libs/protocol.jar"),
            "org.equimacs.cli.EquimacsCLI"
        ));
        cmd.addAll(args);
        runProcess(cmd, REPO_ROOT);
    }

    private static void runSyncContext() throws Exception {
        Path contextPath = REPO_ROOT.resolve("context");
        if (!Files.exists(contextPath.resolve(".git"))) {
            throw new RuntimeException("context is not a git repository.");
        }
        runProcess(List.of("git", "add", "-f", "."), contextPath);
        try {
            runProcess(List.of("git", "commit", "-m", "Manual sync via EquimacsMgr at " + System.currentTimeMillis()), contextPath);
            runProcess(List.of("git", "push", "origin", "master"), contextPath);
        } catch (Exception ignored) {
            // Likely no changes to commit
        }
    }

    private static List<String> runListContext() throws Exception {
        Path contextPath = REPO_ROOT.resolve("context");
        List<String> files = new ArrayList<>();
        if (Files.exists(contextPath)) {
            try (var stream = Files.walk(contextPath)) {
                stream.forEach(p -> {
                    String relative = REPO_ROOT.relativize(p).toString();
                    if (relative.contains(".git" + File.separator)) return;
                    files.add(relative);
                });
            }
        }
        return files;
    }

    private static void runClean() throws Exception {
        String[] targets = { "libs/protocol/build", "libs/cli/build", "plugins/org.equimacs.eclipse.bridge/build", "tools/cli/build", "tools/mgr/build" };
        for (String target : targets) {
            Path p = REPO_ROOT.resolve(target);
            deleteDir(p);
        }
    }

    private static void runPackage() throws Exception {
        Path gsonJar = REPO_ROOT.resolve("lib/gson.jar");
        Path cliLibOut = REPO_ROOT.resolve("libs/cli/build/classes");
        
        // --- 1. Package Equimacs CLI ---
        System.out.println(">>> Packaging Equimacs CLI...");
        packageApp("equimacs", "org.equimacs.cli.EquimacsCLI", 
            REPO_ROOT.resolve("tools/cli/build"),
            List.of(REPO_ROOT.resolve("tools/cli/build/classes"), REPO_ROOT.resolve("libs/protocol/build/classes"), cliLibOut),
            gsonJar);

        // --- 2. Package Equimacs Manager ---
        System.out.println(">>> Packaging Equimacs Manager...");
        packageApp("eqmgr", "org.equimacs.mgr.EquimacsMgr", 
            REPO_ROOT.resolve("tools/mgr/build"),
            List.of(REPO_ROOT.resolve("tools/mgr/build/classes"), cliLibOut),
            gsonJar);
    }

    private static void packageApp(String name, String mainClass, Path buildDir, List<Path> classDirs, Path gsonJar) throws Exception {
        Path libsDir = buildDir.resolve("libs");
        Path appOutBase = buildDir.resolve("app");
        deleteDir(libsDir);
        deleteDir(appOutBase);
        Files.createDirectories(libsDir);
        Files.createDirectories(appOutBase);

        Path fatJar = libsDir.resolve(name + "-fat.jar");
        
        // Explode GSON
        Path tmpExplode = buildDir.resolve("tmp_explode");
        deleteDir(tmpExplode);
        Files.createDirectories(tmpExplode);
        runProcess(List.of(getJar(), "xf", gsonJar.toString()), tmpExplode);

        // Create Fat JAR
        List<String> jarCmd = new ArrayList<>(List.of(getJar(), "--create", "--file", fatJar.toString(), "--main-class", mainClass));
        for (Path dir : classDirs) {
            jarCmd.addAll(List.of("-C", dir.toString(), "."));
        }
        jarCmd.addAll(List.of("-C", tmpExplode.toString(), "."));
        runProcess(jarCmd, REPO_ROOT);

        // Run jpackage in a temp dir to avoid Windows "already exists" errors
        Path jpackageTemp = Files.createTempDirectory(appOutBase, "jpackage-");
        runProcess(List.of(
            Path.of(JAVA_HOME, "bin/jpackage").toString(),
            "--type", "app-image",
            "--dest", jpackageTemp.toString(),
            "--name", name,
            "--main-jar", fatJar.getFileName().toString(),
            "--input", libsDir.toString(),
            "--main-class", mainClass,
            "--vendor", "Equimacs"
        ), REPO_ROOT);

        Path finalApp = appOutBase.resolve(name);
        deleteDir(finalApp);
        Files.move(jpackageTemp.resolve(name), finalApp, StandardCopyOption.REPLACE_EXISTING);
        deleteDir(jpackageTemp);
        System.out.println("Executable created at: " + finalApp.resolve(name + ".exe"));
    }

    private static void loadEnv() throws IOException {
        Path p = REPO_ROOT.resolve(".env");
        if (Files.exists(p)) {
            for (String line : Files.readAllLines(p)) {
                if (line.contains("=") && !line.startsWith("#")) {
                    String[] parts = line.split("=", 2);
                    if (parts[0].trim().equals("JAVA_HOME")) JAVA_HOME = parts[1].trim();
                }
            }
        }
        if (JAVA_HOME == null) JAVA_HOME = System.getenv("JAVA_HOME");
    }

    private static String getJava() {
        return JAVA_HOME != null ? Path.of(JAVA_HOME, "bin/java").toString() : "java";
    }

    private static String getJar() {
        return JAVA_HOME != null ? Path.of(JAVA_HOME, "bin/jar").toString() : "jar";
    }

    private static void runProcess(List<String> args, Path dir) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(args);
        pb.directory(dir.toFile());
        pb.inheritIO(); 
        Process p = pb.start();
        int code = p.waitFor();
        if (code != 0) throw new RuntimeException("Command failed with code " + code + ": " + args);
    }

    private static void deleteDir(Path p) throws IOException {
        if (!Files.exists(p)) return;
        
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < 5000) { // 5 second timeout
            try {
                try (var s = Files.walk(p)) {
                    var paths = s.sorted(Comparator.reverseOrder()).toList();
                    for (Path path : paths) {
                        Files.deleteIfExists(path);
                    }
                }
                if (!Files.exists(p)) return;
            } catch (IOException e) {
                // Wait and retry
                try { Thread.sleep(200); } catch (InterruptedException ignored) {}
            }
        }
        if (Files.exists(p)) {
            throw new IOException("Failed to delete directory after timeout: " + p);
        }
    }
}
