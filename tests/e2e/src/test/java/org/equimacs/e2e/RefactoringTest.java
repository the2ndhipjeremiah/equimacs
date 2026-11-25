package org.equimacs.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import org.equimacs.protocol.Request;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RefactoringTest {
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
    void prepareAndApplyRenameSymbolChangesJavaSource() throws Exception {
        Path source = harness.workspace().resolve("hello-java/src/Hello.java");
        String before = Files.readString(source);
        int offset = before.indexOf("value");
        assertTrue(offset >= 0, before);

        JsonObject preview = harness.rpc().request(
            new Request.PrepareRenameSymbol("/hello-java/src/Hello.java", offset, "renamedValue"));
        assertTrue(preview.has("result"), preview::toString);

        JsonObject result = preview.getAsJsonObject("result");
        assertEquals("rename-symbol", result.get("kind").getAsString(), result::toString);
        assertTrue(result.getAsJsonObject("status").get("ok").getAsBoolean(), result::toString);
        assertTrue(result.getAsJsonObject("summary").get("filesChanged").getAsInt() >= 1, result::toString);

        JsonArray changes = result.getAsJsonArray("changes");
        assertTrue(changes.size() >= 1, result::toString);

        String id = result.get("refactoringId").getAsString();
        JsonObject apply = harness.rpc().request(new Request.ApplyPreparedRefactoring(id));
        assertTrue(apply.has("result"), apply::toString);
        assertTrue(apply.getAsJsonObject("result").get("applied").getAsBoolean(), apply::toString);

        String after = Files.readString(source);
        assertTrue(after.contains("renamedValue"), after);
        assertFalse(after.contains("int value ="), after);

        // Restore original content so other tests see clean fixture state.
        Files.writeString(source, before);
        harness.rpc().request(new Request.RefreshProject("hello-java"));
    }

    @Test
    void stalePreparedRefactoringIsRejectedOnApply() throws Exception {
        Path source = harness.workspace().resolve("hello-java/src/Hello.java");
        String original = Files.readString(source);
        int offset = original.indexOf("value");
        assertTrue(offset >= 0, original);

        JsonObject preview = harness.rpc().request(
            new Request.PrepareRenameSymbol("/hello-java/src/Hello.java", offset, "staleTarget"));
        assertTrue(preview.has("result"), preview::toString);
        String id = preview.getAsJsonObject("result").get("refactoringId").getAsString();

        try {
            // Modify the file after prepare so the prepared Change becomes stale.
            String modified = "// modified after prepare\n" + original;
            Files.writeString(source, modified);
            harness.rpc().request(new Request.RefreshProject("hello-java"));

            JsonObject apply = harness.rpc().request(new Request.ApplyPreparedRefactoring(id));
            assertFalse(apply.has("result"), "Expected stale refactoring error, got: " + apply);
            assertTrue(apply.has("message"), "Expected error message field, got: " + apply);
            assertTrue(apply.get("message").getAsString().contains("no longer valid"), apply::toString);

            String current = Files.readString(source);
            assertFalse(current.contains("staleTarget"), current);
        } finally {
            Files.writeString(source, original);
            harness.rpc().request(new Request.RefreshProject("hello-java"));
        }
    }

    @Test
    void crossFileMethodRenameUpdatesInterfaceImplAndCallers() throws Exception {
        try (EqmdHarness navHarness = EqmdHarness.startWithFixture("nav-java")) {
            navHarness.rpc().request(new Request.RefreshProject("nav-java"));

            Path greeterFile = navHarness.workspace().resolve("nav-java/src/Greeter.java");
            Path implFile = navHarness.workspace().resolve("nav-java/src/FriendlyGreeter.java");
            Path appFile = navHarness.workspace().resolve("nav-java/src/App.java");

            String greeterSrc = Files.readString(greeterFile);
            int offset = greeterSrc.indexOf("greet");
            assertTrue(offset >= 0, greeterSrc);

            JsonObject preview = navHarness.rpc().request(
                new Request.PrepareRenameSymbol("/nav-java/src/Greeter.java", offset, "salute"));
            assertTrue(preview.has("result"), preview::toString);
            JsonObject result = preview.getAsJsonObject("result");
            assertTrue(result.getAsJsonObject("summary").get("filesChanged").getAsInt() >= 3, result::toString);

            String id = result.get("refactoringId").getAsString();
            JsonObject apply = navHarness.rpc().request(new Request.ApplyPreparedRefactoring(id));
            assertTrue(apply.has("result"), apply::toString);
            assertTrue(apply.getAsJsonObject("result").get("applied").getAsBoolean(), apply::toString);

            assertTrue(Files.readString(greeterFile).contains("salute("));
            assertTrue(Files.readString(implFile).contains("salute("));
            assertTrue(Files.readString(appFile).contains(".salute("));
        }
    }
}
