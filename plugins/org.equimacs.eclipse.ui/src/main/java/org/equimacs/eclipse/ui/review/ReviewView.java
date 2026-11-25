package org.equimacs.eclipse.ui.review;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Path;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.MenuManager;
import org.eclipse.jface.action.Separator;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.viewers.DelegatingStyledCellLabelProvider;
import org.eclipse.jface.viewers.DelegatingStyledCellLabelProvider.IStyledLabelProvider;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.StyledString;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.ISharedImages;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.part.ViewPart;
import org.eclipse.ui.texteditor.ITextEditor;

public class ReviewView extends ViewPart {

    public static final String VIEW_ID = "org.equimacs.eclipse.ui.reviewView";

    private static final DateTimeFormatter TIME_FMT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private static volatile ReviewView instance;

    private final ReviewStore store = new ReviewStore();
    private final GitDiffService gitDiffService = new GitDiffService();

    private TreeViewer viewer;
    private Button hideResolvedCheck;
    private Combo gitFilterCombo;
    private Button refreshButton;
    private Text filterText;
    private Label countsLabel;
    private Label detailHeaderLabel;
    private Button jumpButton;
    private Button resolveButton;
    private StyledText codeSnippetText;
    private StyledText transcriptText;
    private Text replyInput;
    private Button replyButton;

    private List<ReviewComment> allComments = List.of();
    private Map<java.nio.file.Path, GitDiffModel.GitDiffSummary> gitDiffCache = new LinkedHashMap<>();
    private String selectedCommentId;

    @Override
    public void createPartControl(Composite parent) {
        instance = this;

        Composite root = new Composite(parent, SWT.NONE);
        GridLayout rootLayout = new GridLayout(1, false);
        rootLayout.marginWidth = 4;
        rootLayout.marginHeight = 4;
        rootLayout.verticalSpacing = 4;
        root.setLayout(rootLayout);

        createFilterBar(root);

        SashForm sash = new SashForm(root, SWT.VERTICAL);
        sash.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        createTreePane(sash);
        createDetailPane(sash);
        sash.setWeights(52, 48);

        reload(null);
    }

