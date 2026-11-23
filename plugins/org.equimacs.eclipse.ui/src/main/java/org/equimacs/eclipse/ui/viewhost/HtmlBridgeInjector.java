package org.equimacs.eclipse.ui.viewhost;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure Java HTML bridge script injector and JavaScript snippet builder for WebView2 / SWT Browser views.
 * Exposes {@code window.equimacs} and resolves asynchronous request promises back to the host.
 */
public final class HtmlBridgeInjector {

    private static final Gson GSON = new Gson();
    private static final Pattern HEAD_OPEN_PATTERN =
        Pattern.compile("<head(?:\\s[^>]*)?>", Pattern.CASE_INSENSITIVE);
    private static final Pattern BODY_CLOSE_PATTERN =
        Pattern.compile("</body\\s*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern SCRIPT_CLOSE_PATTERN =
        Pattern.compile("</script", Pattern.CASE_INSENSITIVE);
    private static final String SCRIPT_CLOSE_REPLACEMENT = Matcher.quoteReplacement("<\\/script");

    private HtmlBridgeInjector() {}

    /**
     * Injects the {@code window.equimacs} bridge script into {@code html} with initial {@code viewId}
     * and {@code stateJson} payload.
     */
    public static String inject(String html, String viewId, String stateJson) {
        String source = html == null ? "" : html;
        String scriptTag = "<script>\n" + buildBridgeBootstrapJs(viewId, stateJson) + "\n</script>\n";

        Matcher headMatcher = HEAD_OPEN_PATTERN.matcher(source);
        if (headMatcher.find()) {
            int pos = headMatcher.end();
            return source.substring(0, pos) + "\n" + scriptTag + source.substring(pos);
        }

        Matcher bodyMatcher = BODY_CLOSE_PATTERN.matcher(source);
        if (bodyMatcher.find()) {
            int pos = bodyMatcher.start();
            return source.substring(0, pos) + scriptTag + source.substring(pos);
        }

        return scriptTag + source;
    }

    /**
     * Builds the JavaScript bootstrap block defining {@code window.equimacs},
     * {@code window.__equimacsResolve}, {@code window.__equimacsPushState}, and
     * {@code window.__equimacsPushEvent}.
     */
    public static String buildBridgeBootstrapJs(String viewId, String stateJson) {
        String safeViewIdJson = escapeEmbeddedJson(GSON.toJson(viewId == null ? "" : viewId));
        String safeStateJson = normalizeJsonLiteral(stateJson, "{}");

        return """
            (function() {
              const initialState = %s;
              if (window.equimacs && typeof window.__equimacsPushState === 'function') {
                window.__equimacsPushState(initialState);
                return;
              }
              const updateListeners = [];
              const eventListeners = [];
              const pendingCalls = Object.create(null);
              let nextCallId = 1;

              function callHostAction(obj) {
                if (!obj || typeof obj !== 'object') {
                  return;
                }
                const payload = JSON.stringify(obj);
                if (typeof window.__equimacsAction === 'function') {
                  window.__equimacsAction(payload);
                } else if (typeof __equimacsAction === 'function') {
                  __equimacsAction(payload);
                }
              }

              const eq = {
                viewId: %s,
                state: initialState,
                action: function(obj) {
                  callHostAction(obj);
                },
                openFile: function(file, line, endLine) {
                  const start = (line === undefined || line === null) ? -1 : line;
                  const end = (endLine === undefined || endLine === null) ? start : endLine;
                  callHostAction({ type: 'open-file', file: file, line: start, endLine: end });
                },
                showView: function(targetViewId, secondaryId) {
                  callHostAction({
                    type: 'show-view',
                    viewId: targetViewId,
                    secondaryId: secondaryId === undefined ? null : secondaryId
                  });
                },
                eclipseCommand: function(commandId, file, offset, params) {
                  callHostAction({
                    type: 'eclipse-command',
                    commandId: commandId,
                    file: file === undefined ? null : file,
                    offset: (offset === undefined || offset === null) ? -1 : offset,
                    params: params || {}
                  });
                },
                custom: function(target, actionName, args) {
                  callHostAction({
                    type: 'custom',
                    target: target,
                    action: actionName,
                    args: args === undefined ? null : args
                  });
                },
                inspect: function(spec) {
                  const payload = Object.assign({}, spec || {}, { type: 'inspect' });
                  if (!payload.targetKind && payload.kind) {
                    payload.targetKind = payload.kind;
                  }
                  callHostAction(payload);
                },
                toolkitView: function(tool, params) {
                  callHostAction({
                    type: 'toolkit-view',
                    tool: tool,
                    params: params || {}
                  });
                },
                emit: function(name, payload) {
                  callHostAction({
                    type: 'emit',
                    name: name,
                    payload: payload === undefined ? null : payload
                  });
                },
                request: function(type, params) {
                  return new Promise(function(resolve, reject) {
                    const callId = nextCallId++;
                    pendingCalls[callId] = { resolve: resolve, reject: reject };
                    try {
                      const jsonParams = JSON.stringify(params || {});
                      if (typeof window.__equimacsRequest === 'function') {
                        window.__equimacsRequest(callId, String(type), jsonParams);
                      } else if (typeof __equimacsRequest === 'function') {
                        __equimacsRequest(callId, String(type), jsonParams);
                      } else {
                        delete pendingCalls[callId];
                        reject(new Error('Equimacs request bridge unavailable'));
                      }
                    } catch (err) {
                      delete pendingCalls[callId];
                      reject(err);
                    }
                  });
                },
                onUpdate: function(fn) {
                  if (typeof fn === 'function') {
                    updateListeners.push(fn);
                  }
                  return function() {
                    const idx = updateListeners.indexOf(fn);
                    if (idx >= 0) {
                      updateListeners.splice(idx, 1);
                    }
                  };
                },
                onEvent: function(fn) {
                  if (typeof fn === 'function') {
                    eventListeners.push(fn);
                  }
                  return function() {
                    const idx = eventListeners.indexOf(fn);
                    if (idx >= 0) {
                      eventListeners.splice(idx, 1);
                    }
                  };
                }
              };

              window.equimacs = eq;

              window.__equimacsResolve = function(callId, ok, dataOrErr) {
                const entry = pendingCalls[callId];
                if (!entry) {
                  return;
                }
                delete pendingCalls[callId];
                if (ok) {
                  entry.resolve(dataOrErr);
                } else {
                  const message = (dataOrErr && typeof dataOrErr === 'object' && dataOrErr.error)
                    ? String(dataOrErr.error)
                    : (typeof dataOrErr === 'string' ? dataOrErr : JSON.stringify(dataOrErr));
                  entry.reject(new Error(message || 'Request failed'));
                }
              };

              window.__equimacsPushState = function(s) {
                const next = (typeof s === 'string') ? JSON.parse(s) : (s || {});
                eq.state = next;
                for (let i = 0; i < updateListeners.length; i++) {
                  try {
                    updateListeners[i](next);
                  } catch (err) {
                    if (typeof console !== 'undefined' && console.error) {
                      console.error(err);
                    }
                  }
                }
              };

              window.__equimacsPushEvent = function(ev) {
                const parsed = (typeof ev === 'string') ? JSON.parse(ev) : (ev || {});
                for (let i = 0; i < eventListeners.length; i++) {
                  try {
                    eventListeners[i](parsed);
                  } catch (err) {
                    if (typeof console !== 'undefined' && console.error) {
                      console.error(err);
                    }
                  }
                }
              };
            })();
            """.formatted(safeStateJson, safeViewIdJson);
    }

    /**
     * Builds the JavaScript snippet that resolves or rejects an in-flight {@code window.equimacs.request} promise.
     */
    public static String buildResolveJs(long callId, boolean ok, String jsonPayload) {
        String safePayload = normalizeJsonLiteral(jsonPayload, "null");
        return "if (window.__equimacsResolve) { window.__equimacsResolve("
            + callId + ", " + ok + ", " + safePayload + "); }";
    }

    /**
     * Builds the JavaScript snippet that pushes updated state into {@code window.equimacs}.
     */
    public static String buildPushStateJs(String stateJson) {
        String safeState = normalizeJsonLiteral(stateJson, "{}");
        return "if (window.__equimacsPushState) { window.__equimacsPushState(" + safeState + "); }";
    }

    /**
     * Builds the JavaScript snippet that pushes a bridge event into {@code window.equimacs}.
     */
    public static String buildPushEventJs(String eventJson) {
        String safeEvent = normalizeJsonLiteral(eventJson, "{}");
        return "if (window.__equimacsPushEvent) { window.__equimacsPushEvent(" + safeEvent + "); }";
    }

    static String escapeEmbeddedJson(String json) {
        if (json == null) {
            return "";
        }
        String escaped = SCRIPT_CLOSE_PATTERN.matcher(json).replaceAll(SCRIPT_CLOSE_REPLACEMENT);
        return escaped.replace("\u2028", "\\u2028").replace("\u2029", "\\u2029");
    }

    private static String normalizeJsonLiteral(String rawJson, String fallback) {
        if (rawJson == null || rawJson.isBlank()) {
            return fallback;
        }
        String trimmed = rawJson.trim();
        try {
            JsonElement parsed = JsonParser.parseString(trimmed);
            if (parsed == null) {
                return fallback;
            }
            return escapeEmbeddedJson(trimmed);
        } catch (JsonSyntaxException ignored) {
            return fallback;
        }
    }
}
