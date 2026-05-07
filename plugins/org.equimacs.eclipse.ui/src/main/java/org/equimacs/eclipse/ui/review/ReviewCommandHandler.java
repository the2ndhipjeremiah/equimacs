package org.equimacs.eclipse.ui.review;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.text.Position;
import org.eclipse.jface.text.source.Annotation;
import org.eclipse.jface.text.source.IAnnotationModel;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IFileEditorInput;
import org.eclipse.ui.IPerspectiveDescriptor;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.editors.text.EditorsUI;
import org.eclipse.ui.texteditor.IDocumentProvider;
import org.eclipse.ui.texteditor.ITextEditor;
import org.eclipse.ui.texteditor.MarkerAnnotation;
import org.equimacs.eclipse.bridge.api.IBridgeCommandHandler;
import org.equimacs.eclipse.bridge.api.IBridgeService;
import org.equimacs.protocol.Request;

public final class ReviewCommandHandler implements IBridgeCommandHandler {

    private volatile IBridgeService bridge;
    private final ReviewStore store = new ReviewStore();

    void setBridge(IBridgeService bridge) {
        this.bridge = bridge;
    }

    void unsetBridge(IBridgeService bridge) {
        this.bridge = null;
    }

    void activate() {
        initAnnotationPreferences();
        try {
            ReviewMarkerManager.reconcile(store.list(null));
        } catch (Exception e) {
            // Review comments remain available even if decorative markers fail.
        }
    }

    private static void initAnnotationPreferences() {
        IPreferenceStore store = EditorsUI.getPreferenceStore();
        String p = "org.equimacs.eclipse.ui.reviewComment";
        store.setValue(p + ".highlight", true);
        store.setValue(p + ".verticalRuler", true);
        store.setValue(p + ".overviewRuler", true);
        store.setValue(p + ".textStyle", "HIGHLIGHT");
        store.setValue(p + ".color", "100,180,255");
    }

    void deactivate() {
        ReviewMarkerManager.disposeAll();
    }

    @Override
    public Object handle(Request req) throws Exception {
        return switch (req) {
            case Request.AddReviewComment r -> {
                ReviewComment comment = store.addComment(r.file(), r.line(), r.author(), r.text());
                ReviewMarkerManager.add(comment);
                triggerViewRefresh();
                yield comment;
            }
            case Request.ListReviewComments r -> store.list(r.file());
            case Request.ReplyToComment r -> {
                ReviewComment updated = store.addReply(r.commentId(), r.author(), r.text());
                triggerViewRefresh();
                yield updated;
            }
            case Request.ResolveComment r -> {
                ReviewComment updated = store.resolve(r.commentId());
                ReviewMarkerManager.remove(r.commentId());
                triggerViewRefresh();
                yield updated;
            }
            case Request.ReviewDiagnostics r -> reviewDiagnostics(r.file());
            case Request.EclipseWorkbench _ -> eclipseWorkbench();
            case Request.EclipseEditorDiagnostics _ -> eclipseEditorDiagnostics();
            case Request.ReviewEditorDiagnostics r -> reviewEditorDiagnostics(r.file(), r.line());
            default -> throw new IllegalArgumentException(
                "ReviewCommandHandler: no handler for " + req.getClass().getSimpleName());
        };
    }

    private Map<String, Object> reviewDiagnostics(String file) throws Exception {
        List<ReviewComment> comments = store.list(file);
        ReviewMarkerManager.reconcile(store.list(null));

        IPreferenceStore prefs = EditorsUI.getPreferenceStore();
        String p = "org.equimacs.eclipse.ui.reviewComment";
        Map<String, Object> prefValues = new LinkedHashMap<>();
        prefValues.put("highlight", prefs.getBoolean(p + ".highlight"));
        prefValues.put("verticalRuler", prefs.getBoolean(p + ".verticalRuler"));
        prefValues.put("overviewRuler", prefs.getBoolean(p + ".overviewRuler"));
        prefValues.put("textStyle", prefs.getString(p + ".textStyle"));
        prefValues.put("color", prefs.getString(p + ".color"));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("comments", comments);
        result.put("markers", ReviewMarkerManager.diagnostics(file));
        result.put("annotationPreferences", prefValues);
        return result;
    }