    private void createFilterBar(Composite parent) {
        Composite bar = new Composite(parent, SWT.NONE);
        bar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        GridLayout layout = new GridLayout(5, false);
        layout.marginWidth = 0;
        layout.marginHeight = 0;
        bar.setLayout(layout);

        filterText = new Text(bar, SWT.SEARCH | SWT.ICON_CANCEL | SWT.ICON_SEARCH);
        filterText.setMessage("Filter threads...");
        filterText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        filterText.addModifyListener(e -> applyFilterAndRefresh(selectedCommentId));

        gitFilterCombo = new Combo(bar, SWT.DROP_DOWN | SWT.READ_ONLY);
        gitFilterCombo.setItems("All files", "Changed files (git)", "Changed hunks (git)");
        gitFilterCombo.select(0);
        gitFilterCombo.setLayoutData(new GridData(SWT.BEGINNING, SWT.CENTER, false, false));
        gitFilterCombo.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                refreshGitSnapshot();
                applyFilterAndRefresh(selectedCommentId);
            }
        });

        hideResolvedCheck = new Button(bar, SWT.CHECK);
        hideResolvedCheck.setText("Hide resolved");
        hideResolvedCheck.setSelection(true);
        hideResolvedCheck.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                applyFilterAndRefresh(selectedCommentId);
            }
        });

        refreshButton = new Button(bar, SWT.PUSH);
        refreshButton.setText("Refresh");
        refreshButton.setLayoutData(new GridData(SWT.BEGINNING, SWT.CENTER, false, false));
        refreshButton.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                reload(selectedCommentId);
            }
        });

        countsLabel = new Label(bar, SWT.NONE);
        countsLabel.setLayoutData(new GridData(SWT.END, SWT.CENTER, false, false));
    }

    private void createTreePane(Composite parent) {
        viewer = new TreeViewer(parent, SWT.SINGLE | SWT.BORDER | SWT.H_SCROLL | SWT.V_SCROLL);
        viewer.setContentProvider(new ContentProvider());
        viewer.setLabelProvider(new DelegatingStyledCellLabelProvider(new ReviewLabelProvider()));

        viewer.addSelectionChangedListener(event -> {
            ReviewComment comment = getSelectedComment();
            selectedCommentId = comment != null ? comment.id() : null;
            updateDetailPane(comment);
        });

        viewer.addDoubleClickListener(event -> {
            IStructuredSelection sel = viewer.getStructuredSelection();
            Object element = sel.getFirstElement();
            if (element instanceof ReviewComment comment) {
                openEditor(comment.file(), comment.line());
            } else if (element instanceof FileGroup group) {
                boolean expanded = viewer.getExpandedState(group);
                viewer.setExpandedState(group, !expanded);
            }
        });

        createContextMenu();
    }

    private void createContextMenu() {
        MenuManager menuMgr = new MenuManager("#PopupMenu");
        menuMgr.setRemoveAllWhenShown(true);
        menuMgr.addMenuListener(manager -> {
            ReviewComment selected = getSelectedComment();
            if (selected != null) {
                Action jumpAction = new Action("Jump to Line") {
                    @Override
                    public void run() {
                        openEditor(selected.file(), selected.line());
                    }
                };
                manager.add(jumpAction);

                Action resolveAction = new Action(selected.resolved() ? "Reopen Thread" : "Resolve Thread") {
                    @Override
                    public void run() {
                        toggleResolveSelected();
                    }
                };
                manager.add(resolveAction);

                Action removeAction = new Action("Remove Thread") {
                    @Override
                    public void run() {
                        removeSelected();
                    }
                };
                manager.add(removeAction);

                Action copyIdAction = new Action("Copy Thread ID") {
                    @Override
                    public void run() {
                        copyToClipboard(selected.id());
                    }
                };
                manager.add(copyIdAction);
                manager.add(new Separator());
            }

            Action refreshAction = new Action("Refresh") {
                @Override
                public void run() {
                    reload(selectedCommentId);
                }
            };
            manager.add(refreshAction);
        });

        Menu menu = menuMgr.createContextMenu(viewer.getControl());
        viewer.getControl().setMenu(menu);
    }

    private void createDetailPane(Composite parent) {
        Composite detail = new Composite(parent, SWT.BORDER);
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 6;
        layout.marginHeight = 6;
        layout.verticalSpacing = 4;
        detail.setLayout(layout);

        Composite headerBar = new Composite(detail, SWT.NONE);
        headerBar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        GridLayout headerLayout = new GridLayout(3, false);
        headerLayout.marginWidth = 0;
        headerLayout.marginHeight = 0;
        headerBar.setLayout(headerLayout);

        detailHeaderLabel = new Label(headerBar, SWT.NONE);
        detailHeaderLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        jumpButton = new Button(headerBar, SWT.PUSH);
        jumpButton.setText("Jump");
        jumpButton.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                ReviewComment c = findCommentById(selectedCommentId);
                if (c != null) {
                    openEditor(c.file(), c.line());
                }
            }
        });

        resolveButton = new Button(headerBar, SWT.PUSH);
        resolveButton.setText("Resolve");
        resolveButton.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                toggleResolveSelected();
            }
        });

        codeSnippetText = new StyledText(detail, SWT.READ_ONLY | SWT.MULTI | SWT.H_SCROLL | SWT.V_SCROLL | SWT.BORDER);
        codeSnippetText.setFont(JFaceResources.getTextFont());
        GridData codeData = new GridData(SWT.FILL, SWT.FILL, true, false);
        codeData.heightHint = 92;
        codeSnippetText.setLayoutData(codeData);

        transcriptText = new StyledText(detail, SWT.READ_ONLY | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL | SWT.BORDER);
        transcriptText.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        Composite replyBar = new Composite(detail, SWT.NONE);
        replyBar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        GridLayout replyLayout = new GridLayout(2, false);
        replyLayout.marginWidth = 0;
        replyLayout.marginHeight = 0;
        replyBar.setLayout(replyLayout);

        replyInput = new Text(replyBar, SWT.BORDER);
        replyInput.setMessage("Write a reply...");
        replyInput.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        replyInput.addTraverseListener(e -> {
            if (e.detail == SWT.TRAVERSE_RETURN) {
                submitReply();
            }
        });

        replyButton = new Button(replyBar, SWT.PUSH);
        replyButton.setText("Reply");
        replyButton.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                submitReply();
            }
        });

        updateDetailPane(null);
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
        if (v == null || v.viewer == null || v.viewer.getControl().isDisposed()) {
            return;
        }
        Display display = v.viewer.getControl().getDisplay();
        if (Display.getCurrent() != display) {
            display.asyncExec(() -> v.reload(v.selectedCommentId));
        } else {
            v.reload(v.selectedCommentId);
        }
    }

    /**
     * Opens/activates the Review view and selects {@code commentId}, unchecking "Hide resolved"
     * when {@code includeResolved} is {@code true}.
     */
    public static void showAndSelect(String commentId, boolean includeResolved) {
        if (!PlatformUI.isWorkbenchRunning()) {
            return;
        }
        Display display = PlatformUI.getWorkbench().getDisplay();
        if (display == null || display.isDisposed()) {
            return;
        }
        Runnable task = () -> {
            try {
                org.eclipse.ui.IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
                if (window == null) {
                    return;
                }
                IWorkbenchPage page = window.getActivePage();
                if (page == null) {
                    return;
                }
                org.eclipse.ui.IViewPart part = page.showView(VIEW_ID);
                if (part instanceof ReviewView view) {
                    if (includeResolved && view.hideResolvedCheck != null && !view.hideResolvedCheck.isDisposed()) {
                        view.hideResolvedCheck.setSelection(false);
                    }
                    view.reload(commentId);
                }
            } catch (Exception ignored) {
                // Best-effort UI activation.
            }
        };
        if (Display.getCurrent() != display) {
            display.asyncExec(task);
        } else {
            task.run();
        }
    }

    private void reload(String preserveCommentId) {
        try {
            store.reanchorAll();
        } catch (Exception e) {
            // Best-effort reanchor before loading comments.
        }
        allComments = loadComments();
        if (activeGitFilterMode() != GitDiffModel.FilterMode.ALL) {
            refreshGitSnapshot();
        } else {
            gitDiffCache = new LinkedHashMap<>();
        }
        applyFilterAndRefresh(preserveCommentId);
    }

    private GitDiffModel.FilterMode activeGitFilterMode() {
        if (gitFilterCombo == null || gitFilterCombo.isDisposed()) {
            return GitDiffModel.FilterMode.ALL;
        }
        return switch (gitFilterCombo.getSelectionIndex()) {
            case 1 -> GitDiffModel.FilterMode.CHANGED_FILES;
            case 2 -> GitDiffModel.FilterMode.CHANGED_HUNKS;
            default -> GitDiffModel.FilterMode.ALL;
        };
    }

    private void refreshGitSnapshot() {
        Map<java.nio.file.Path, GitDiffModel.GitDiffSummary> nextCache = new LinkedHashMap<>();
        if (activeGitFilterMode() != GitDiffModel.FilterMode.ALL) {
            for (ReviewComment c : allComments) {
                gitDiffService.summaryForWorkspaceFile(c.file(), nextCache);
            }
        }
        gitDiffCache = nextCache;
    }

    private void applyFilterAndRefresh(String preserveCommentId) {
        if (viewer == null || viewer.getControl().isDisposed()) {
            return;
        }
        boolean hideResolved = hideResolvedCheck != null && hideResolvedCheck.getSelection();
        GitDiffModel.FilterMode gitMode = activeGitFilterMode();
        String query = filterText != null ? filterText.getText().trim().toLowerCase(Locale.ROOT) : "";

        long openCount = allComments.stream().filter(c -> !c.resolved()).count();
        long resolvedCount = allComments.size() - openCount;
        if (countsLabel != null && !countsLabel.isDisposed()) {
            countsLabel.setText("Open: " + openCount + "  Resolved: " + resolvedCount);
            countsLabel.getParent().layout(true);
        }

        Map<String, List<ReviewComment>> grouped = new LinkedHashMap<>();
        List<ReviewComment> sorted = new ArrayList<>(allComments);
        sorted.sort(Comparator.comparing(ReviewComment::file).thenComparingInt(ReviewComment::line));

        for (ReviewComment c : sorted) {
            if (hideResolved && c.resolved()) {
                continue;
            }
            if (gitMode != GitDiffModel.FilterMode.ALL && !gitDiffService.matches(c, gitMode, gitDiffCache)) {
                continue;
            }
            if (!query.isEmpty() && !matchesQuery(c, query)) {
                continue;
            }
            grouped.computeIfAbsent(c.file(), k -> new ArrayList<>()).add(c);
        }

        List<FileGroup> groups = new ArrayList<>();
        for (Map.Entry<String, List<ReviewComment>> entry : grouped.entrySet()) {
            groups.add(new FileGroup(entry.getKey(), entry.getValue()));
        }

        viewer.setInput(groups);
        viewer.expandAll();

        ReviewComment target = findCommentInGroups(groups, preserveCommentId);
        if (target == null && !groups.isEmpty() && !groups.get(0).comments().isEmpty()) {
            target = groups.get(0).comments().get(0);
        }
        if (target != null) {
            selectedCommentId = target.id();
            viewer.setSelection(new StructuredSelection(target), true);
        } else {
            selectedCommentId = null;
            updateDetailPane(null);
        }
    }

    private static boolean matchesQuery(ReviewComment c, String query) {
        if (c.file().toLowerCase(Locale.ROOT).contains(query)) return true;
        if (c.author().toLowerCase(Locale.ROOT).contains(query)) return true;
        if (c.text().toLowerCase(Locale.ROOT).contains(query)) return true;
        for (ReviewComment.Reply r : c.replies()) {
            if (r.author().toLowerCase(Locale.ROOT).contains(query)) return true;
            if (r.text().toLowerCase(Locale.ROOT).contains(query)) return true;
        }
        return false;
    }

    private ReviewComment findCommentInGroups(List<FileGroup> groups, String commentId) {
        if (commentId == null) return null;
        for (FileGroup g : groups) {
            for (ReviewComment c : g.comments()) {
                if (c.id().equals(commentId)) {
                    return c;
                }
            }
        }
        return null;
    }

    private ReviewComment findCommentById(String commentId) {
        if (commentId == null) return null;
        for (ReviewComment c : allComments) {
            if (c.id().equals(commentId)) {
                return c;
            }
        }
        return null;
    }

    private ReviewComment getSelectedComment() {
        IStructuredSelection sel = viewer.getStructuredSelection();
        Object element = sel.getFirstElement();
        return element instanceof ReviewComment c ? c : null;
    }

    private void updateDetailPane(ReviewComment comment) {
        boolean hasSelection = comment != null;
        jumpButton.setEnabled(hasSelection);
        resolveButton.setEnabled(hasSelection);
        replyInput.setEnabled(hasSelection);
        replyButton.setEnabled(hasSelection);

        if (!hasSelection) {
            detailHeaderLabel.setText("Select a review thread");
            resolveButton.setText("Resolve");
            codeSnippetText.setText("");
            transcriptText.setText("");
            return;
        }

        String status = comment.resolved() ? "[RESOLVED]" : "[OPEN]";
        String anchorDecoration = formatAnchorDecoration(comment.anchor());
        String suffix = anchorDecoration.isEmpty() ? "" : "  " + anchorDecoration;
        detailHeaderLabel.setText(comment.file() + ":" + comment.line() + "  " + status + suffix);
        resolveButton.setText(comment.resolved() ? "Reopen" : "Resolve");

        renderCodeSnippet(comment.file(), comment.line());
        renderTranscript(comment);
        detailHeaderLabel.getParent().layout(true);
    }

    private void renderCodeSnippet(String file, int targetLine) {
        SnippetResult snippet = readCodeSnippet(file, targetLine);
        codeSnippetText.setText(snippet.text());
        if (snippet.highlightStart() >= 0 && snippet.highlightLength() > 0) {
            Display display = codeSnippetText.getDisplay();
            StyleRange range = new StyleRange();
            range.start = snippet.highlightStart();
            range.length = snippet.highlightLength();
            range.background = display.getSystemColor(SWT.COLOR_INFO_BACKGROUND);
            range.fontStyle = SWT.BOLD;
            codeSnippetText.setStyleRange(range);
        }
    }

    private void renderTranscript(ReviewComment comment) {
        StringBuilder sb = new StringBuilder();
        List<StyleRange> styles = new ArrayList<>();
        Display display = transcriptText.getDisplay();

        appendMessage(sb, styles, display, comment.author(), comment.timestamp(), comment.text(), false);
        for (ReviewComment.Reply reply : comment.replies()) {
            sb.append("\n");
            appendMessage(sb, styles, display, reply.author(), reply.timestamp(), reply.text(), true);
        }

        transcriptText.setText(sb.toString());
        transcriptText.setStyleRanges(styles.toArray(new StyleRange[0]));
    }

    private static void appendMessage(
            StringBuilder sb,
            List<StyleRange> styles,
            Display display,
            String author,
            long timestamp,
            String body,
            boolean isReply) {
        String prefix = isReply ? "  \u21b3 " : "";
        int authorStart = sb.length() + prefix.length();
        sb.append(prefix).append(author);
        StyleRange authorStyle = new StyleRange();
        authorStyle.start = authorStart;
        authorStyle.length = author.length();
        authorStyle.fontStyle = SWT.BOLD;
        styles.add(authorStyle);

        String timePart = " (" + formatTime(timestamp) + "):\n";
        int timeStart = sb.length();
        sb.append(timePart);
        StyleRange timeStyle = new StyleRange();
        timeStyle.start = timeStart;
        timeStyle.length = timePart.length();
        timeStyle.foreground = display.getSystemColor(SWT.COLOR_DARK_GRAY);
        styles.add(timeStyle);

        String indent = isReply ? "    " : "";
        sb.append(indent).append(body).append("\n");
    }

    private void submitReply() {
        ReviewComment current = findCommentById(selectedCommentId);
        if (current == null) return;
        String text = replyInput.getText().trim();
        if (text.isEmpty()) return;

        try {
            store.addReply(current.id(), "user", text);
            replyInput.setText("");
            reload(current.id());
        } catch (Exception e) {
            // Best-effort
        }
    }

    private void toggleResolveSelected() {
        ReviewComment current = findCommentById(selectedCommentId);
        if (current == null) return;

        try {
            boolean nextResolved = !current.resolved();
            ReviewComment updated = store.setResolved(current.id(), nextResolved);
            if (nextResolved) {
                ReviewMarkerManager.remove(updated.id());
            } else {
                ReviewMarkerManager.add(updated);
            }
            reload(updated.id());
        } catch (Exception e) {
            // Best-effort
        }
    }

    private void removeSelected() {
        ReviewComment current = findCommentById(selectedCommentId);
        if (current == null) return;

        try {
            store.remove(current.id());
            ReviewMarkerManager.remove(current.id());
            reload(null);
        } catch (Exception e) {
            // Best-effort
        }
    }

    private void copyToClipboard(String text) {
        Clipboard clipboard = new Clipboard(viewer.getControl().getDisplay());
        try {
            clipboard.setContents(new Object[] { text }, new Transfer[] { TextTransfer.getInstance() });
        } finally {
            clipboard.dispose();
        }
    }

    private static String formatTime(long epochMillis) {
        return TIME_FMT.format(Instant.ofEpochMilli(epochMillis));
    }

    private record SnippetResult(String text, int highlightStart, int highlightLength) {}

    private static SnippetResult readCodeSnippet(String file, int targetLine) {
        if (targetLine <= 0) return new SnippetResult("", -1, 0);
        try {
            IFile iFile = ResourcesPlugin.getWorkspace().getRoot().getFile(new Path(file));
            if (!iFile.exists()) return new SnippetResult("", -1, 0);
            List<String> lines = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(iFile.getContents(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    lines.add(line);
                }
            }
            int start = Math.max(1, targetLine - 2);
            int end = Math.min(lines.size(), targetLine + 2);
            StringBuilder sb = new StringBuilder();
            int highlightStart = -1;
            int highlightLength = 0;
            for (int n = start; n <= end; n++) {
                String marker = n == targetLine ? ">" : " ";
                String formatted = String.format("%s %4d | %s%n", marker, n, lines.get(n - 1));
                if (n == targetLine) {
                    highlightStart = sb.length();
                    highlightLength = formatted.length();
                }
                sb.append(formatted);
            }
            return new SnippetResult(sb.toString(), highlightStart, highlightLength);
        } catch (Exception e) {
            return new SnippetResult("", -1, 0);
        }
    }

    private List<ReviewComment> loadComments() {
        try {
            return store.list(null);
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
            if (editor instanceof ITextEditor textEditor && line > 0) {
                IDocument doc = textEditor.getDocumentProvider().getDocument(textEditor.getEditorInput());
                if (doc != null && doc.getNumberOfLines() > 0) {
                    int clampedLine = Math.max(1, Math.min(line, doc.getNumberOfLines()));
                    int offset = doc.getLineOffset(clampedLine - 1);
                    textEditor.selectAndReveal(offset, 0);
                }
            }
        } catch (Exception e) {
            // Best-effort
        }
    }

    private record FileGroup(String file, List<ReviewComment> comments) {}

    private static class ContentProvider implements ITreeContentProvider {
        @Override
        public Object[] getElements(Object input) {
            if (input instanceof List<?> list) return list.toArray();
            return new Object[0];
        }

        @Override
        public Object[] getChildren(Object parent) {
            if (parent instanceof FileGroup group) return group.comments().toArray();
            return new Object[0];
        }

        @Override
        public Object getParent(Object element) {
            return null;
        }

        @Override
        public boolean hasChildren(Object element) {
            return element instanceof FileGroup group && !group.comments().isEmpty();
        }
    }

    private static class ReviewLabelProvider extends LabelProvider implements IStyledLabelProvider {
        @Override
        public Image getImage(Object element) {
            if (element instanceof FileGroup) {
                return PlatformUI.getWorkbench().getSharedImages().getImage(ISharedImages.IMG_OBJ_FILE);
            }
            return null;
        }

        @Override
        public StyledString getStyledText(Object element) {
            return switch (element) {
                case FileGroup g -> {
                    long open = g.comments().stream().filter(c -> !c.resolved()).count();
                    StyledString ss = new StyledString(g.file());
                    ss.append(" (" + open + " open / " + g.comments().size() + ")", StyledString.COUNTER_STYLER);
                    yield ss;
                }
                case ReviewComment c -> {
                    StyledString ss = new StyledString();
                    ss.append("L" + c.line() + " ", StyledString.QUALIFIER_STYLER);
                    if (c.anchor() != null && LineAnchor.STATUS_RELOCATED.equals(c.anchor().status())) {
                        ss.append("(moved) ", StyledString.DECORATIONS_STYLER);
                    }
                    if (c.resolved()) {
                        ss.append("[RESOLVED] ", StyledString.DECORATIONS_STYLER);
                    } else {
                        ss.append("[OPEN] ", StyledString.COUNTER_STYLER);
                    }
                    if (c.anchor() != null && LineAnchor.STATUS_STALE.equals(c.anchor().status())) {
                        ss.append("[STALE] ", StyledString.DECORATIONS_STYLER);
                    }
                    ss.append(c.author() + ": " + c.text());
                    int count = c.replies().size();
                    if (count > 0) {
                        String word = count == 1 ? "reply" : "replies";
                        ss.append(" (" + count + " " + word + ")", StyledString.COUNTER_STYLER);
                    }
                    yield ss;
                }
                default -> new StyledString(String.valueOf(element));
            };
        }

        @Override
        public String getText(Object element) {
            return getStyledText(element).getString();
        }
    }

    private static String formatAnchorDecoration(LineAnchor anchor) {
        if (anchor == null || anchor.status() == null) {
            return "";
        }
        return switch (anchor.status()) {
            case LineAnchor.STATUS_STALE -> "[STALE]";
            case LineAnchor.STATUS_RELOCATED -> "(moved)";
            default -> "";
        };
    }
}
