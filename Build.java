import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

/**
 * Equimacs Boring Build.
 * Outputs JSON-RPC style status.
 */
public class Build {
    private static String ECLIPSE_HOME;
    private static String JAVA_HOME;
    private static final Path ROOT = Paths.get(".").toAbsolutePath().normalize();
    private static final Map<String, String> LIBS = new LinkedHashMap<>();

    public static void main(String[] args) {
        try {
            parseArgs(args);
            loadEnv();
            loadLibs();
            
            System.out.println(">>> Equimacs Build Started");
            System.out.println(">>> Root: " + ROOT);
            
            ensureLibDir();
            downloadDependencies();
            
            buildCliLib();
            buildProtocol();
            buildBridge();
            buildCLI();
            buildMgr();
            packageAll();

            copyToDropins();
            
            System.out.println(">>> Build Successful");
        } catch (Throwable t) {
            System.err.println("!!! Build Failed: " + t.getMessage());
            t.printStackTrace();
            System.exit(1);
        }
    }

    private static void buildCliLib() throws Exception {
        step("cli-lib", () -> {
            Path src = ROOT.resolve("libs/cli/src/main/java");
            Path out = ROOT.resolve("libs/cli/build/classes");
            if (Files.exists(out)) deleteDir(out);
            Files.createDirectories(out);
            runProcess(List.of(getJavac(), "-d", out.toString(), "--release", "25",
                src.resolve("org/equimacs/cli/util/CliArgs.java").toString()));
        });
    }

    private static void buildProtocol() throws Exception {
        step("protocol", () -> {
            Path src = ROOT.resolve("libs/protocol/src/main/java");
            Path out = ROOT.resolve("libs/protocol/build/classes");
            if (Files.exists(out)) deleteDir(out);
            Files.createDirectories(out);

            runProcess(List.of(getJavac(), "-d", out.toString(), "--release", "25",
                src.resolve("org/equimacs/protocol/ProtocolSchema.java").toString(),
                src.resolve("org/equimacs/protocol/Request.java").toString(),
                src.resolve("org/equimacs/protocol/Response.java").toString()));

            Path jarOut = ROOT.resolve("libs/protocol/build/libs");
            Files.createDirectories(jarOut);
            runProcess(List.of(getJar(), "--create", "--file", jarOut.resolve("protocol.jar").toString(),
                "-C", out.toString(), "."));
        });
    }

    private static void buildBridge() throws Exception {
        step("bridge", () -> {
            Path bridgeDir = ROOT.resolve("plugins/org.equimacs.eclipse.bridge");
            Path src = bridgeDir.resolve("src/main/java");
            Path out = bridgeDir.resolve("build/classes");
            if (Files.exists(out)) deleteDir(out);
            Files.createDirectories(out);

            String cp = findEclipseJars();
            cp += File.pathSeparator + ROOT.resolve("libs/protocol/build/libs/protocol.jar");
            cp += File.pathSeparator + ROOT.resolve("libs/cli/build/classes");
            cp += File.pathSeparator + getLib("gson");

            List<String> javacCmd = new ArrayList<>(List.of(getJavac(), "-cp", cp, "-d", out.toString(), "--release", "25"));
            try (Stream<Path> s = Files.walk(src)) {
                s.filter(p -> p.toString().endsWith(".java")).forEach(p -> javacCmd.add(p.toString()));
            }
            runProcess(javacCmd);

            // Bnd Packaging
            Path bndJar = Path.of(getLib("bnd"));
            runProcess(List.of(getJava(), "-jar", bndJar.toAbsolutePath().toString(), "buildx", "bnd.bnd"), bridgeDir);
        });
    }

    private static void buildCLI() throws Exception {
        step("cli", () -> {
            Path src = ROOT.resolve("tools/cli/src/main/java");
            Path out = ROOT.resolve("tools/cli/build/classes");
            if (Files.exists(out)) deleteDir(out);
            Files.createDirectories(out);

            String cp = ROOT.resolve("libs/protocol/build/libs/protocol.jar") + 
                        File.pathSeparator + ROOT.resolve("libs/cli/build/classes") + 
                        File.pathSeparator + getLib("gson");
            runProcess(List.of(getJavac(), "-cp", cp, "-d", out.toString(), "--release", "25",
                src.resolve("org/equimacs/cli/EquimacsCLI.java").toString()));
        });
    }

    private static void buildMgr() throws Exception {
        step("mgr", () -> {
            Path src = ROOT.resolve("tools/mgr/src/main/java");
            Path out = ROOT.resolve("tools/mgr/build/classes");
            if (Files.exists(out)) deleteDir(out);
            Files.createDirectories(out);

            String cp = ROOT.resolve("libs/cli/build/classes") + File.pathSeparator + getLib("gson");
            runProcess(List.of(getJavac(), "-cp", cp, "-d", out.toString(), "--release", "25",
                src.resolve("org/equimacs/mgr/EquimacsMgr.java").toString()));
        });
    }