    private Map<String, Object> eclipseWorkbench() {
        return uiRead(() -> {
            Map<String, Object> result = new LinkedHashMap<>();
            Display display = Display.getDefault();
            result.put("displayAvailable", display != null && !display.isDisposed());
            result.put("workbenchRunning", PlatformUI.isWorkbenchRunning());
            WorkbenchState state = activeWorkbenchState();
            result.put("windowAvailable", state.window != null);
            result.put("pageAvailable", state.page != null);
            if (state.page == null) return result;

            IPerspectiveDescriptor perspective = state.page.getPerspective();
            IEditorPart activeEditor = state.page.getActiveEditor();
            IEditorPart[] editors = state.page.getEditors();
            result.put("perspectiveId", perspective == null ? null : perspective.getId());
            result.put("openEditorCount", editors == null ? 0 : editors.length);
            result.put("activeEditor", editorSummary(activeEditor));
            result.put("selection", selectionSummary(state.window == null ? null : state.window.getSelectionService().getSelection()));
            return result;
        });
    }

    private Map<String, Object> eclipseEditorDiagnostics() {
        return uiRead(() -> {
            Map<String, Object> result = new LinkedHashMap<>();
            EditorState state = activeEditorState();
            result.put("available", state.editor != null);
            if (state.editor == null) return result;

            result.put("editor", editorSummary(state.editor));
            result.put("isTextEditor", state.textEditor != null);
            if (state.textEditor == null) return result;

            fillTextEditorDiagnostics(result, state);
            result.put("annotationCounts", annotationCounts(state.annotationModel));
            return result;
        });
    }

    private Map<String, Object> reviewEditorDiagnostics(String file, int line) {
        return uiRead(() -> {
            Map<String, Object> result = new LinkedHashMap<>();
            EditorState state = activeEditorState();
            result.put("available", state.editor != null);
            result.put("requestedFile", file);
            result.put("requestedLine", line);
            if (state.editor == null) return result;

            result.put("editor", editorSummary(state.editor));
            result.put("isTextEditor", state.textEditor != null);
            if (state.textEditor == null) return result;

            fillTextEditorDiagnostics(result, state);
            result.put("reviewAnnotations", reviewAnnotations(state, file, line));
            return result;
        });
    }

