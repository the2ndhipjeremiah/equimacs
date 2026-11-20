package org.equimacs.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.equimacs.protocol.Request;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class NavigationTest {
    private static EqmdHarness harness;

    @BeforeAll
    static void startDaemon() throws Exception {
        harness = EqmdHarness.startWithFixture("nav-java");
        harness.rpc().request(new Request.Build("nav-java", "full"));
    }

    @AfterAll
    static void stopDaemon() throws Exception {
        if (harness != null) harness.close();
    }

    @Test
    void findReferencesLocatesAllCallSitesOfMethod() throws Exception {
        Path greeterFile = harness.workspace().resolve("nav-java/src/Greeter.java");
        String src = Files.readString(greeterFile);
        int offset = src.indexOf("greet");
        assertTrue(offset >= 0, src);

        JsonObject resp = harness.rpc().request(
            new Request.FindReferences("/nav-java/src/Greeter.java", offset));
        assertTrue(resp.has("result"), resp::toString);

        JsonObject result = resp.getAsJsonObject("result");
        assertTrue(result.get("count").getAsInt() >= 2, result::toString);
        JsonArray refs = result.getAsJsonArray("references");
        List<String> files = new ArrayList<>();
        for (JsonElement el : refs) {
            files.add(el.getAsJsonObject().get("file").getAsString());
        }
        assertTrue(files.stream().anyMatch(f -> f.endsWith("App.java")), files::toString);
    }

    @Test
    void callHierarchyFindsCallersAndCallees() throws Exception {
        Path greeterFile = harness.workspace().resolve("nav-java/src/Greeter.java");
        int greetOffset = Files.readString(greeterFile).indexOf("greet");

        JsonObject callersResp = harness.rpc().request(
            new Request.CallHierarchy("/nav-java/src/Greeter.java", greetOffset, "callers", 2));
        assertTrue(callersResp.has("result"), callersResp::toString);
        JsonArray callerNodes = callersResp.getAsJsonObject("result").getAsJsonArray("nodes");
        assertTrue(callerNodes.size() >= 2, callersResp::toString);

        Path friendlyFile = harness.workspace().resolve("nav-java/src/FriendlyGreeter.java");
        int friendlyGreetOffset = Files.readString(friendlyFile).indexOf("greet");

        JsonObject calleesResp = harness.rpc().request(
            new Request.CallHierarchy("/nav-java/src/FriendlyGreeter.java", friendlyGreetOffset, "callees", 2));
        assertTrue(calleesResp.has("result"), calleesResp::toString);
        JsonArray calleeNodes = calleesResp.getAsJsonObject("result").getAsJsonArray("nodes");
        assertEquals(1, calleeNodes.size(), calleesResp::toString);
        String calleeName = calleeNodes.get(0).getAsJsonObject()
            .getAsJsonObject("element").get("name").getAsString();
        assertEquals("format", calleeName);
    }

    @Test
    void typeHierarchyReturnsSubtypesAndInterfaces() throws Exception {
        Path greeterFile = harness.workspace().resolve("nav-java/src/Greeter.java");
        int typeOffset = Files.readString(greeterFile).indexOf("Greeter");

        JsonObject resp = harness.rpc().request(
            new Request.TypeHierarchy("/nav-java/src/Greeter.java", typeOffset));
        assertTrue(resp.has("result"), resp::toString);

        JsonObject result = resp.getAsJsonObject("result");
        assertEquals("Greeter", result.getAsJsonObject("target").get("name").getAsString());
        JsonArray subtypes = result.getAsJsonArray("subtypes");
        assertEquals(1, subtypes.size(), result::toString);
        assertEquals("FriendlyGreeter", subtypes.get(0).getAsJsonObject().get("name").getAsString());
    }
}
