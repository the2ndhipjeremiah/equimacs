# Phase 7 — In-Editor Code Review Panel

## Status: In Progress — persistent marker reconciliation added; annotation highlight still needs visual verification

## What's Built

### New bundle: `org.equimacs.eclipse.ui`
Home for all Eclipse UI work going forward. Registers as an `IBridgeCommandHandler` via DS on the bridge whiteboard.

**Key files:**
- `plugins/org.equimacs.eclipse.ui/META-INF/MANIFEST.MF`
- `plugins/org.equimacs.eclipse.ui/plugin.xml` — views, markers, annotation types, commands, bindings, menus
- `plugins/org.equimacs.eclipse.ui/OSGI-INF/ReviewCommandHandler.xml`
- `plugins/org.equimacs.eclipse.ui/src/main/java/org/equimacs/eclipse/ui/review/`
  - `ReviewComment.java` — data record (id, file, line, author, text, timestamp, replies, resolved)
  - `ReviewStore.java` — reads/writes `<workspace>/.equimacs-review/comments.json`
- `ReviewMarkerManager.java` — static registry of IMarker instances; sets CHAR_START/CHAR_END via ITextFileBufferManager
- `ReviewCommandHandler.java` — handles review CLI commands; force-inits annotation prefs on DS activate
  - `ReviewView.java` — ViewPart (Window → Show View → Equimacs Review); TreeViewer with threads; double-click navigates to file:line
  - `AddReviewCommentHandler.java` — Eclipse command handler; opens InputDialog at cursor line; bound to Ctrl+Alt+C and right-click menu

### Protocol (4 new request types in `Request.java`):
- `AddReviewComment(file, line, author, text)`
- `ListReviewComments(file)` — file null = all
- `ReplyToComment(commentId, author, text)`
- `ResolveComment(commentId)`

### CLI commands:
```bash
eqm-cli review-add <file>:<line> <text...> [--author <name>]
eqm-cli review-list [file]
eqm-cli review-reply <commentId> <text...> [--author <name>]
eqm-cli review-resolve <commentId>
eqm-cli review-diagnostics [file]
```

### Build.java changes:
- Added `UI_BUNDLE_ID = "org.equimacs.eclipse.ui"` and `UI_BUNDLE_VERSION`
- Added `buildUi()` + `packageUiBundle()` — same pattern as debug bundle
- Added to `copyToDropins()` and build sequence (between debug and app)
- Added `org.eclipse.ui.editors_`, `org.eclipse.core.filebuffers_`, `org.eclipse.ui.workbench.texteditor_` to `findEclipseJars()` targets
- **Fixed JAR deletion bug**: removed `cleanOldInstalledBundleJars()` call — was deleting the JAR Eclipse's OSGi class loader was still referencing, causing `NoSuchFileException` on next class load
- **Persistent review markers**: marker type is now persistent, `ReviewMarkerManager.add()` is idempotent, activation reconciles stored unresolved comments with workspace markers, resolved/stale markers are deleted, and bundle shutdown no longer deletes markers.
- **Review diagnostics**: `review-diagnostics` reports stored comments, marker resource/line/char range/message, and annotation preference values. `ReviewStore` now resolves the workspace path lazily so DS activation does not fail before the workspace is available.

## Outstanding Issue: Annotation Highlight Not Visible

### Symptom
Gutter hover shows the review comment message (marker IS created). No blue line highlight or gutter icon visible.

### Root Causes Identified (Eclipse source research)

**1. CHAR_START/CHAR_END required for HIGHLIGHT strategy**
`AbstractMarkerAnnotationModel.createPositionFromMarker()` creates `Position(lineStart, 0)` when only `LINE_NUMBER` is set. The `HIGHLIGHT` (`ITextStyleStrategy`) skips zero-length positions — nothing to paint.

Fix applied: `ReviewMarkerManager.setCharRange()` uses `ITextFileBufferManager` to compute line offsets and sets `IMarker.CHAR_START`/`CHAR_END`. May be failing silently if the file buffer isn't available when called from the bridge server thread (non-UI thread). Needs verification.

**2. Preference store never auto-populated**
`highlightPreferenceValue="true"` in `markerAnnotationSpecification` is only a UI hint for the Annotations preference page — it does NOT write to the preference store. `SourceViewerDecorationSupport.areAnnotationsHighlighted()` calls `fPreferenceStore.getBoolean(key)` which returns `false` if key was never written.

Fix applied: `ReviewCommandHandler.activate()` calls `EditorsUI.getPreferenceStore().setValue(p + ".highlight", true)` (and other keys). DS `activate()` runs on a background thread — need to verify the preference change fires correctly to `SourceViewerDecorationSupport`'s property change listener.

### Next Debug Steps
1. Restart Eclipse after UI bundle changes, then verify handler/diagnostics:
   ```bash
   eqm-cli review-diagnostics /myproject/src/myproject/Main.java
   ```
