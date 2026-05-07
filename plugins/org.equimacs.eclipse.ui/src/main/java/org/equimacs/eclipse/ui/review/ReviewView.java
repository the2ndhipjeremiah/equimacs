package org.equimacs.eclipse.ui.review;

import java.util.List;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Path;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.part.ViewPart;

public class ReviewView extends ViewPart {

    public static final String VIEW_ID = "org.equimacs.eclipse.ui.reviewView";

    private static volatile ReviewView instance;

    private TreeViewer viewer;

    @Override
    public void createPartControl(Composite parent) {
        instance = this;

        viewer = new TreeViewer(parent, SWT.MULTI | SWT.H_SCROLL | SWT.V_SCROLL);
        viewer.setContentProvider(new ContentProvider());
        viewer.setLabelProvider(new ReviewLabelProvider());
        viewer.setInput(loadComments());

        viewer.addDoubleClickListener(event -> {
            IStructuredSelection sel = viewer.getStructuredSelection();
            Object element = sel.getFirstElement();
            if (element instanceof ReviewComment comment) {
                openEditor(comment.file(), comment.line());
            }
        });
    }

    @Override
    public void setFocus() {
        viewer.getControl().setFocus();
    }

    @Override
    public void dispose() {
        instance = null;
        super.dispose();
    }

    public static void refresh() {
        ReviewView v = instance;
        if (v == null || v.viewer == null || v.viewer.getControl().isDisposed()) return;
        List<ReviewComment> comments = v.loadComments();
        v.viewer.setInput(comments);
        v.viewer.refresh();
    }

    private List<ReviewComment> loadComments() {
        try {
            return new ReviewStore().list(null);
        } catch (Exception e) {
            return List.of();
        }
    }

    private void openEditor(String file, int line) {
        try {
            IFile iFile = ResourcesPlugin.getWorkspace().getRoot().getFile(new Path(file));
            if (!iFile.exists()) return;
            IWorkbenchPage page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
            IEditorPart editor = IDE.openEditor(page, iFile);
            if (editor instanceof org.eclipse.ui.texteditor.ITextEditor textEditor && line > 0) {
                org.eclipse.jface.text.IDocument doc =
                    textEditor.getDocumentProvider().getDocument(textEditor.getEditorInput());
                if (doc != null) {
                    int offset = doc.getLineOffset(line - 1);
                    textEditor.selectAndReveal(offset, 0);
                }
            }
        } catch (Exception e) {
            // Best-effort
        }
    }

    private static class ContentProvider implements ITreeContentProvider {
        @Override
        public Object[] getElements(Object input) {
            if (input instanceof List<?> list) return list.toArray();
            return new Object[0];
        }

        @Override
        public Object[] getChildren(Object parent) {
            if (parent instanceof ReviewComment comment) return comment.replies().toArray();
            return new Object[0];
        }

        @Override
        public Object getParent(Object element) { return null; }

        @Override
        public boolean hasChildren(Object element) {
            return element instanceof ReviewComment c && !c.replies().isEmpty();
        }
    }

    private static class ReviewLabelProvider extends LabelProvider {
        @Override
        public String getText(Object element) {
            return switch (element) {
                case ReviewComment c -> {
                    String status = c.resolved() ? " [resolved]" : "";
                    yield c.file() + ":" + c.line() + "  " + c.author() + ": " + c.text() + status;
                }
                case ReviewComment.Reply r -> "↳ " + r.author() + ": " + r.text();
                default -> element.toString();
            };
        }
    }
}
