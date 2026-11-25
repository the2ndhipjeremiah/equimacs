package org.equimacs.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.equimacs.protocol.Request;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ClasspathTest {
    private static EqmdHarness harness;

    @BeforeAll
    static void startDaemon() throws Exception {
        harness = EqmdHarness.startWithFixture("hello-java");
    }

    @AfterAll
    static void stopDaemon() throws Exception {
        if (harness != null) harness.close();
    }

    @BeforeEach
    void refreshProject() throws Exception {
        harness.rpc().request(new Request.RefreshProject("hello-java"));
    }

    @Test
    void classpathListsEntries() throws Exception {
        JsonObject response = harness.rpc().request(new Request.GetClasspath("hello-java"));

        assertTrue(response.has("result"), response::toString);
        JsonObject result = response.getAsJsonObject("result");
        assertEquals("hello-java", result.get("project").getAsString());
        assertEquals("/hello-java/bin", result.get("outputLocation").getAsString());

        JsonArray entries = result.getAsJsonArray("entries");
        assertTrue(hasEntry(entries, "source", "/hello-java/src"), result::toString);
        assertTrue(hasEntry(entries, "container", "org.eclipse.jdt.launching.JRE_CONTAINER"), result::toString);
    }

    @Test
    void setClasspathAddsAndRemovesSourceEntry() throws Exception {
        java.nio.file.Path extraSrc = harness.workspace().resolve("hello-java/extra-src");
        java.nio.file.Files.createDirectories(extraSrc);
        harness.rpc().request(new Request.RefreshProject("hello-java"));

        JsonObject added = harness.rpc().request(
            new Request.SetClasspath("hello-java", "add", "source", "/hello-java/extra-src", null));
        assertTrue(added.has("result"), added::toString);
        JsonObject addResult = added.getAsJsonObject("result");
        assertTrue(addResult.get("changed").getAsBoolean(), addResult::toString);
        assertTrue(hasEntry(addResult.getAsJsonArray("entries"), "source", "/hello-java/extra-src"), addResult::toString);

        // Idempotent second add reports changed=false
        JsonObject secondAdd = harness.rpc().request(
            new Request.SetClasspath("hello-java", "add", "source", "/hello-java/extra-src", null));
        assertTrue(secondAdd.has("result"), secondAdd::toString);
        assertEquals(false, secondAdd.getAsJsonObject("result").get("changed").getAsBoolean());

        JsonObject removed = harness.rpc().request(
            new Request.SetClasspath("hello-java", "remove", "source", "/hello-java/extra-src", null));
        assertTrue(removed.has("result"), removed::toString);
        JsonObject removeResult = removed.getAsJsonObject("result");
        assertTrue(removeResult.get("changed").getAsBoolean(), removeResult::toString);
        assertEquals(false, hasEntry(removeResult.getAsJsonArray("entries"), "source", "/hello-java/extra-src"));
    }

    private static boolean hasEntry(JsonArray entries, String kind, String path) {
        for (JsonElement element : entries) {
            JsonObject entry = element.getAsJsonObject();
            if (entry.get("kind").getAsString().equals(kind)
                && entry.get("path").getAsString().equals(path)) {
                return true;
            }
        }
        return false;
    }
}