    private static Map<String, Object> uiRead(DiagnosticSupplier supplier) {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed()) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("available", false);
            result.put("reason", "SWT display unavailable");
            return result;
        }
        if (Display.getCurrent() == display) {
            return supplier.get();
        }
        AtomicReference<Map<String, Object>> ref = new AtomicReference<>();
        display.syncExec(() -> ref.set(supplier.get()));
        return ref.get();
    }

    private static WorkbenchState activeWorkbenchState() {
        if (!PlatformUI.isWorkbenchRunning()) return new WorkbenchState(null, null);
        IWorkbench workbench = PlatformUI.getWorkbench();
        IWorkbenchWindow window = workbench == null ? null : workbench.getActiveWorkbenchWindow();
        IWorkbenchPage page = window == null ? null : window.getActivePage();
        return new WorkbenchState(window, page);
    }

    private static EditorState activeEditorState() {
        WorkbenchState workbench = activeWorkbenchState();
        IEditorPart editor = workbench.page == null ? null : workbench.page.getActiveEditor();
        ITextEditor textEditor = editor == null ? null : editor.getAdapter(ITextEditor.class);
        IDocumentProvider provider = textEditor == null ? null : textEditor.getDocumentProvider();
        IEditorInput input = editor == null ? null : editor.getEditorInput();
        IDocument document = provider == null ? null : provider.getDocument(input);
        IAnnotationModel annotationModel = provider == null ? null : provider.getAnnotationModel(input);
        return new EditorState(editor, textEditor, input, document, annotationModel);
    }

    private static void fillTextEditorDiagnostics(Map<String, Object> result, EditorState state) {
        result.put("documentAvailable", state.document != null);
        if (state.document != null) {
            result.put("documentLines", state.document.getNumberOfLines());
        }
        result.put("dirty", state.editor.isDirty());

        ISelection selection = state.textEditor.getSelectionProvider() == null
            ? null
            : state.textEditor.getSelectionProvider().getSelection();
        result.put("selection", selectionSummary(selection));

        StyledText styledText = state.textEditor.getAdapter(StyledText.class);
        if (styledText != null && !styledText.isDisposed()) {
            int topVisibleLine = styledText.getTopIndex() + 1;
            int visibleLineCount = Math.max(1, styledText.getClientArea().height / Math.max(1, styledText.getLineHeight()));
            result.put("caretOffset", styledText.getCaretOffset());
            result.put("topVisibleLine", topVisibleLine);
            result.put("bottomVisibleLineApprox", topVisibleLine + visibleLineCount - 1);
        }
        result.put("annotationModelClass", state.annotationModel == null ? null : state.annotationModel.getClass().getName());
    }

    private static Map<String, Object> editorSummary(IEditorPart editor) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("available", editor != null);
        if (editor == null) return result;
        IEditorInput input = editor.getEditorInput();
        result.put("title", editor.getTitle());
        result.put("class", editor.getClass().getName());
        result.put("inputClass", input == null ? null : input.getClass().getName());
        result.put("path", inputPath(input));
        return result;
    }

    private static String inputPath(IEditorInput input) {
        if (input instanceof IFileEditorInput fileInput) {
            return fileInput.getFile().getFullPath().toString();
        }
        IFile file = input == null ? null : input.getAdapter(IFile.class);
        return file == null ? null : file.getFullPath().toString();
    }

    private static Map<String, Object> selectionSummary(ISelection selection) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("available", selection != null);
        if (selection == null) return result;
        result.put("class", selection.getClass().getName());
        result.put("empty", selection.isEmpty());
        if (selection instanceof ITextSelection text) {
            result.put("offset", text.getOffset());
            result.put("length", text.getLength());
            result.put("startLine", text.getStartLine() + 1);
            result.put("endLine", text.getEndLine() + 1);
        } else if (selection instanceof IStructuredSelection structured) {
            result.put("size", structured.size());
            Object first = structured.getFirstElement();
            result.put("firstElementClass", first == null ? null : first.getClass().getName());
            result.put("firstElement", first == null ? null : first.toString());
        }
        return result;
    }

    private static Map<String, Integer> annotationCounts(IAnnotationModel model) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        if (model == null) return counts;
        Iterator<Annotation> it = model.getAnnotationIterator();
        while (it.hasNext()) {
            Annotation annotation = it.next();
            String type = annotation.getType();
            counts.put(type, counts.getOrDefault(type, 0) + 1);
        }
        return counts;
    }

    private static List<Map<String, Object>> reviewAnnotations(EditorState state, String file, int line) {
        List<Map<String, Object>> result = new java.util.ArrayList<>();
        if (state.annotationModel == null) return result;
        Iterator<Annotation> it = state.annotationModel.getAnnotationIterator();
        while (it.hasNext()) {
            Annotation annotation = it.next();
            if (!isReviewAnnotation(annotation)) continue;
            Position position = state.annotationModel.getPosition(annotation);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("type", annotation.getType());
            item.put("text", annotation.getText());
            item.put("positionOffset", position == null ? -1 : position.getOffset());
            item.put("positionLength", position == null ? -1 : position.getLength());
            fillMarkerAnnotation(item, annotation);
            item.put("matchesRequestedFile", file == null || file.isBlank() || file.equals(inputPath(state.input)));
            item.put("overlapsRequestedLine", line <= 0 || overlapsLine(state.document, position, line));
            result.add(item);
        }
        return result;
    }

    private static void fillMarkerAnnotation(Map<String, Object> item, Annotation annotation) {
        if (!(annotation instanceof MarkerAnnotation markerAnnotation)) return;
        IMarker marker = markerAnnotation.getMarker();
        try {
            item.put("markerResource", marker.getResource() == null ? null : marker.getResource().getFullPath().toString());
            item.put("commentId", marker.getAttribute("equimacs.commentId", null));
            item.put("markerLine", marker.getAttribute(IMarker.LINE_NUMBER, -1));
            item.put("markerCharStart", marker.getAttribute(IMarker.CHAR_START, -1));
            item.put("markerCharEnd", marker.getAttribute(IMarker.CHAR_END, -1));
        } catch (Exception e) {
            item.put("markerError", e.getMessage());
        }
    }

    private static boolean isReviewAnnotation(Annotation annotation) {
        if (ReviewMarkerManager.MARKER_TYPE.equals(annotation.getType())) return true;
        if (!(annotation instanceof MarkerAnnotation markerAnnotation)) return false;
        IMarker marker = markerAnnotation.getMarker();
        try {
            if (ReviewMarkerManager.MARKER_TYPE.equals(marker.getType())) return true;
            return marker.getAttribute("equimacs.commentId", null) != null;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean overlapsLine(IDocument document, Position position, int line) {
        if (document == null || position == null) return false;
        try {
            int lineIndex = line - 1;
            int lineStart = document.getLineOffset(lineIndex);
            int lineEnd = lineStart + document.getLineLength(lineIndex);
            int posStart = position.getOffset();
            int posEnd = posStart + position.getLength();
            return posStart < lineEnd && posEnd > lineStart;
        } catch (Exception e) {
            return false;
        }
    }

    private void triggerViewRefresh() {
        Display d = Display.getDefault();
        if (d != null && !d.isDisposed()) {
            d.asyncExec(ReviewView::refresh);
        }
    }

    private interface DiagnosticSupplier {
        Map<String, Object> get();
    }

    private record WorkbenchState(IWorkbenchWindow window, IWorkbenchPage page) {}

    private record EditorState(
        IEditorPart editor,
        ITextEditor textEditor,
        IEditorInput input,
        IDocument document,
        IAnnotationModel annotationModel) {}
}