    private static void downloadDependencies() throws IOException {
        for (var entry : LIBS.entrySet()) {
            Path dest = ROOT.resolve("lib").resolve(entry.getKey() + ".jar");
            if (!Files.exists(dest)) {
                System.out.println("  [lib] downloading " + entry.getKey() + "...");
                try (InputStream in = new java.net.URL(entry.getValue()).openStream()) {
                    Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void packageAll() throws Exception {
        step("package", () -> {
            if (JAVA_HOME == null) { System.out.println("  [package] skipped: JAVA_HOME not set"); return; }
            Path jpackage = Path.of(JAVA_HOME, "bin/jpackage.exe");
            if (!Files.exists(jpackage)) jpackage = Path.of(JAVA_HOME, "bin/jpackage");
            if (!Files.exists(jpackage)) { System.out.println("  [package] skipped: jpackage not found in " + JAVA_HOME); return; }

            Path gsonJar   = ROOT.resolve("lib/gson.jar");
            Path cliLibOut = ROOT.resolve("libs/cli/build/classes");

            packageApp("eqm", "org.equimacs.cli.EquimacsCLI",
                ROOT.resolve("tools/cli/build"),
                List.of(ROOT.resolve("tools/cli/build/classes"),
                        ROOT.resolve("libs/protocol/build/classes"),
                        cliLibOut),
                gsonJar);

            packageApp("eqm-mgr", "org.equimacs.mgr.EquimacsMgr",
                ROOT.resolve("tools/mgr/build"),
                List.of(ROOT.resolve("tools/mgr/build/classes"), cliLibOut),
                gsonJar);
        });
    }

    private static void packageApp(String name, String mainClass, Path buildDir, List<Path> classDirs, Path gsonJar) throws Exception {
        Path libsDir    = buildDir.resolve("libs");
        Path appOutBase = buildDir.resolve("app");
        Path tmpExplode = buildDir.resolve("tmp_explode");
        Path fatJar     = libsDir.resolve(name + "-fat.jar");
        Path finalApp   = appOutBase.resolve(name);

        deleteDir(libsDir);
        Files.createDirectories(libsDir);
        Files.createDirectories(appOutBase);

        // Clean up stale jpackage temp dirs from previous failed runs
        try (var s = Files.list(appOutBase)) {
            s.filter(p -> p.getFileName().toString().startsWith("jpackage-") ||
                          p.getFileName().toString().startsWith(name + ".old."))
             .forEach(p -> { try { deleteDir(p); } catch (IOException ignored) {} });
        }

        Path oldApp      = appOutBase.resolve(name + ".old." + System.currentTimeMillis());
        Path jpackageTemp = null;
        boolean renamedOld = false;
        try {
            // Build fat JAR: explode gson + all class dirs
            deleteDir(tmpExplode);
            Files.createDirectories(tmpExplode);
            runProcess(List.of(getJar(), "xf", gsonJar.toString()), tmpExplode);

            List<String> jarCmd = new ArrayList<>(List.of(
                getJar(), "--create", "--file", fatJar.toString(), "--main-class", mainClass));
            for (Path dir : classDirs) jarCmd.addAll(List.of("-C", dir.toString(), "."));
            jarCmd.addAll(List.of("-C", tmpExplode.toString(), "."));
            runProcess(jarCmd, ROOT);

            // jpackage into a temp dir; only swap if successful
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
            ), ROOT);

            verifyAppImage(jpackageTemp.resolve(name), name);

            if (Files.exists(finalApp)) { Files.move(finalApp, oldApp); renamedOld = true; }
            Files.move(jpackageTemp.resolve(name), finalApp);
            renamedOld = false;

        } catch (Exception e) {
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
        System.out.println("  [package] " + finalApp.resolve(name + ".exe"));
    }

    private static void verifyAppImage(Path appDir, String name) {
        for (String entry : new String[]{ name + ".exe", "app", "runtime" }) {
            if (!Files.exists(appDir.resolve(entry)))
                throw new RuntimeException("Incomplete app image at " + appDir + ": missing " + entry);
        }
    }

    private static void copyToDropins() throws Exception {
        Path jar = ROOT.resolve("plugins/org.equimacs.eclipse.bridge/build/libs/org.equimacs.eclipse.bridge.jar");
        if (Files.exists(jar)) {
            if (ECLIPSE_HOME != null) {
                Path dropins = Path.of(ECLIPSE_HOME, "dropins");
                Path dest = dropins.resolve("org.equimacs.eclipse.bridge");
                if (Files.exists(dest)) deleteDir(dest);
                Files.createDirectories(dest);
                
                System.out.println("  [deploy] -> " + dest + " (exploded)");
                runProcess(List.of(getJar(), "xf", jar.toAbsolutePath().toString()), dest);
            } else {
                System.out.println("  [deploy] skipped: ECLIPSE_HOME not set.");
            }
        }
    }

    private static String findEclipseJars() throws IOException {
        Path plugins = Path.of(ECLIPSE_HOME, "plugins");
        String[] targets = { "org.eclipse.osgi_", "org.eclipse.ui_", "org.eclipse.core.runtime_", "org.eclipse.debug.core_",
            "org.eclipse.core.resources_", "org.eclipse.jdt.core_", "org.eclipse.jdt.debug_", "org.eclipse.equinox.common_",
            "org.eclipse.core.commands_", "org.eclipse.jface_", "org.eclipse.ui.workbench_", "org.eclipse.swt_",
            "org.eclipse.core.jobs_", "org.eclipse.equinox.registry_", "org.eclipse.equinox.preferences_",
            "org.eclipse.core.contenttype_", "org.eclipse.swt.win32.win32.x86_64_",
            "org.apache.felix.gogo.runtime_", "org.eclipse.ui.ide_" };
        
        List<String> found = new ArrayList<>();
        try (Stream<Path> stream = Files.list(plugins)) {
            List<Path> all = stream.toList();
            for (String t : targets) {
                all.stream().filter(p -> p.getFileName().toString().startsWith(t)).findFirst().ifPresent(p -> {
                    if (Files.isDirectory(p)) {
                        try (var s = Files.walk(p)) { s.filter(x -> x.toString().endsWith(".jar")).forEach(x -> found.add(x.toString())); }
                        catch (IOException ignored) {}
                        found.add(p.toString());
                    } else found.add(p.toString());
                });
            }
        }
        return String.join(File.pathSeparator, found);
    }

    private static void loadLibs() throws IOException {
        Path p = ROOT.resolve("libs.txt");
        if (Files.exists(p)) {
            for (String line : Files.readAllLines(p)) {
                if (line.contains("=")) {
                    String[] parts = line.split("=", 2);
                    LIBS.put(parts[0].trim(), parts[1].trim());
                }
            }
        }
    }

    private static String getLib(String name) {
        Path p = ROOT.resolve("lib").resolve(name + ".jar");
        return p.toAbsolutePath().toString();
    }

    private static void loadEnv() throws IOException {
        Path p = ROOT.resolve(".env");
        if (Files.exists(p)) {
            for (String line : Files.readAllLines(p)) {
                if (line.contains("=") && !line.startsWith("#")) {
                    String[] parts = line.split("=", 2);
                    String key = parts[0].trim();
                    String val = parts[1].trim();
                    if (key.equals("ECLIPSE_HOME")) ECLIPSE_HOME = val;
                    if (key.equals("JAVA_HOME")) JAVA_HOME = val;
                }
            }
        }
        if (ECLIPSE_HOME == null) ECLIPSE_HOME = System.getenv("ECLIPSE_HOME");
        if (JAVA_HOME == null) JAVA_HOME = System.getenv("JAVA_HOME");
    }

    private static void parseArgs(String[] args) {
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--eclipse") && i + 1 < args.length) ECLIPSE_HOME = args[++i];
            if (args[i].equals("--java") && i + 1 < args.length) JAVA_HOME = args[++i];
        }
    }

    private static String getJavac() { return JAVA_HOME != null ? Path.of(JAVA_HOME, "bin/javac").toString() : "javac"; }
    private static String getJar() { return JAVA_HOME != null ? Path.of(JAVA_HOME, "bin/jar").toString() : "jar"; }
    private static String getJava() { return JAVA_HOME != null ? Path.of(JAVA_HOME, "bin/java").toString() : "java"; }

    private static void runProcess(List<String> args) throws Exception { runProcess(args, ROOT); }
    private static void runProcess(List<String> args, Path dir) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(args);
        pb.directory(dir.toFile());
        pb.inheritIO();
        if (!pb.environment().containsKey("REPO_ROOT")) {
            pb.environment().put("REPO_ROOT", ROOT.toString());
        }
        Process p = pb.start();
        int code = p.waitFor();
        if (code != 0) throw new RuntimeException("Process failed with code " + code + ": " + args);
    }

    private static void step(String name, Task task) throws Exception {
        System.out.println("  [step] " + name + "...");
        task.run();
    }

    private static void ensureLibDir() throws IOException { Files.createDirectories(ROOT.resolve("lib")); }
    private static void deleteDir(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (var s = Files.walk(p)) {
            s.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(f -> { f.setWritable(true); f.delete(); });
        }
    }

    @FunctionalInterface interface Task { void run() throws Exception; }
}
