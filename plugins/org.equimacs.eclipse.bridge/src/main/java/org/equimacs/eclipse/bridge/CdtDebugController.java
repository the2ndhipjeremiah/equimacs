package org.equimacs.eclipse.bridge;

// Placeholder for CDT (C/C++) debug operations.
// JDT and CDT have incompatible breakpoint/thread APIs — they are intentionally separate.
// Implement using org.eclipse.cdt.debug.core APIs when CDT support is added.
public class CdtDebugController {

    public void setBreakpoint(String filePath, int lineNumber, String condition) {
        throw new UnsupportedOperationException("CDT breakpoints not yet implemented");
    }
}