2. Confirm diagnostics reports `charStart >= 0`, `charEnd > charStart`, and annotation preferences `highlight=true`, `textStyle=HIGHLIGHT`.
3. If char range IS set and preferences ARE enabled but still no highlight: the `ITextFileBufferManager` call may need to run on the UI thread. Consider wrapping in `Display.syncExec()` from `AddReviewCommentHandler` (which already runs on UI thread and has the document).
4. Alternative approach: in `AddReviewCommentHandler.execute()`, since we already have `IDocument doc`, compute char offsets and set `CHAR_START`/`CHAR_END` directly on the marker after `ReviewMarkerManager.add()`. This bypasses the file buffer manager entirely for the in-editor path.

## Structured Inspection Plan

Use structured Eclipse state before relying on screenshots.

## Eclipse Visibility Track

Goal: make Eclipse's internal UI state inspectable from the bridge so visual
bugs can be diagnosed from JSON before falling back to screenshots. This should
stay in `org.equimacs.eclipse.ui`; the bridge remains UI-free and only routes
requests to the UI bundle via the existing `IBridgeCommandHandler` service.

### Visibility commands to add

```bash
eqm-cli eclipse-workbench
eqm-cli eclipse-editor-diagnostics
eqm-cli review-editor-diagnostics
```

`eclipse-workbench` should report:
- whether the SWT display exists and whether the workbench is running
- active workbench window/page presence
- active perspective id
- open editor count and active editor title/input path
- active selection class and a concise selection summary

`eclipse-editor-diagnostics` should report generic editor state:
- active editor class and editor input class
- workspace path for the active editor, when resolvable
- whether the editor adapts to `ITextEditor`
- document line count and dirty state
- caret offset, caret line, and visible line range where available
- annotation model class and annotation counts grouped by annotation type

`review-editor-diagnostics` should be review-specific:
- active editor path and target file/line if supplied
- all annotations for `org.equimacs.eclipse.ui.reviewComment`
- marker comment id, marker line, marker char range
- annotation model position offset/length
- whether the annotation position overlaps the target line

### Implementation shape

- Add protocol records for the three requests.
- Add CLI parse arms using the `eclipse-*` and `review-editor-diagnostics`
  command names above.
- Handle the requests in `ReviewCommandHandler` for now, since this bundle owns
  UI visibility. If more UI features appear, rename the handler later rather
  than introducing a second UI command handler immediately.
- Run all workbench/editor reads on the SWT UI thread with `Display.syncExec`.
- Return plain `Map<String, Object>` / `List<Map<String, Object>>` payloads.
  Keep raw Eclipse objects out of the JSON surface.

### Acceptance checks

- When no workbench/editor is active, commands return structured `available:
  false` fields instead of throwing.
- With a Java editor open, `eclipse-editor-diagnostics` reports a text editor,
  document line count, caret line, and annotation model class.
- With a review marker on the active file, `review-editor-diagnostics` shows a
  non-zero annotation position matching the marker char range.
- If `review-diagnostics` is correct but `review-editor-diagnostics` has no
  review annotation, the problem is narrowed to marker-to-annotation-model
  synchronization.
- If both diagnostics are correct but the screenshot is still missing the
  highlight/icon, the problem is narrowed to Eclipse annotation presentation
  preferences or ruler painting.

### 1. OSGi / Declarative Services
Confirm the bundle and review handler are actually live:
```bash
eqm-cli gogo "lb -s org.equimacs.eclipse.ui"
eqm-cli gogo "scr:info org.equimacs.eclipse.ui.ReviewCommandHandler"
eqm-cli gogo "services org.equimacs.eclipse.bridge.api.IBridgeCommandHandler"
```

Expected:
- `org.equimacs.eclipse.ui` is `Active`.
- `ReviewCommandHandler` is enabled and instantiated.
- Bridge is using the review handler service.

### 2. Review Store / Marker / Annotation Preferences
Inspect stored comments, workspace markers, char ranges, and annotation prefs:
```bash
eqm-cli review-diagnostics /myproject/src/myproject/Main.java
```

Expected:
- One unresolved stored comment for the target file.
- One `org.equimacs.eclipse.ui.reviewComment` marker for the comment.
- `charStart >= 0`.
- `charEnd > charStart`.
- `annotationPreferences.highlight == true`.
- `annotationPreferences.textStyle == "HIGHLIGHT"`.
- `annotationPreferences.verticalRuler == true`.

### 3. Active Editor Annotation Model
If marker diagnostics are correct but no highlight appears, add a CLI command to inspect the active editor:
- active workbench window/page exists
- active editor input path
- editor is an `ITextEditor`
- annotation model class
- whether the model contains an annotation for marker type `org.equimacs.eclipse.ui.reviewComment`
- annotation position offset/length
- whether the position overlaps the target line

Candidate command:
```bash
eqm-cli review-editor-diagnostics
```

Expected:
- The active editor is `/myproject/src/myproject/Main.java`.
- The marker annotation model contains the review marker annotation.
- The annotation position has non-zero length and matches the marker char range.

### 4. Visual Snapshot
Screenshots are available but secondary:
- Bring Eclipse to the foreground with the target editor visible.
- Capture desktop screenshot from PowerShell.
- Inspect whether ruler/line highlight appears.

This is useful only after the structured checks show the marker and annotation model are correct.

## Not Yet Done
- `review-remove <commentId>` CLI command (user requested)
- Custom gutter icon (currently no icon; just hover text in gutter)
- Reply UI in the Review view (currently CLI-only)
- Resolve UI in the Review view
