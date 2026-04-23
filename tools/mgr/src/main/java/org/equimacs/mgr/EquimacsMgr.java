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
    private static String ECLIPSE_HOME;

    public record Response(String status, String message, Object data) {}

    public static class Schema {
        public String name = "Equimacs Manager";
        public String description = "Workflow automation for Equimacs development.";
        public Map<String, String> commands = Map.ofEntries(
            Map.entry("build", "Run the boring build (Build.java)"),
            Map.entry("deploy", "Build and deploy the bridge plugin to Eclipse dropins"),
            Map.entry("test-cli", "Run the Equimacs CLI (args: <cmd> [args...])"),
            Map.entry("sync-context", "Force-sync private context repository"),
            Map.entry("list-context", "Raw directory listing of context (bypasses gitignore)"),
            Map.entry("diagnose", "Report Eclipse bridge deployment, cache, socket, and relevant logs"),
            Map.entry("logs", "Show recent Eclipse log entries relevant to the bridge"),
            Map.entry("clean", "Remove all build artifacts"),
            Map.entry("all", "Build and deploy everything"),
            Map.entry("package", "Create standalone executables via jpackage"),
            Map.entry("reproduce", "Full deterministic cycle: clean -> build -> package")
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
                case "diagnose" -> {
                    System.out.println(gson.toJson(new Response("success", "Diagnostics complete", runDiagnose(false))));
                }
                case "logs" -> {
                    System.out.println(gson.toJson(new Response("success", "Log scan complete", runDiagnose(true))));
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
        if (JAVA_HOME == null) throw new IllegalStateException("JAVA_HOME is not set; cannot run jpackage");
        Path jpackageBin = Path.of(JAVA_HOME, "bin", "jpackage.exe");
        if (!Files.exists(jpackageBin)) throw new IllegalStateException("jpackage not found: " + jpackageBin);

        Path gsonJar    = REPO_ROOT.resolve("lib/gson.jar");
        Path cliLibOut  = REPO_ROOT.resolve("libs/cli/build/classes");
        if (!Files.exists(gsonJar)) throw new IllegalStateException("Not found (run build first): " + gsonJar);

        System.out.println(">>> Packaging Equimacs CLI...");
        packageApp("eqm", "org.equimacs.cli.EquimacsCLI",
            REPO_ROOT.resolve("tools/cli/build"),
            List.of(REPO_ROOT.resolve("tools/cli/build/classes"),
                    REPO_ROOT.resolve("libs/protocol/build/classes"),
                    cliLibOut),
            gsonJar);

        System.out.println(">>> Packaging Equimacs Manager...");
        packageApp("eqm-mgr", "org.equimacs.mgr.EquimacsMgr",
            REPO_ROOT.resolve("tools/mgr/build"),
            List.of(REPO_ROOT.resolve("tools/mgr/build/classes"), cliLibOut),
            gsonJar);
    }

    private static Map<String, Object> runDiagnose(boolean logsOnly) throws IOException {
        Map<String, Object> data = new LinkedHashMap<>();
        Path eclipseHome = getEclipseHome();
        Path eclipseUserHome = getEclipseUserHome(eclipseHome);
        Path workspace = eclipseUserHome.resolve("eclipse-workspace");
        Path workspaceLog = workspace.resolve(".metadata/.log");
        Path bridgeTrace = eclipseUserHome.resolve(".equimacs.trace");
        List<String> patterns = List.of(
            "org.equimacs",
            "NoClassDefFoundError",
            "ClassNotFoundException",
            "JsonParseException",
            "BundleException",
            "Unable to clean the storage area"
        );

        data.put("workspaceLog", workspaceLog.toString());
        data.put("workspaceLogMatches", recentMatchingLines(workspaceLog, patterns, 120));
        data.put("bridgeTrace", bridgeTrace.toString());
        data.put("bridgeTraceTail", tailLines(bridgeTrace, 30));

        if (logsOnly) {
            return data;
        }

        Path dropin = eclipseHome.resolve("dropins/org.equimacs.eclipse.bridge");
        Path installedJar = configuredBridgeJar(eclipseHome);
        Path manifest = dropin.resolve("META-INF/MANIFEST.MF");
        Map<String, Object> paths = new LinkedHashMap<>();
        paths.put("repoRoot", REPO_ROOT.toString());
        paths.put("eclipseUserHome", eclipseUserHome.toString());
        paths.put("eclipseHome", eclipseHome.toString());
        paths.put("workspace", workspace.toString());
        paths.put("dropin", dropin.toString());
        paths.put("installedJar", installedJar.toString());
        paths.put("frameworkCache", eclipseHome.resolve("configuration/org.eclipse.osgi").toString());
        data.put("paths", paths);

        Map<String, Object> runtime = new LinkedHashMap<>();
        runtime.put("socketExists", Files.exists(eclipseUserHome.resolve(".equimacs.sock")));
        runtime.put("eclipseProcesses", eclipseProcesses());
        data.put("runtime", runtime);

        Map<String, Object> bundle = new LinkedHashMap<>();
        bundle.put("dropinExists", Files.exists(dropin));
        bundle.put("installedJarExists", Files.exists(installedJar));
        bundle.put("manifestExists", Files.exists(manifest));
        bundle.put("bundleClassPath", manifestHeader(manifest, "Bundle-ClassPath"));
        bundle.put("requireBundle", manifestHeader(manifest, "Require-Bundle"));
        bundle.put("hasRootGsonJsonParseException", Files.exists(dropin.resolve("com/google/gson/JsonParseException.class")));
        bundle.put("hasRootProtocolRequest", Files.exists(dropin.resolve("org/equimacs/protocol/Request.class")));
        bundle.put("hasLibGsonJar", Files.exists(dropin.resolve("lib/gson.jar")));
        bundle.put("hasLibProtocolJar", Files.exists(dropin.resolve("lib/protocol.jar")));
        data.put("bundle", bundle);

        Path config = eclipseHome.resolve("configuration");
        Path bundlesInfo = config.resolve("org.eclipse.equinox.simpleconfigurator/bundles.info");
        List<String> simpleConfiguratorMatches = recentMatchingLines(bundlesInfo, List.of("org.equimacs.eclipse.bridge"), 20);
        Map<String, Object> eclipse = new LinkedHashMap<>();
        eclipse.put("frameworkCacheExists", Files.exists(eclipseHome.resolve("configuration/org.eclipse.osgi")));
        eclipse.put("simpleConfigurator", bundlesInfo.toString());
        eclipse.put("bridgeInSimpleConfigurator", !simpleConfiguratorMatches.isEmpty());
        eclipse.put("simpleConfiguratorMatches", simpleConfiguratorMatches);
        eclipse.put("recentConfigurationLogs", recentFiles(config, "*.log", 8));
        eclipse.put("dropinsCacheTimestamps", findFiles(eclipseHome.resolve("configuration/org.eclipse.osgi"), "cache.timestamps", 8));
        data.put("eclipse", eclipse);

        return data;
    }

    private static Path getEclipseHome() {
        if (ECLIPSE_HOME != null && !ECLIPSE_HOME.isBlank()) {
            return Path.of(ECLIPSE_HOME);
        }
        return Path.of(System.getProperty("user.home"), "eclipse");
    }

    private static Path getEclipseUserHome(Path eclipseHome) {
        Path parent = eclipseHome.toAbsolutePath().normalize().getParent();
        if (parent != null) {
            return parent;
        }
        return Path.of(System.getProperty("user.home"));
    }

    private static Path configuredBridgeJar(Path eclipseHome) throws IOException {
        Path bundlesInfo = eclipseHome.resolve("configuration/org.eclipse.equinox.simpleconfigurator/bundles.info");
        if (Files.exists(bundlesInfo)) {
            for (String line : Files.readAllLines(bundlesInfo)) {
                if (line.startsWith("org.equimacs.eclipse.bridge,")) {
                    String[] parts = line.split(",", 5);
                    if (parts.length >= 3) {
                        return eclipseHome.resolve(parts[2]);
                    }
                }
            }
        }
        return eclipseHome.resolve("plugins/org.equimacs.eclipse.bridge_1.0.0.qualifier.jar");
    }

    private static List<Map<String, Object>> eclipseProcesses() {
        List<Map<String, Object>> processes = new ArrayList<>();
        ProcessHandle.allProcesses().forEach(p -> {
            ProcessHandle.Info info = p.info();
            String command = info.command().orElse("");
            String name = Path.of(command.isBlank() ? "unknown" : command).getFileName().toString().toLowerCase(Locale.ROOT);
            if (name.contains("eclipse") || name.equals("javaw.exe") || name.equals("java.exe")) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("pid", p.pid());
                row.put("command", command);
                row.put("arguments", List.of(info.arguments().orElse(new String[0])));
                processes.add(row);
            }
        });
        return processes;
    }

    private static String manifestHeader(Path manifest, String key) throws IOException {
        if (!Files.exists(manifest)) return null;
        StringBuilder value = new StringBuilder();
        boolean active = false;
        for (String line : Files.readAllLines(manifest)) {
            if (line.startsWith(key + ":")) {
                value.setLength(0);
                value.append(line.substring(key.length() + 1).trim());
                active = true;
            } else if (active && line.startsWith(" ")) {
                value.append(line.trim());
            } else if (active) {
                break;
            }
        }
        return value.isEmpty() ? null : value.toString();
    }

    private static List<String> recentMatchingLines(Path file, List<String> patterns, int limit) throws IOException {
        List<String> matches = new ArrayList<>();
        for (String line : tailLines(file, 2000)) {
            String lower = line.toLowerCase(Locale.ROOT);
            for (String pattern : patterns) {
                if (lower.contains(pattern.toLowerCase(Locale.ROOT))) {
                    matches.add(line);
                    break;
                }
            }
        }
        return last(matches, limit);
    }

    private static List<String> tailLines(Path file, int limit) throws IOException {
        if (!Files.exists(file)) return List.of();
        List<String> lines = Files.readAllLines(file);
        return last(lines, limit);
    }

    private static <T> List<T> last(List<T> values, int limit) {
        int from = Math.max(0, values.size() - limit);
        return new ArrayList<>(values.subList(from, values.size()));
    }

    private static List<Map<String, Object>> recentFiles(Path root, String glob, int limit) throws IOException {
        if (!Files.exists(root)) return List.of();
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + glob);
        List<Path> files;
        try (var stream = Files.list(root)) {
            files = stream.filter(p -> matcher.matches(p.getFileName()))
                .sorted(Comparator.comparing((Path p) -> {
                    try { return Files.getLastModifiedTime(p); }
                    catch (IOException e) { return java.nio.file.attribute.FileTime.fromMillis(0); }
                }).reversed())
                .limit(limit)
                .toList();
        }
        return fileRows(files);
    }

    private static List<Map<String, Object>> findFiles(Path root, String fileName, int limit) throws IOException {
        if (!Files.exists(root)) return List.of();
        List<Path> files;
        try (var stream = Files.walk(root)) {
            files = stream.filter(p -> p.getFileName().toString().equals(fileName))
                .sorted(Comparator.comparing((Path p) -> {
                    try { return Files.getLastModifiedTime(p); }
                    catch (IOException e) { return java.nio.file.attribute.FileTime.fromMillis(0); }
                }).reversed())
                .limit(limit)
                .toList();
        }
        return fileRows(files);
    }

    private static List<Map<String, Object>> fileRows(List<Path> files) throws IOException {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Path file : files) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("path", file.toString());
            row.put("lastModified", Files.getLastModifiedTime(file).toString());
            row.put("size", Files.size(file));
            rows.add(row);
        }
        return rows;
    }

    private static void packageApp(String name, String mainClass, Path buildDir, List<Path> classDirs, Path gsonJar) throws Exception {
        // Pre-flight: verify all class dirs exist before touching anything
        for (Path dir : classDirs) {
            if (!Files.exists(dir))
                throw new IllegalStateException("Class dir not found (run build first): " + dir);
        }

        Path libsDir    = buildDir.resolve("libs");
        Path appOutBase = buildDir.resolve("app");
        Path tmpExplode = buildDir.resolve("tmp_explode");
        Path fatJar     = libsDir.resolve(name + "-fat.jar");
        Path finalApp   = appOutBase.resolve(name);

        deleteDir(libsDir);
        Files.createDirectories(libsDir);
        Files.createDirectories(appOutBase);

        // Remove artefacts left by any previous interrupted run
        try (var s = Files.list(appOutBase)) {
            s.filter(p -> {
                String n = p.getFileName().toString();
                return n.startsWith("jpackage-") || n.startsWith(name + ".old.");
            }).forEach(p -> { try { deleteDir(p); } catch (IOException ignored) {} });
        }

        // Use a timestamped name so a stale .old dir never causes FileAlreadyExistsException
        Path oldApp = appOutBase.resolve(name + ".old." + System.currentTimeMillis());

        Path jpackageTemp = null;
        boolean renamedOld = false;
        try {
            // Build fat JAR
            deleteDir(tmpExplode);
            Files.createDirectories(tmpExplode);
            runProcess(List.of(getJar(), "xf", gsonJar.toString()), tmpExplode);

            List<String> jarCmd = new ArrayList<>(List.of(
                getJar(), "--create", "--file", fatJar.toString(), "--main-class", mainClass));
            for (Path dir : classDirs) jarCmd.addAll(List.of("-C", dir.toString(), "."));
            jarCmd.addAll(List.of("-C", tmpExplode.toString(), "."));
            runProcess(jarCmd, REPO_ROOT);

            // Run jpackage into a temp dir so a failed run never touches the live image
            jpackageTemp = Files.createTempDirectory(appOutBase, "jpackage-");
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

            // Verify the new image is complete before we touch the live one
            verifyAppImage(jpackageTemp.resolve(name), name);

            // Swap: rename the old dir aside first so the live image is never absent.
            // Renaming works on Windows even if the exe is mapped into memory; deleting does not.
            if (Files.exists(finalApp)) {
                Files.move(finalApp, oldApp);
                renamedOld = true;
            }
            Files.move(jpackageTemp.resolve(name), finalApp);
            renamedOld = false; // committed — old dir is now safe to remove

        } catch (Exception e) {
            // If the swap was half-done, put the old image back
            if (renamedOld && !Files.exists(finalApp)) {
                try { Files.move(oldApp, finalApp); } catch (IOException ignored) {}
            }
            throw e;
        } finally {
            try { deleteDir(tmpExplode); } catch (IOException ignored) {}
            if (jpackageTemp != null) try { deleteDir(jpackageTemp); } catch (IOException ignored) {}
            if (!renamedOld)          try { deleteDir(oldApp);       } catch (IOException ignored) {}
        }

        verifyAppImage(finalApp, name);
        System.out.println("  Executable: " + finalApp.resolve(name + ".exe"));
    }

    private static void verifyAppImage(Path appDir, String name) {
        for (String entry : new String[]{ name + ".exe", "app", "runtime" }) {
            if (!Files.exists(appDir.resolve(entry)))
                throw new RuntimeException("Incomplete app image at " + appDir + ": missing " + entry);
        }
    }

    private static void loadEnv() throws IOException {
        Path p = REPO_ROOT.resolve(".env");
        if (Files.exists(p)) {
            for (String line : Files.readAllLines(p)) {
                if (line.contains("=") && !line.startsWith("#")) {
                    String[] parts = line.split("=", 2);
                    if (parts[0].trim().equals("JAVA_HOME")) JAVA_HOME = parts[1].trim();
                    if (parts[0].trim().equals("ECLIPSE_HOME")) ECLIPSE_HOME = parts[1].trim();
                }
            }
        }
        if (JAVA_HOME == null) JAVA_HOME = System.getenv("JAVA_HOME");
        if (ECLIPSE_HOME == null) ECLIPSE_HOME = System.getenv("ECLIPSE_HOME");
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
        try (var s = Files.walk(p)) {
            for (Path path : s.sorted(Comparator.reverseOrder()).toList()) {
                // jpackage marks some runtime files read-only on Windows; clear it before deleting
                path.toFile().setWritable(true);
                Files.deleteIfExists(path);
            }
        }
    }
}
