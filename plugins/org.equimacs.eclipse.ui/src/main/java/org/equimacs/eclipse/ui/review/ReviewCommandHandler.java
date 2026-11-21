package org.equimacs.eclipse.ui.review;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.core.commands.Command;
import org.eclipse.core.commands.ParameterizedCommand;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Path;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRegion;
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
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.commands.ICommandService;
import org.eclipse.ui.editors.text.EditorsUI;
import org.eclipse.ui.handlers.IHandlerService;
import org.eclipse.ui.texteditor.IDocumentProvider;
import org.eclipse.ui.texteditor.ITextEditor;
import org.eclipse.ui.texteditor.MarkerAnnotation;
import org.equimacs.eclipse.bridge.api.IBridgeCommandHandler;
import org.equimacs.eclipse.bridge.api.IBridgeService;
import org.equimacs.protocol.Request;

public final class ReviewCommandHandler implements IBridgeCommandHandler {

    private volatile IBridgeService bridge;
    private volatile boolean uiInitialized;
    private final ReviewStore store = new ReviewStore();

    void setBridge(IBridgeService bridge) {
        this.bridge = bridge;
    }

    void unsetBridge(IBridgeService bridge) {
        this.bridge = null;
    }

    void activate() {
        ensureUiInitialized();
    }

    private synchronized void ensureUiInitialized() {
        if (uiInitialized) {
            return;
        }
        try {
            initAnnotationPreferences();
        } catch (IllegalStateException e) {
            // Workspace instance location is not initialized yet; retry on first request.
            return;
        }
        try {
            ReviewMarkerManager.reconcile(store.list(null));
        } catch (Exception e) {
            // Review comments remain available even if decorative markers fail.
        }
        uiInitialized = true;
    }

    private static void initAnnotationPreferences() {
        IPreferenceStore store = EditorsUI.getPreferenceStore();
        String p = "org.equimacs.eclipse.ui.reviewComment";
        store.setValue(p + ".highlight", true);
        store.setValue(p + ".verticalRuler", true);
        store.setValue(p + ".overviewRuler", true);
        store.setValue(p + ".text", true);
        store.setValue(p + ".textStyle", "HIGHLIGHT");
        store.setValue(p + ".color", "100,180,255");
    }

    void deactivate() {
        ReviewMarkerManager.disposeAll();
    }

    @Override
    public Object handle(Request req) throws Exception {
        ensureUiInitialized();
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
            case Request.OpenFile o -> openFile(o.file(), o.line(), o.endLine());
            case Request.EclipseCommand ec -> eclipseCommand(ec.commandId(), ec.file(), ec.offset(), ec.params());
            case Request.ShowView sv -> showView(sv.viewId(), sv.secondaryId());
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
        prefValues.put("text", prefs.getBoolean(p + ".text"));
        prefValues.put("textStyle", prefs.getString(p + ".textStyle"));
        prefValues.put("color", prefs.getString(p + ".color"));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("comments", comments);
        result.put("markers", ReviewMarkerManager.diagnostics(file));
        result.put("annotationPreferences", prefValues);
        return result;
    }

    private Map<String, Object> openFile(String filePath, int line, int endLine) {
        if (filePath == null || filePath.isBlank()) {
            throw new IllegalArgumentException("OpenFile requires a non-blank file path");
        }
        IFile file = resolveWorkspaceFile(filePath);
        if (file == null) {
            throw new IllegalArgumentException("File not found in workspace: " + filePath);
        }
        return uiRead(() -> {
            if (!PlatformUI.isWorkbenchRunning()) {
                throw new IllegalStateException("Eclipse workbench is not running");
            }
            WorkbenchState state = activeWorkbenchState();
            if (state.page == null) {
                throw new IllegalStateException("No active Eclipse workbench page");
            }
            try {
                IEditorPart editor = org.eclipse.ui.ide.IDE.openEditor(state.page, file, true);
                int offset = -1;
                int length = 0;
                int resolvedStart = line;
                int resolvedEnd = endLine;
                if (line > 0 && editor instanceof ITextEditor textEditor) {
                    IDocumentProvider provider = textEditor.getDocumentProvider();
                    IDocument doc = provider != null ? provider.getDocument(textEditor.getEditorInput()) : null;
                    if (doc != null && doc.getNumberOfLines() > 0) {
                        resolvedStart = Math.max(1, Math.min(line, doc.getNumberOfLines()));
                        resolvedEnd = endLine >= resolvedStart
                            ? Math.min(endLine, doc.getNumberOfLines())
                            : resolvedStart;
                        IRegion startInfo = doc.getLineInformation(resolvedStart - 1);
                        IRegion endInfo = doc.getLineInformation(resolvedEnd - 1);
                        offset = startInfo.getOffset();
                        length = Math.max(0, (endInfo.getOffset() + endInfo.getLength()) - offset);
                        textEditor.selectAndReveal(offset, length);
                    }
                }
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("opened", true);
                result.put("file", file.getFullPath().toString());
                result.put("line", resolvedStart);
                result.put("endLine", resolvedEnd);
                result.put("offset", offset);
                result.put("length", length);
                result.put("editorTitle", editor != null ? editor.getTitle() : null);
                return result;
            } catch (Exception e) {
                throw new IllegalStateException("Failed to open editor: " + e.getMessage(), e);
            }
        });
    }

