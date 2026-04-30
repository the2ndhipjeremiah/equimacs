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
