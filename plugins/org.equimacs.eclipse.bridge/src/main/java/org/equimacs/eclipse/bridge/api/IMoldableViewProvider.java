package org.equimacs.eclipse.bridge.api;

import java.util.List;

/**
 * OSGi whiteboard extension interface allowing built-in or external bundles to contribute
 * contextual tabs to the Equimacs Moldable Inspector without depending on SWT/Eclipse UI.
 */
public interface IMoldableViewProvider {
    String id();
    boolean supports(InspectContext context);
    List<MoldableTab> provideViews(InspectContext context);
}
