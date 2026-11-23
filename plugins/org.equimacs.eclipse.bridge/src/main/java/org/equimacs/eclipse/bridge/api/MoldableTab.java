package org.equimacs.eclipse.bridge.api;

/**
 * Declarative inspector tab contributed by an {@link IMoldableViewProvider}.
 * Lower priority renders earlier; specJson is any valid ModdableView ViewSpec JSON.
 */
public record MoldableTab(
    String id,
    String label,
    int priority,
    String specJson
) {}
