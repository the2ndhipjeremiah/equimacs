package org.equimacs.eclipse.ui.review;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.Position;
import org.eclipse.jface.text.source.Annotation;
import org.eclipse.jface.text.source.IAnnotationModel;
import org.eclipse.jface.text.source.IAnnotationModelExtension;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPartSite;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.editors.text.EditorsUI;
import org.eclipse.ui.texteditor.IDocumentProvider;
import org.eclipse.ui.texteditor.ITextEditor;

/**
 * Thin SWT/JFace manager for temporary editor range highlights.
 */
public final class EditorHighlightManager {

    public static final String ANNOTATION_TYPE = "org.equimacs.eclipse.ui.tempHighlight";

    private record ActiveHighlight(IAnnotationModel model, Annotation annotation, long generation) {}

    private static final Object LOCK = new Object();
    private static final Map<IAnnotationModel, ActiveHighlight> ACTIVE = new IdentityHashMap<>();
    private static final AtomicLong GENERATION = new AtomicLong();

    private EditorHighlightManager() {}

    /**
     * Initializes default JFace editor annotation preferences for temporary highlights.
     */
    public static void initAnnotationPreferences() {
        IPreferenceStore store = EditorsUI.getPreferenceStore();
        store.setValue(ANNOTATION_TYPE + ".highlight", true);
        store.setValue(ANNOTATION_TYPE + ".text", true);
        store.setValue(ANNOTATION_TYPE + ".textStyle", "HIGHLIGHT");
        store.setValue(ANNOTATION_TYPE + ".color", "255,230,128");
        store.setValue(ANNOTATION_TYPE + ".overviewRuler", true);
        store.setValue(ANNOTATION_TYPE + ".verticalRuler", false);
    }

    /**
     * Applies a temporary highlight annotation on {@code editor}'s annotation model and schedules removal.
     */
    public static void highlight(ITextEditor editor, IDocument doc, int offset, int length, int durationMs) {
        if (editor == null || doc == null || offset < 0) {
            return;
        }
        IDocumentProvider provider = editor.getDocumentProvider();
        IEditorInput input = editor.getEditorInput();
        if (provider == null || input == null) {
            return;
        }
        IAnnotationModel model = provider.getAnnotationModel(input);
        if (model == null) {
            return;
        }

        Display display = resolveDisplay(editor);
        if (display == null || display.isDisposed()) {
            return;
        }

        int docLen = doc.getLength();
        int safeOffset = Math.max(0, Math.min(offset, docLen));
        int safeLength = Math.max(0, Math.min(length, docLen - safeOffset));
        int clampedDurationMs = EditorHighlightCore.clampDuration(durationMs);

        long gen = GENERATION.incrementAndGet();
        Annotation next = new Annotation(ANNOTATION_TYPE, false, "Equimacs highlight");
        Position position = new Position(safeOffset, safeLength);

        ActiveHighlight previous;
        synchronized (LOCK) {
            previous = ACTIVE.get(model);
            ACTIVE.put(model, new ActiveHighlight(model, next, gen));
        }

        swapAnnotation(model, previous != null ? previous.annotation() : null, next, position);
        Runnable removeTask = () -> {
            ActiveHighlight current;
            synchronized (LOCK) {
                current = ACTIVE.get(model);
                if (current == null || current.generation() != gen) {
                    return;
                }
                ACTIVE.remove(model);
            }
            safeRemove(model, current.annotation());
        };
        if (Display.getCurrent() == display) {
            display.timerExec(clampedDurationMs, removeTask);
        } else {
            display.asyncExec(() -> {
                if (!display.isDisposed()) {
                    display.timerExec(clampedDurationMs, removeTask);
                }
            });
        }
    }

    /**
     * Removes all tracked temporary highlight annotations.
     */
    public static void disposeAll() {
        List<ActiveHighlight> snapshot;
        synchronized (LOCK) {
            snapshot = List.copyOf(ACTIVE.values());
            ACTIVE.clear();
        }
        if (snapshot.isEmpty()) {
            return;
        }
        Display display = workbenchDisplay();
        Runnable cleanup = () -> {
            for (ActiveHighlight item : snapshot) {
                safeRemove(item.model(), item.annotation());
            }
        };
        if (display != null && !display.isDisposed() && Display.getCurrent() != display) {
            display.asyncExec(cleanup);
        } else {
            cleanup.run();
        }
    }

    private static void swapAnnotation(
            IAnnotationModel model,
            Annotation previous,
            Annotation next,
            Position position) {
        if (model instanceof IAnnotationModelExtension ext) {
            Annotation[] toRemove = previous != null ? new Annotation[] { previous } : new Annotation[0];
            ext.replaceAnnotations(toRemove, Map.of(next, position));
        } else {
            if (previous != null) {
                safeRemove(model, previous);
            }
            model.addAnnotation(next, position);
        }
    }

    private static void safeRemove(IAnnotationModel model, Annotation annotation) {
        if (model == null || annotation == null) {
            return;
        }
        try {
            model.removeAnnotation(annotation);
        } catch (RuntimeException ignored) {
            // Ignore when the editor or annotation model has already closed.
        }
    }

    private static Display resolveDisplay(ITextEditor editor) {
        IWorkbenchPartSite site = editor.getSite();
        if (site != null && site.getShell() != null && !site.getShell().isDisposed()) {
            return site.getShell().getDisplay();
        }
        return workbenchDisplay();
    }

    private static Display workbenchDisplay() {
        if (!PlatformUI.isWorkbenchRunning()) {
            return null;
        }
        IWorkbench workbench;
        try {
            workbench = PlatformUI.getWorkbench();
        } catch (IllegalStateException e) {
            return null;
        }
        if (workbench == null || workbench.isClosing()) {
            return null;
        }
        Display display = workbench.getDisplay();
        return (display != null && !display.isDisposed()) ? display : null;
    }
}
