package org.equimacs.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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

class MoldableInspectorTest {
    private static EqmdHarness harness;

    @BeforeAll
    static void startDaemon() throws Exception {
        harness = EqmdHarness.startWithFixture("nav-java");
        JsonObject build = harness.rpc().request(new Request.Build("nav-java", "full"));
        assertTrue(build.has("result"), build::toString);
    }

    @AfterAll
    static void stopDaemon() throws Exception {
        if (harness != null) harness.close();
    }

    @Test
    void inspectSymbolReturnsMultiTabSpecForTypeAndMethod() throws Exception {
        Path greeterFile = harness.workspace().resolve("nav-java/src/Greeter.java");
        String src = Files.readString(greeterFile);
        int typeOffset = src.indexOf("Greeter");
        assertTrue(typeOffset >= 0, src);

        JsonObject typeResp = harness.rpc().request(
            new Request.InspectSymbol("/nav-java/src/Greeter.java", typeOffset, Boolean.FALSE, null));
        assertTrue(typeResp.has("result"), typeResp::toString);

        JsonObject typeResult = typeResp.getAsJsonObject("result");
        assertEquals("jdt-symbol", typeResult.get("targetKind").getAsString(), typeResp::toString);
        List<String> typeTabIds = tabIds(typeResult.getAsJsonArray("tabs"));
        assertTrue(typeTabIds.contains("overview"), typeResp::toString);
        assertTrue(typeTabIds.contains("members"), typeResp::toString);
        assertTrue(typeTabIds.contains("references"), typeResp::toString);
        assertTrue(typeTabIds.contains("hierarchy"), typeResp::toString);
        assertEquals("tabs", typeResult.getAsJsonObject("spec").get("kind").getAsString(), typeResp::toString);

        int methodOffset = src.indexOf("greet");
        assertTrue(methodOffset >= 0, src);
        JsonObject methodResp = harness.rpc().request(
            new Request.InspectSymbol("/nav-java/src/Greeter.java", methodOffset, Boolean.FALSE, null));
        assertTrue(methodResp.has("result"), methodResp::toString);

        JsonObject methodResult = methodResp.getAsJsonObject("result");
        List<String> methodTabIds = tabIds(methodResult.getAsJsonArray("tabs"));
        assertTrue(methodTabIds.contains("overview"), methodResp::toString);
        assertTrue(methodTabIds.contains("references"), methodResp::toString);
        assertTrue(methodTabIds.contains("calls"), methodResp::toString);
    }

    @Test
    void externalOsgiBundleContributesMoldableInspectorTab() throws Exception {
        Path bundleJar = buildTestProviderBundle(harness.workspace().resolve("ext-inspector-bundle"));
        String fileUri = bundleJar.toUri().toString();

        JsonObject installResp = harness.rpc().request(new Request.GogoExec("install " + fileUri));
        assertTrue(installResp.has("result"), installResp::toString);
        String bundleId = extractBundleId(installResp.get("result").getAsString());

        try {
            JsonObject startResp = harness.rpc().request(new Request.GogoExec("start " + bundleId));
            assertTrue(startResp.has("result"), startResp::toString);

            JsonObject providersResp = harness.rpc().request(new Request.ListInspectProviders());
            assertTrue(providersResp.has("result"), providersResp::toString);
            JsonArray providers = providersResp.getAsJsonArray("result");
            boolean foundExternal = false;
            for (JsonElement el : providers) {
                JsonObject item = el.getAsJsonObject();
                if ("test.custom.moldable".equals(item.get("id").getAsString())) {
                    foundExternal = true;
                    break;
                }
            }
            assertTrue(foundExternal, providersResp::toString);

            Path greeterFile = harness.workspace().resolve("nav-java/src/Greeter.java");
            int typeOffset = Files.readString(greeterFile).indexOf("Greeter");
            JsonObject inspectResp = harness.rpc().request(
                new Request.InspectSymbol("/nav-java/src/Greeter.java", typeOffset, Boolean.FALSE, null));
            assertTrue(inspectResp.has("result"), inspectResp::toString);

            JsonObject inspectResult = inspectResp.getAsJsonObject("result");
            List<String> ids = tabIds(inspectResult.getAsJsonArray("tabs"));
            assertTrue(ids.contains("custom-ext-tab"), inspectResp::toString);

            List<String> tabLabels = new ArrayList<>();
            for (JsonElement el : inspectResult.getAsJsonObject("spec").getAsJsonArray("tabs")) {
                tabLabels.add(el.getAsJsonObject().get("label").getAsString());
            }
            assertTrue(tabLabels.contains("External Tab"), inspectResp::toString);
        } finally {
            harness.rpc().request(new Request.GogoExec("uninstall " + bundleId));
        }
    }