    private Map<String, Object> eclipseCommand(
        String commandId,
        String filePath,
        int offset,
        Map<String, String> params
    ) {
        if (commandId == null || commandId.isBlank()) {
            throw new IllegalArgumentException("EclipseCommand requires a non-blank commandId");
        }
        String trimmedCommandId = commandId.trim();
        IFile resolvedFile = (filePath != null && !filePath.isBlank()) ? resolveWorkspaceFile(filePath) : null;
        if (filePath != null && !filePath.isBlank() && resolvedFile == null) {
            throw new IllegalArgumentException("File not found in workspace: " + filePath);
        }
        return uiRead(() -> {
            if (!PlatformUI.isWorkbenchRunning()) {
                throw new IllegalStateException("Eclipse workbench is not running");
            }
            WorkbenchState state = activeWorkbenchState();
            try {
                if (resolvedFile != null) {
                    if (state.page == null) {
                        throw new IllegalStateException("No active Eclipse workbench page");
                    }
                    IEditorPart editor = org.eclipse.ui.ide.IDE.openEditor(state.page, resolvedFile, true);
                    if (offset >= 0 && editor instanceof ITextEditor textEditor) {
                        textEditor.selectAndReveal(offset, 0);
                    }
                }
                IHandlerService handlerService = state.window != null
                    ? state.window.getService(IHandlerService.class)
                    : PlatformUI.getWorkbench().getService(IHandlerService.class);
                if (handlerService == null) {
                    throw new IllegalStateException("Eclipse IHandlerService unavailable");
                }
                if (params != null && !params.isEmpty()) {
                    ICommandService commandService = state.window != null
                        ? state.window.getService(ICommandService.class)
                        : PlatformUI.getWorkbench().getService(ICommandService.class);
                    if (commandService == null) {
                        throw new IllegalStateException("Eclipse ICommandService unavailable");
                    }
                    Command command = commandService.getCommand(trimmedCommandId);
                    ParameterizedCommand parameterized = ParameterizedCommand.generateCommand(command, params);
                    if (parameterized == null) {
                        throw new IllegalArgumentException(
                            "Failed to parameterize command " + trimmedCommandId + " with " + params);
                    }
                    handlerService.executeCommand(parameterized, null);
                } else {
                    handlerService.executeCommand(trimmedCommandId, null);
                }
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("executed", true);
                result.put("commandId", trimmedCommandId);
                result.put("file", resolvedFile != null ? resolvedFile.getFullPath().toString() : null);
                result.put("offset", offset);
                result.put("params", params);
                return result;
            } catch (Exception e) {
                throw new IllegalStateException(
                    "Failed to execute Eclipse command " + trimmedCommandId + ": " + e.getMessage(), e);
            }
        });
    }

    private Map<String, Object> showView(String viewId, String secondaryId) {
        if (viewId == null || viewId.isBlank()) {
            throw new IllegalArgumentException("ShowView requires a non-blank viewId");
        }
        String trimmedViewId = viewId.trim();
        String trimmedSecondary = (secondaryId != null && !secondaryId.isBlank()) ? secondaryId.trim() : null;
        return uiRead(() -> {
            if (!PlatformUI.isWorkbenchRunning()) {
                throw new IllegalStateException("Eclipse workbench is not running");
            }
            WorkbenchState state = activeWorkbenchState();
            if (state.page == null) {
                throw new IllegalStateException("No active Eclipse workbench page");
            }
            try {
                IViewPart view = trimmedSecondary != null
                    ? state.page.showView(trimmedViewId, trimmedSecondary, IWorkbenchPage.VIEW_ACTIVATE)
                    : state.page.showView(trimmedViewId);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("shown", true);
                result.put("viewId", trimmedViewId);
                result.put("secondaryId", trimmedSecondary);
                result.put("title", view != null ? view.getTitle() : null);
                return result;
            } catch (Exception e) {
                throw new IllegalStateException(
                    "Failed to show view " + trimmedViewId + ": " + e.getMessage(), e);
            }
        });
    }

    private static IFile resolveWorkspaceFile(String pathStr) {
        IResource member = ResourcesPlugin.getWorkspace().getRoot().findMember(new Path(pathStr));
        if (member instanceof IFile file) {
            return file;
        }
        IFile[] files = ResourcesPlugin.getWorkspace().getRoot().findFilesForLocationURI(
            java.net.URI.create("file:///" + pathStr.replace("\\", "/")));
        return files.length > 0 ? files[0] : null;
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
