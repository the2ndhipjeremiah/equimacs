package org.equimacs.eclipse.ui.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReviewCoreTest {

    @Test
    void lineAnchorCaptureAndUnchangedResolution() {
        List<String> lines = List.of(
            "package demo;",
            "",
            "public class App {",
            "    int count = 42;",
            "    return count;",
            "}"
        );

        assertNull(LineAnchorMatcher.capture(lines, 0));
        assertNull(LineAnchorMatcher.capture(lines, 7));
        assertNull(LineAnchorMatcher.capture(null, 1));

        LineAnchor firstLineAnchor = LineAnchorMatcher.capture(lines, 1);
        assertNotNull(firstLineAnchor);
        assertEquals("", firstLineAnchor.contextBefore());
        assertEquals("\npublic class App {", firstLineAnchor.contextAfter());
        assertEquals(LineAnchor.STATUS_EXACT, firstLineAnchor.status());

        LineAnchor anchor = LineAnchorMatcher.capture(lines, 4);
        assertNotNull(anchor);
        assertEquals("int count = 42;", anchor.lineText());
        assertEquals(LineAnchorMatcher.hashLine("  int count = 42;  "), anchor.lineHash());
        assertEquals("\npublic class App {", anchor.contextBefore());
        assertEquals("return count;\n}", anchor.contextAfter());

        // Re-indenting without changing trimmed content stays Unchanged at line 4.
        List<String> reindented = List.of(
            "package demo;",
            "",
            "public class App {",
            "        int count = 42;",
            "    return count;",
            "}"
        );
        LineAnchorMatcher.AnchorResolution res = LineAnchorMatcher.resolve(4, anchor, reindented);
        LineAnchorMatcher.AnchorResolution.Unchanged unchanged =
            assertInstanceOf(LineAnchorMatcher.AnchorResolution.Unchanged.class, res);
        assertEquals(4, unchanged.line());
    }

    @Test
    void lineAnchorShiftedDownAndShiftedUp() {
        List<String> original = List.of(
            "line 1",
            "line 2",
            "target line",
            "line 4",
            "line 5"
        );
        LineAnchor anchor = LineAnchorMatcher.capture(original, 3);
        assertNotNull(anchor);

        List<String> shiftedDown = List.of(
            "inserted 1",
            "inserted 2",
            "inserted 3",
            "line 1",
            "line 2",
            "target line",
            "line 4",
            "line 5"
        );
        LineAnchorMatcher.AnchorResolution downRes = LineAnchorMatcher.resolve(3, anchor, shiftedDown);
        LineAnchorMatcher.AnchorResolution.Relocated down =
            assertInstanceOf(LineAnchorMatcher.AnchorResolution.Relocated.class, downRes);
        assertEquals(3, down.oldLine());
        assertEquals(6, down.newLine());
        assertEquals("hash-match", down.reason());

        List<String> shiftedUp = List.of(
            "line 2",
            "target line",
            "line 4",
            "line 5"
        );
        LineAnchorMatcher.AnchorResolution upRes = LineAnchorMatcher.resolve(3, anchor, shiftedUp);
        LineAnchorMatcher.AnchorResolution.Relocated up =
            assertInstanceOf(LineAnchorMatcher.AnchorResolution.Relocated.class, upRes);
        assertEquals(3, up.oldLine());
        assertEquals(2, up.newLine());
        assertEquals("hash-match", up.reason());
    }

    @Test
    void lineAnchorDisambiguatesDuplicateLinesByContextAndDistance() {
        List<String> original = List.of(
            "void first() {",
            "    log();",
            "    return;",
            "}",
            "void second() {",
            "    compute();",
            "    return;",
            "}"
        );
        LineAnchor secondReturnAnchor = LineAnchorMatcher.capture(original, 7);
        assertNotNull(secondReturnAnchor);

        List<String> shifted = List.of(
            "// extra comment",
            "void first() {",
            "    log();",
            "    return;",
            "}",
            "void second() {",
            "    compute();",
            "    return;",
            "}"
        );
        LineAnchorMatcher.AnchorResolution res = LineAnchorMatcher.resolve(7, secondReturnAnchor, shifted);
        LineAnchorMatcher.AnchorResolution.Relocated relocated =
            assertInstanceOf(LineAnchorMatcher.AnchorResolution.Relocated.class, res);
        assertEquals(8, relocated.newLine());
        assertEquals("context-disambiguated", relocated.reason());

        // Tie-break by distance when duplicate occurrences have identical context.
        List<String> repeatedBlocks = List.of(
            "alpha",
            "return;",
            "omega",
            "spacer",
            "alpha",
            "return;",
            "omega",
            "spacer",
            "alpha",
            "return;",
            "omega"
        );
        LineAnchor tieAnchor = LineAnchorMatcher.capture(repeatedBlocks, 10);
        LineAnchorMatcher.AnchorResolution tieRes = LineAnchorMatcher.resolve(9, tieAnchor, repeatedBlocks);
        LineAnchorMatcher.AnchorResolution.Relocated tieRelocated =
            assertInstanceOf(LineAnchorMatcher.AnchorResolution.Relocated.class, tieRes);
        assertEquals(10, tieRelocated.newLine());
        assertEquals("context-disambiguated", tieRelocated.reason());
    }

    @Test
    void lineAnchorContextWindowFallbackAndStaleAndNullPassthrough() {
        List<String> original = List.of(
            "int a = 1;",
            "int b = 2;",
            "int x = a + b;",
            "int c = 3;",
            "return x + c;"
        );
        LineAnchor anchor = LineAnchorMatcher.capture(original, 3);
        assertNotNull(anchor);

        // Target line edited AND shifted down, surrounding context intact.
        List<String> editedAndShifted = List.of(
            "// header",
            "int a = 1;",
            "int b = 2;",
            "int x = (a + b) * 2;",
            "int c = 3;",
            "return x + c;"
        );
        LineAnchorMatcher.AnchorResolution windowRes = LineAnchorMatcher.resolve(3, anchor, editedAndShifted);
        LineAnchorMatcher.AnchorResolution.Relocated windowRelocated =
            assertInstanceOf(LineAnchorMatcher.AnchorResolution.Relocated.class, windowRes);
        assertEquals(4, windowRelocated.newLine());
        assertEquals("context-window", windowRelocated.reason());

        // Both line and surrounding context broken -> Stale clamped to file length.
        List<String> rewrittenShorter = List.of("completely", "different");
        LineAnchorMatcher.AnchorResolution staleRes = LineAnchorMatcher.resolve(10, anchor, rewrittenShorter);
        LineAnchorMatcher.AnchorResolution.Stale stale =
            assertInstanceOf(LineAnchorMatcher.AnchorResolution.Stale.class, staleRes);
        assertEquals(2, stale.fallbackLine());
        assertEquals("line-modified-or-deleted", stale.reason());

        // Null anchor / null hash / empty lines passthrough.
        LineAnchorMatcher.AnchorResolution nullAnchorRes = LineAnchorMatcher.resolve(5, null, original);
        LineAnchorMatcher.AnchorResolution.Unchanged nullAnchorUnchanged =
            assertInstanceOf(LineAnchorMatcher.AnchorResolution.Unchanged.class, nullAnchorRes);
        assertEquals(5, nullAnchorUnchanged.line());

        LineAnchorMatcher.AnchorResolution emptyLinesRes = LineAnchorMatcher.resolve(5, anchor, List.of());
        LineAnchorMatcher.AnchorResolution.Unchanged emptyUnchanged =
            assertInstanceOf(LineAnchorMatcher.AnchorResolution.Unchanged.class, emptyLinesRes);
        assertEquals(5, emptyUnchanged.line());
    }

    @Test
    void reviewStoreCoreCapturesReanchorsMarksStaleAndLoadsLegacyJson(@TempDir Path tempDir) throws Exception {
        Path storePath = tempDir.resolve(".equimacs-review/comments.json");
        Map<String, List<String>> workspaceFiles = new HashMap<>();
        String file = "/proj/src/App.java";
        workspaceFiles.put(file, List.of(
            "class App {",
            "    void run() {",
            "        work();",
            "    }",
            "}"
        ));

        ReviewStoreCore store = new ReviewStoreCore(() -> storePath, workspaceFiles::get);
        ReviewComment added = store.addComment(file, 3, "alice", "Check work()");
        assertNotNull(added.anchor());
        assertEquals("work();", added.anchor().lineText());
        assertEquals(LineAnchor.STATUS_EXACT, added.anchor().status());

        // Shift line down by 2 lines and reanchor all.
        workspaceFiles.put(file, List.of(
            "// copyright",
            "// license",
            "class App {",
            "    void run() {",
            "        work();",
            "    }",
            "}"
        ));
        ReviewStoreCore.ReanchorReport shiftedReport = store.reanchorAll();
        assertEquals(new ReviewStoreCore.ReanchorReport(0, 1, 0, true), shiftedReport);

        List<ReviewComment> afterShift = store.list(file);
        assertEquals(1, afterShift.size());
        assertEquals(5, afterShift.get(0).line());
        assertNotNull(afterShift.get(0).anchor());
        assertEquals(LineAnchor.STATUS_RELOCATED, afterShift.get(0).anchor().status());

        // Idempotent second pass does not overwrite relocated status or mark modified.
        ReviewStoreCore.ReanchorReport secondReport = store.reanchorAll();
        assertEquals(new ReviewStoreCore.ReanchorReport(1, 0, 0, false), secondReport);
        assertEquals(LineAnchor.STATUS_RELOCATED, store.list(file).get(0).anchor().status());

        // Break both line and context -> marked stale without deleting comment, line clamped.
        ReviewStoreCore.ReanchorReport staleReport = store.reanchorFile(file, List.of("class App {", "}"));
        assertEquals(new ReviewStoreCore.ReanchorReport(0, 0, 1, true), staleReport);
        List<ReviewComment> afterStale = store.list(file);
        assertEquals(1, afterStale.size());
        assertEquals(2, afterStale.get(0).line());
        assertEquals(LineAnchor.STATUS_STALE, afterStale.get(0).anchor().status());

        // Legacy comments.json without "anchor" loads and survives reanchor/reply/resolve.
        String legacyJson = """
            [
              {
                "id": "legacy-1",
                "file": "/proj/src/Legacy.java",
                "line": 12,
                "author": "bob",
                "text": "Legacy thread",
                "timestamp": 1700000000000,
                "replies": [],
                "resolved": false
              }
            ]
            """;
        Files.writeString(storePath, legacyJson, StandardCharsets.UTF_8);

        List<ReviewComment> legacyLoaded = store.list(null);
        assertEquals(1, legacyLoaded.size());
        assertNull(legacyLoaded.get(0).anchor());

        ReviewStoreCore.ReanchorReport legacyReport = store.reanchorAll();
        assertEquals(new ReviewStoreCore.ReanchorReport(0, 0, 0, false), legacyReport);

        ReviewComment replied = store.addReply("legacy-1", "alice", "Still valid");
        assertNull(replied.anchor());
        assertEquals(1, replied.replies().size());

        ReviewComment resolved = store.resolve("legacy-1");
        assertTrue(resolved.resolved());
        assertNull(resolved.anchor());
    }

    @Test
    void gitDiffParserParsesDiffStatusAndMatchesFilters() {
        assertEquals(GitDiffModel.FilterMode.ALL, GitDiffModel.FilterMode.parse(null));
        assertEquals(GitDiffModel.FilterMode.ALL, GitDiffModel.FilterMode.parse("  "));
        assertEquals(GitDiffModel.FilterMode.ALL, GitDiffModel.FilterMode.parse("all"));
        assertEquals(GitDiffModel.FilterMode.CHANGED_FILES, GitDiffModel.FilterMode.parse("CHANGED-FILES"));
        assertEquals(GitDiffModel.FilterMode.CHANGED_HUNKS, GitDiffModel.FilterMode.parse("changed-hunks"));
        assertThrows(IllegalArgumentException.class, () -> GitDiffModel.FilterMode.parse("unknown"));

        String diffOutput = """
            diff --git a/src/App.java b/src/App.java
            index 1111111..2222222 100644
            --- a/src/App.java
            +++ b/src/App.java
            @@ -5 +10 @@ void single() {
            @@ -20,2 +25,3 @@ void multi() {
            @@ -40,3 +39,0 @@ void deleted() {
            diff --git a/src/Removed.java b/src/Removed.java
            deleted file mode 100644
            --- a/src/Removed.java
            +++ /dev/null
            @@ -1,5 +0,0 @@
            diff --git a/src/TopDeleted.java b/src/TopDeleted.java
            --- a/src/TopDeleted.java
            +++ b/src/TopDeleted.java
            @@ -1,2 +0,0 @@
            """;

        Map<String, List<GitDiffModel.LineRange>> ranges = GitDiffParser.parseUnifiedZeroDiff(diffOutput);
        assertEquals(
            List.of(
                new GitDiffModel.LineRange(10, 10),
                new GitDiffModel.LineRange(25, 27),
                new GitDiffModel.LineRange(39, 39)
            ),
            ranges.get("src/App.java")
        );
        assertFalse(ranges.containsKey("src/Removed.java"));
        assertEquals(List.of(new GitDiffModel.LineRange(1, 1)), ranges.get("src/TopDeleted.java"));

        String statusOutput = """
             M src/App.java
            R  src/OldName.java -> src/Renamed.java
            ?? "src/New File.java"
            A  src/EmptyStaged.java
            """;
        Map<String, String> statusMap = GitDiffParser.parseStatusPorcelain(statusOutput);
        assertEquals(" M", statusMap.get("src/App.java"));
        assertEquals("R ", statusMap.get("src/Renamed.java"));
        assertEquals("??", statusMap.get("src/New File.java"));
        assertEquals("A ", statusMap.get("src/EmptyStaged.java"));

        GitDiffModel.GitDiffSummary summary = GitDiffParser.buildSummary("/repo", statusOutput, diffOutput);
        assertTrue(summary.available());

        ReviewComment appAtHunk = comment("/proj/src/App.java", 26);
        ReviewComment appTolerance = comment("/proj/src/App.java", 28);
        ReviewComment appPureDelTolerance = comment("/proj/src/App.java", 40);
        ReviewComment appOutside = comment("/proj/src/App.java", 60);
        ReviewComment untracked = comment("/proj/src/New File.java", 4);
        ReviewComment emptyStaged = comment("/proj/src/EmptyStaged.java", 1);
        ReviewComment untouched = comment("/proj/src/Untouched.java", 5);

        java.util.function.Function<String, String> mapper = ws -> ws.replaceFirst("^/proj/", "");

        assertTrue(GitDiffParser.matches(untouched, summary, GitDiffModel.FilterMode.ALL, mapper));

        assertTrue(GitDiffParser.matches(appOutside, summary, GitDiffModel.FilterMode.CHANGED_FILES, mapper));
        assertTrue(GitDiffParser.matches(untracked, summary, GitDiffModel.FilterMode.CHANGED_FILES, mapper));
        assertFalse(GitDiffParser.matches(untouched, summary, GitDiffModel.FilterMode.CHANGED_FILES, mapper));

        assertTrue(GitDiffParser.matches(appAtHunk, summary, GitDiffModel.FilterMode.CHANGED_HUNKS, mapper));
        assertTrue(GitDiffParser.matches(appTolerance, summary, GitDiffModel.FilterMode.CHANGED_HUNKS, mapper));
        assertTrue(GitDiffParser.matches(appPureDelTolerance, summary, GitDiffModel.FilterMode.CHANGED_HUNKS, mapper));
        assertFalse(GitDiffParser.matches(appOutside, summary, GitDiffModel.FilterMode.CHANGED_HUNKS, mapper));
        assertTrue(GitDiffParser.matches(untracked, summary, GitDiffModel.FilterMode.CHANGED_HUNKS, mapper));
        assertTrue(GitDiffParser.matches(emptyStaged, summary, GitDiffModel.FilterMode.CHANGED_HUNKS, mapper));
        assertFalse(GitDiffParser.matches(untouched, summary, GitDiffModel.FilterMode.CHANGED_HUNKS, mapper));

        GitDiffModel.GitDiffSummary unavailable = new GitDiffModel.GitDiffSummary(false, null, Map.of());
        assertFalse(GitDiffParser.matches(appAtHunk, unavailable, GitDiffModel.FilterMode.CHANGED_FILES, mapper));
        assertTrue(GitDiffParser.matches(appAtHunk, unavailable, GitDiffModel.FilterMode.ALL, mapper));
    }

    @Test
    void editorHighlightCoreClampsDurationAndComputesLineRanges() {
        assertEquals(EditorHighlightCore.DEFAULT_DURATION_MS, EditorHighlightCore.clampDuration(0));
        assertEquals(EditorHighlightCore.DEFAULT_DURATION_MS, EditorHighlightCore.clampDuration(-50));
        assertEquals(EditorHighlightCore.MIN_DURATION_MS, EditorHighlightCore.clampDuration(10));
        assertEquals(2500, EditorHighlightCore.clampDuration(2500));
        assertEquals(EditorHighlightCore.MAX_DURATION_MS, EditorHighlightCore.clampDuration(120000));

        int[] offsets = new int[] { 0, 14, 30, 48 };
        int[] lengths = new int[] { 13, 15, 17, 1 };

        assertNull(EditorHighlightCore.computeRange(0, 1, 1, i -> offsets[i], i -> lengths[i], 1000));
        assertNull(EditorHighlightCore.computeRange(4, 0, 1, i -> offsets[i], i -> lengths[i], 1000));

        EditorHighlightCore.HighlightRange single = EditorHighlightCore.computeRange(
            4, 2, -1, i -> offsets[i], i -> lengths[i], 1500);
        assertEquals(new EditorHighlightCore.HighlightRange(14, 15, 2, 2, 1500), single);

        EditorHighlightCore.HighlightRange multi = EditorHighlightCore.computeRange(
            4, 2, 3, i -> offsets[i], i -> lengths[i], 50);
        assertEquals(new EditorHighlightCore.HighlightRange(14, 33, 2, 3, EditorHighlightCore.MIN_DURATION_MS), multi);

        EditorHighlightCore.HighlightRange clamped = EditorHighlightCore.computeRange(
            4, 99, 120, i -> offsets[i], i -> lengths[i], 99999);
        assertEquals(new EditorHighlightCore.HighlightRange(48, 1, 4, 4, EditorHighlightCore.MAX_DURATION_MS), clamped);
    }

    private static ReviewComment comment(String file, int line) {
        return new ReviewComment("id-" + line, file, line, "user", "note", 100L, List.of(), false);
    }
}
