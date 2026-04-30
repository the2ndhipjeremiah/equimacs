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

class ProblemsTest {
    private static EqmdHarness harness;

    @BeforeAll
    static void startDaemon() throws Exception {
        harness = EqmdHarness.startWithFixture("broken-java");
    }

    @AfterAll
    static void stopDaemon() throws Exception {
        if (harness != null) harness.close();
    }

    @BeforeEach
    void refreshProject() throws Exception {
        harness.rpc().request(new Request.RefreshProject("broken-java"));
    }

    @Test
    void problemsQuickfixesAndApplyfixUseJdtMarkers() throws Exception {
        JsonObject build = harness.rpc().request(new Request.Build("broken-java", "full"));
        assertTrue(build.has("result"), build::toString);

        JsonObject problems = harness.rpc().request(new Request.GetProblems("broken-java", "error"));
        assertTrue(problems.has("result"), problems::toString);
        JsonArray markers = problems.getAsJsonArray("result");
        assertTrue(hasProblem(markers, "/broken-java/src/Broken.java", 3, "List"), problems::toString);
        assertTrue(hasProblem(markers, "/broken-java/src/Broken.java", 3, "ArrayList"), problems::toString);

        JsonObject quickfixes = harness.rpc().request(new Request.GetQuickFixes("/broken-java/src/Broken.java", 3));
        assertTrue(quickfixes.has("result"), quickfixes::toString);
        int importListFix = findFixIndex(quickfixes.getAsJsonArray("result"), "Import 'List'");
        assertTrue(importListFix >= 0, quickfixes::toString);

        JsonObject applyfix = harness.rpc().request(
            new Request.ApplyFix("/broken-java/src/Broken.java", 3, importListFix));
        assertTrue(applyfix.has("result"), applyfix::toString);
        assertTrue(applyfix.get("result").getAsString().startsWith("Applied fix ["), applyfix::toString);

        JsonObject after = harness.rpc().request(new Request.GetQuickFixes("/broken-java/src/Broken.java", 3));
        assertTrue(after.has("result"), after::toString);
        assertEquals(-1, findFixIndex(after.getAsJsonArray("result"), "Import 'List'"), after::toString);
    }

    private static boolean hasProblem(JsonArray markers, String file, int line, String messageFragment) {
        for (JsonElement element : markers) {
            JsonObject marker = element.getAsJsonObject();
            if (marker.get("file").getAsString().equals(file)
                && marker.get("line").getAsInt() == line
                && marker.get("message").getAsString().contains(messageFragment)) {
                return true;
            }
        }
        return false;
    }

    private static int findFixIndex(JsonArray fixes, String labelFragment) {
        for (JsonElement element : fixes) {
            JsonObject fix = element.getAsJsonObject();
            if (fix.get("label").getAsString().contains(labelFragment)) {
                return fix.get("index").getAsInt();
            }
        }
        return -1;
    }
}
