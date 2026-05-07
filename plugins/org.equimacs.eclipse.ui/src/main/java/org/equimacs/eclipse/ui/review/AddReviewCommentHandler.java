package org.equimacs.eclipse.ui.review;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.IFile;
import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.window.Window;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IFileEditorInput;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.texteditor.ITextEditor;

public class AddReviewCommentHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        IEditorPart editor = HandlerUtil.getActiveEditor(event);
        if (!(editor instanceof ITextEditor textEditor)) return null;

        IEditorInput input = textEditor.getEditorInput();
        if (!(input instanceof IFileEditorInput fileInput)) return null;

        IFile file = fileInput.getFile();
        String workspacePath = file.getFullPath().toString();

        ISelection selection = textEditor.getSelectionProvider().getSelection();
        int line = 1;
        if (selection instanceof ITextSelection textSel) {
            line = textSel.getStartLine() + 1;
        }

        int finalLine = line;
        InputDialog dialog = new InputDialog(
            HandlerUtil.getActiveShell(event),
            "Add Review Comment",
            file.getName() + ":" + line,
            "",
            s -> (s == null || s.isBlank()) ? "Comment cannot be empty" : null
        );

        if (dialog.open() != Window.OK) return null;

        String text = dialog.getValue().trim();
        try {
            ReviewStore store = new ReviewStore();
            ReviewComment comment = store.addComment(workspacePath, finalLine, "user", text);
            ReviewMarkerManager.add(comment);
            ReviewView.refresh();
        } catch (Exception e) {
            throw new ExecutionException("Failed to add review comment", e);
        }
        return null;
    }
}
