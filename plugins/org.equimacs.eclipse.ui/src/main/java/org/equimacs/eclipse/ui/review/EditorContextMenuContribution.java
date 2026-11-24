package org.equimacs.eclipse.ui.review;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.core.resources.IFile;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.ActionContributionItem;
import org.eclipse.jface.action.IContributionItem;
import org.eclipse.jface.action.Separator;
import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.ISelectionProvider;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IFileEditorInput;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.actions.CompoundContributionItem;
import org.eclipse.ui.texteditor.ITextEditor;
import org.equimacs.eclipse.ui.viewhost.ViewAction;
import org.equimacs.eclipse.ui.viewhost.ViewActionDispatcher;

/**
 * Dynamic editor context-menu items for Equimacs review threads (reply, resolve, reopen,
 * show in Review view, add comment) and Moldable Toolkit actions (Call Explorer, Inspect Symbol).
 */
public final class EditorContextMenuContribution extends CompoundContributionItem {

    private static final int MAX_PREVIEW_LEN = 36;

    @Override
    protected IContributionItem[] getContributionItems() {
        IWorkbenchWindow window = PlatformUI.isWorkbenchRunning()
            ? PlatformUI.getWorkbench().getActiveWorkbenchWindow()
            : null;
        if (window == null) {
            return new IContributionItem[0];
        }
        IWorkbenchPage page = window.getActivePage();
        if (page == null) {
            return new IContributionItem[0];
        }
        IEditorPart activeEditor = page.getActiveEditor();
        if (!(activeEditor instanceof ITextEditor textEditor)) {
            return new IContributionItem[0];
        }
        IEditorInput input = textEditor.getEditorInput();
        if (!(input instanceof IFileEditorInput fileInput)) {
            return new IContributionItem[0];
        }

        IFile file = fileInput.getFile();
        String workspacePath = file.getFullPath().toString();
        String fileName = file.getName();
        Shell shell = window.getShell();

        int line = 1;
        int offset = 0;
        ISelectionProvider selectionProvider = textEditor.getSelectionProvider();
        ISelection selection = selectionProvider != null ? selectionProvider.getSelection() : null;
        if (selection instanceof ITextSelection textSelection) {
            line = textSelection.getStartLine() + 1;
            offset = Math.max(0, textSelection.getOffset());
        }

        List<ReviewComment> lineThreads = findThreadsAtLine(workspacePath, line);
        List<IContributionItem> items = new ArrayList<>();

        boolean multiple = lineThreads.size() > 1;
        for (ReviewComment thread : lineThreads) {
            String suffix = multiple ? " (" + preview(thread.text()) + ")" : "";
            if (!thread.resolved()) {
                items.add(new ActionContributionItem(new Action("Reply to Thread" + suffix + "...") {
                    @Override
                    public void run() {
                        replyToThread(shell, fileName, thread);
                    }
                }));
                items.add(new ActionContributionItem(new Action("Resolve Thread" + suffix) {
                    @Override
                    public void run() {
                        setThreadResolved(thread, true);
                    }
                }));
            } else {
                items.add(new ActionContributionItem(new Action("Reopen Thread" + suffix) {
                    @Override
                    public void run() {
                        setThreadResolved(thread, false);
                    }
                }));
            }
            items.add(new ActionContributionItem(new Action("Show Thread in Review View" + suffix) {
                @Override
                public void run() {
                    ReviewView.showAndSelect(thread.id(), thread.resolved());
                }
            }));
        }

        if (!lineThreads.isEmpty()) {
            items.add(new Separator());
        }

        int finalLine = line;
        items.add(new ActionContributionItem(new Action("Add Review Comment...") {
            @Override
            public void run() {
                addComment(shell, workspacePath, fileName, finalLine);
            }
        }));

        if (fileName.endsWith(".java")) {
            items.add(new Separator());
            int finalOffset = offset;
            items.add(new ActionContributionItem(new Action("Open Call Explorer") {
                @Override
                public void run() {
                    Map<String, String> params = new LinkedHashMap<>();
                    params.put("file", workspacePath);
                    params.put("offset", String.valueOf(finalOffset));
                    params.put("direction", "both");
                    params.put("depth", "3");
                    params.put("hideJdk", "true");
                    params.put("viewMode", "hybrid");
                    ViewActionDispatcher.dispatch(
                        "editor-context",
                        null,
                        new ViewAction.ToolkitView("call-explorer", params));
                }
            }));
            items.add(new ActionContributionItem(new Action("Inspect Symbol") {
                @Override
                public void run() {
                    ViewActionDispatcher.dispatch(
                        "editor-context",
                        null,
                        new ViewAction.Inspect("jdt-symbol", null, null, workspacePath, finalOffset, null));
                }
            }));
        }

        return items.toArray(new IContributionItem[0]);
    }

    private static List<ReviewComment> findThreadsAtLine(String workspacePath, int line) {
        try {
            ReviewStore store = new ReviewStore();
            List<ReviewComment> fileComments = store.list(workspacePath);
            List<ReviewComment> matches = new ArrayList<>();
            for (ReviewComment comment : fileComments) {
                if (comment.line() == line) {
                    matches.add(comment);
                }
            }
            return matches;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static void addComment(Shell shell, String workspacePath, String fileName, int line) {
        InputDialog dialog = new InputDialog(
            shell,
            "Add Review Comment",
            fileName + ":" + line,
            "",
            s -> (s == null || s.isBlank()) ? "Comment cannot be empty" : null
        );
        if (dialog.open() != Window.OK) {
            return;
        }
        String text = dialog.getValue().trim();
        try {
            ReviewStore store = new ReviewStore();
            ReviewComment comment = store.addComment(workspacePath, line, "user", text);
            ReviewMarkerManager.add(comment);
            ReviewView.refresh();
        } catch (Exception e) {
            System.err.println("[Equimacs] Failed to add review comment: " + e.getMessage());
        }
    }

    private static void replyToThread(Shell shell, String fileName, ReviewComment thread) {
        InputDialog dialog = new InputDialog(
            shell,
            "Reply to Review Thread",
            fileName + ":" + thread.line() + " - " + preview(thread.text()),
            "",
            s -> (s == null || s.isBlank()) ? "Reply cannot be empty" : null
        );
        if (dialog.open() != Window.OK) {
            return;
        }
        String text = dialog.getValue().trim();
        try {
            ReviewStore store = new ReviewStore();
            store.addReply(thread.id(), "user", text);
            ReviewView.refresh();
        } catch (Exception e) {
            System.err.println("[Equimacs] Failed to reply to review thread: " + e.getMessage());
        }
    }

    private static void setThreadResolved(ReviewComment thread, boolean resolved) {
        try {
            ReviewStore store = new ReviewStore();
            ReviewComment updated = store.setResolved(thread.id(), resolved);
            if (resolved) {
                ReviewMarkerManager.remove(updated.id());
            } else {
                ReviewMarkerManager.add(updated);
            }
            ReviewView.refresh();
        } catch (Exception e) {
            System.err.println("[Equimacs] Failed to update review thread: " + e.getMessage());
        }
    }

    private static String preview(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String singleLine = text.replace('\n', ' ').replace('\r', ' ').trim();
        if (singleLine.length() <= MAX_PREVIEW_LEN) {
            return singleLine;
        }
        return singleLine.substring(0, MAX_PREVIEW_LEN - 3) + "...";
    }
}
