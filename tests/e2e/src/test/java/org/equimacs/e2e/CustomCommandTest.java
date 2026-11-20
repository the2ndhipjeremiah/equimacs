package org.equimacs.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.equimacs.protocol.Request;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class CustomCommandTest {
    private static EqmdHarness harness;

    @BeforeAll
    static void startDaemon() throws Exception {
        harness = EqmdHarness.start();
    }

    @AfterAll
    static void stopDaemon() throws Exception {
        if (harness != null) harness.close();
    }

    @Test
    void unknownCustomTargetReturnsClearError() throws Exception {
        JsonObject response = harness.rpc().request(
            new Request.CustomCommand("missing-target", "ping", null));
        assertTrue(response.has("message"), response::toString);
        String msg = response.get("message").getAsString();
        assertTrue(msg.contains("missing-target"), msg);
    }

    @Test
    void externalOsgiBundleRegistersAndHandlesCustomCommand() throws Exception {
        Path bundleJar = buildTestExtensionBundle(harness.workspace().resolve("ext-bundle"));
        String fileUri = bundleJar.toUri().toString();

        JsonObject installResp = harness.rpc().request(new Request.GogoExec("install " + fileUri));
        assertTrue(installResp.has("result"), installResp::toString);
        String bundleId = extractBundleId(installResp.get("result").getAsString());

        try {
            JsonObject startResp = harness.rpc().request(new Request.GogoExec("start " + bundleId));
            assertTrue(startResp.has("result"), startResp::toString);

            JsonObject response = harness.rpc().request(
                new Request.CustomCommand("demo-ext", "ping", "{\"msg\":\"hello\"}"));
            assertTrue(response.has("result"), response::toString);

            JsonObject result = response.getAsJsonObject("result");
            assertEquals("demo-ext", result.get("target").getAsString());
            assertEquals("ping", result.get("action").getAsString());
            assertEquals("{\"msg\":\"hello\"}", result.get("args").getAsString());
        } finally {
            harness.rpc().request(new Request.GogoExec("uninstall " + bundleId));
        }
    }

    private static String extractBundleId(String gogoOutput) {
        Matcher m = Pattern.compile("Bundle ID:\\s*(\\d+)").matcher(gogoOutput);
        if (!m.find()) {
            throw new AssertionError("Could not parse Bundle ID from: " + gogoOutput);
        }
        return m.group(1);
    }

    private static Path buildTestExtensionBundle(Path dir) throws IOException {
        Path srcDir = dir.resolve("src/org/equimacs/test/custom");
        Path classesDir = dir.resolve("classes");
        Files.createDirectories(srcDir);
        Files.createDirectories(classesDir);

        String source = """
            package org.equimacs.test.custom;

            import java.util.Hashtable;
            import java.util.LinkedHashMap;
            import java.util.Map;
            import org.equimacs.eclipse.bridge.api.IBridgeCommandHandler;
            import org.equimacs.protocol.Request;
            import org.osgi.framework.BundleActivator;
            import org.osgi.framework.BundleContext;
            import org.osgi.framework.ServiceRegistration;

            public class TestCustomActivator implements BundleActivator, IBridgeCommandHandler {
                private ServiceRegistration<IBridgeCommandHandler> reg;

                @Override
                public void start(BundleContext context) {
                    Hashtable<String, Object> props = new Hashtable<>();
                    props.put("equimacs.commands", "custom:demo-ext");
                    reg = context.registerService(IBridgeCommandHandler.class, this, props);
                }

                @Override
                public void stop(BundleContext context) {
                    if (reg != null) {
                        reg.unregister();
                        reg = null;
                    }
                }

                @Override
                public Object handle(Request req) {
                    Request.CustomCommand cmd = (Request.CustomCommand) req;
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("target", cmd.target());
                    out.put("action", cmd.action());
                    out.put("args", cmd.args());
                    return out;
                }
            }
            """;

        Path javaFile = srcDir.resolve("TestCustomActivator.java");
        Files.writeString(javaFile, source, StandardCharsets.UTF_8);

        Path repo = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        String classpath = String.join(System.getProperty("path.separator"), List.of(
            repo.resolve("libs/protocol/build/libs/protocol.jar").toString(),
            repo.resolve("plugins/org.equimacs.eclipse.bridge/build/classes").toString(),
            findOsgiFrameworkJar(repo).toString()
        ));

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int rc = compiler.run(null, null, err,
            "-d", classesDir.toString(),
            "--release", "26",
            "-cp", classpath,
            javaFile.toString());
        if (rc != 0) {
            throw new IllegalStateException("Failed to compile test bundle:\n" + err);
        }

        Manifest manifest = new Manifest();
        Attributes main = manifest.getMainAttributes();
        main.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        main.putValue("Bundle-ManifestVersion", "2");
        main.putValue("Bundle-SymbolicName", "org.equimacs.test.custom");
        main.putValue("Bundle-Version", "1.0.0");
        main.putValue("Bundle-Activator", "org.equimacs.test.custom.TestCustomActivator");
        main.putValue("Bundle-RequiredExecutionEnvironment", "JavaSE-26");
        main.putValue("Import-Package",
            "org.equimacs.eclipse.bridge.api,org.equimacs.protocol,org.osgi.framework");

        Path jarPath = dir.resolve("org.equimacs.test.custom_1.0.0.jar");
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jarPath), manifest)) {
            Path classFile = classesDir.resolve("org/equimacs/test/custom/TestCustomActivator.class");
            jos.putNextEntry(new JarEntry("org/equimacs/test/custom/TestCustomActivator.class"));
            jos.write(Files.readAllBytes(classFile));
            jos.closeEntry();
        }
        return jarPath;
    }

    private static Path findOsgiFrameworkJar(Path repo) throws IOException {
        String eclipseHome = System.getenv("ECLIPSE_HOME");
        if (eclipseHome == null || eclipseHome.isBlank()) {
            Path envFile = repo.resolve(".env");
            if (Files.exists(envFile)) {
                for (String line : Files.readAllLines(envFile)) {
                    if (line.startsWith("ECLIPSE_HOME=")) {
                        eclipseHome = line.substring("ECLIPSE_HOME=".length()).trim();
                    }
                }
            }
        }
        Path plugins = Path.of(eclipseHome, "plugins");
        try (Stream<Path> stream = Files.list(plugins)) {
            return stream
                .filter(p -> p.getFileName().toString().startsWith("org.eclipse.osgi_"))
                .filter(p -> p.toString().endsWith(".jar"))
                .findFirst()
                .orElseThrow(() -> new IOException("org.eclipse.osgi_*.jar not found in " + plugins));
        }
    }
}
