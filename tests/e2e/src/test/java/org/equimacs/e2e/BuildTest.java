package org.equimacs.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import org.equimacs.protocol.Request;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BuildTest {
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
    void buildFullSucceeds() throws Exception {
        JsonObject response = harness.rpc().request(new Request.Build("hello-java", "full"));

        assertTrue(response.has("result"), response::toString);
        assertEquals("Build complete: hello-java", response.get("result").getAsString());
    }
}