    private static List<String> tabIds(JsonArray tabs) {
        List<String> ids = new ArrayList<>();
        for (JsonElement el : tabs) {
            ids.add(el.getAsJsonObject().get("id").getAsString());
        }
        return ids;
    }

    private static String extractBundleId(String gogoOutput) {
        Matcher m = Pattern.compile("Bundle ID:\\s*(\\d+)").matcher(gogoOutput);
        if (!m.find()) {
            throw new AssertionError("Could not parse Bundle ID from: " + gogoOutput);
        }
        return m.group(1);
    }

    private static Path buildTestProviderBundle(Path dir) throws IOException {
        Path srcDir = dir.resolve("src/org/equimacs/test/moldable");
        Path classesDir = dir.resolve("classes");
        Files.createDirectories(srcDir);
        Files.createDirectories(classesDir);

        String source = """
            package org.equimacs.test.moldable;

            import java.util.List;
            import org.equimacs.eclipse.bridge.api.IMoldableViewProvider;
            import org.equimacs.eclipse.bridge.api.InspectContext;
            import org.equimacs.eclipse.bridge.api.MoldableTab;
            import org.osgi.framework.BundleActivator;
            import org.osgi.framework.BundleContext;
            import org.osgi.framework.ServiceRegistration;

            public class TestMoldableActivator implements BundleActivator, IMoldableViewProvider {
                private ServiceRegistration<IMoldableViewProvider> reg;

                @Override
                public void start(BundleContext context) {
                    reg = context.registerService(IMoldableViewProvider.class, this, null);
                }

                @Override
                public void stop(BundleContext context) {
                    if (reg != null) {
                        reg.unregister();
                        reg = null;
                    }
                }

                @Override
                public String id() {
                    return "test.custom.moldable";
                }

                @Override
                public boolean supports(InspectContext context) {
                    return "jdt-symbol".equals(context.targetKind());
                }

                @Override
                public List<MoldableTab> provideViews(InspectContext context) {
                    String spec = "{\\"kind\\":\\"markdown\\",\\"content\\":\\"# Custom Tab\\\\nTarget: "
                        + context.title() + "\\"}";
                    return List.of(new MoldableTab("custom-ext-tab", "External Tab", 5, spec));
                }
            }
            """;

        Path javaFile = srcDir.resolve("TestMoldableActivator.java");
        Files.writeString(javaFile, source, StandardCharsets.UTF_8);

        Path repo = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        String classpath = String.join(System.getProperty("path.separator"), List.of(
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
        main.putValue("Bundle-SymbolicName", "org.equimacs.test.moldable");
        main.putValue("Bundle-Version", "1.0.0");
        main.putValue("Bundle-Activator", "org.equimacs.test.moldable.TestMoldableActivator");
        main.putValue("Bundle-RequiredExecutionEnvironment", "JavaSE-26");
        main.putValue("Import-Package", "org.equimacs.eclipse.bridge.api,org.osgi.framework");

        Path jarPath = dir.resolve("org.equimacs.test.moldable_1.0.0.jar");
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jarPath), manifest)) {
            Path classFile = classesDir.resolve("org/equimacs/test/moldable/TestMoldableActivator.class");
            jos.putNextEntry(new JarEntry("org/equimacs/test/moldable/TestMoldableActivator.class"));
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
