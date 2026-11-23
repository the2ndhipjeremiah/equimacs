# Equimacs Moldable Toolkit & Moddable View Host

Equimacs includes a **native-first** workbench UI toolkit built on Eclipse SWT, JFace, JDT, and OSGi Declarative Services (DS). All built-in tools (`call-explorer`, `trace-view`, `junit-view`, and the Moldable Inspector) render purely with native Java/SWT controls — no Node runtime or browser dependency is required. An optional WebView2/HTML host (`kind: "html"`) is provided as an escape hatch for custom visualizations.

---

## 1. Moddable View Host (`view-open`, `view-update`, `view-close`, `view-list`)

The `ModdableView` view part (`org.equimacs.eclipse.ui.moddableView`, `allowMultiple="true"`) hosts dynamic views driven over the bridge or internally by toolkit commands.

### Rules & Limits
- **`viewId`**: Required secondary view ID matching `^[A-Za-z0-9._-]+$`, max **128** characters, must not contain `:` (checked by `ViewHostStore.validateViewId`). Auto-generated inspector IDs are capped at **100** characters (`MoldableInspectorCore.defaultViewId`).
- **`MAX_LAYOUT_DEPTH = 6`**: Maximum recursive container depth across nested `tabs` and `split` specs.
- **`MAX_TREE_DEPTH = 64`**: Maximum node nesting inside a `tree` spec.
- **Native-first preference**: Prefer native Java/SWT kinds (`table`, `tree`, `markdown`, `key-value`, `tabs`, `graph`, `split`, `form`) whenever structured tables, trees, graphs, forms, or dashboards suffice. Reserve `html` for bespoke web visualizations.

### Supported `ViewSpec` Kinds (9)

Every `ViewSpec` JSON object accepts common optional fields:
- `title` (`string`, optional; overridden if top-level request `title` is supplied)
- `toolbar` (`ActionItem[]`, optional): `[{ "label": "...", "tooltip": "...", "action": <ViewAction> }]`

#### `table`
Filterable, sortable SWT table with row selection/activation and CSV/cell copy context menu:
```json
{
  "kind": "table",
  "title": "Problems",
  "columns": ["Severity", "File", "Message"],
  "rows": [
    {
      "id": "p1",
      "cells": ["ERROR", "/myproject/src/Main.java", "Unresolved symbol"],
      "onSelect": { "type": "emit", "name": "rowSelected", "payload": "p1" },
      "onActivate": { "type": "open-file", "file": "/myproject/src/Main.java", "line": 42 }
    }
  ]
}
```

#### `tree`
Hierarchical single- or multi-column SWT tree (`roots` or alias `nodes`):
```json
{
  "kind": "tree",
  "title": "Callers",
  "columns": ["Method", "Location"],
  "expandAll": true,
  "roots": [
    {
      "id": "n1",
      "label": "OrderController.checkout",
      "values": ["/myproject/src/OrderController.java:28"],
      "badge": "RECURSIVE",
      "onActivate": { "type": "open-file", "file": "/myproject/src/OrderController.java", "line": 28 },
      "children": []
    }
  ]
}
```

#### `markdown`
Styled native SWT text viewer (`StyledText`) supporting headings, lists, fences, and clickable `open-file:` / bridge action links:
```json
{
  "kind": "markdown",
  "title": "Notes",
  "content": "# Summary\n\n- Found 2 hotspots in `OrderService`"
}
```

#### `key-value`
Grouped two-column property inspector (`sections` or top-level `entries`):
```json
{
  "kind": "key-value",
  "title": "Frame",
  "sections": [
    {
      "heading": "Location",
      "entries": [
        {
          "key": "File",
          "value": "/myproject/src/OrderService.java:58",
          "onActivate": { "type": "open-file", "file": "/myproject/src/OrderService.java", "line": 58 }
        }
      ]
    }
  ]
}
```

#### `tabs`
Native `CTabFolder` hosting named nested `ViewSpec` panes:
```json
{
  "kind": "tabs",
  "title": "Inspector",
  "tabs": [
    { "label": "Overview", "content": { "kind": "key-value", "sections": [] } },
    { "label": "Details", "content": { "kind": "markdown", "content": "..." } }
  ]
}
```

#### `graph` (Native SWT `Canvas` + `GC`)
Double-buffered interactive directed graph (`GraphLayoutCore` + `GraphCanvasRenderer`):
- `layout`: `"focus"` (default; 3-zone caller/center/callee horizontal placement) or `"layered"` (topological DAG layers).
- `nodes`: array of node objects (`id` required and **unique** — duplicate node IDs are rejected by `ViewSpecParser`):
  - `id` (`string`, required), `label` (`string`), `sublabel` (`string`), `group` (`"left"` | `"center"` | `"right"`, default `"center"`), `layer` (`int`), `badge` (`string`), `secondaryLabel` (`string`)
  - Optional actions: `onSelect` (click), `onActivate` (double-click), `onSecondary` (Shift+click or right-click menu)
- `edges`: array of `{ "from": "<id>", "to": "<id>", "label": "...", "style": "solid" | "dashed" }`
- Built-in canvas interactions: **Wheel** zoom (`0.25x..2.0x`), **Drag** pan, **Fit / Reset / Zoom + / Zoom -** buttons, right-click context menu (`Open`, secondary action, copy label/ID).

```json
{
  "kind": "graph",
  "title": "Local Neighborhood",
  "layout": "focus",
  "nodes": [
    { "id": "caller1", "label": "main()", "sublabel": "App", "group": "left" },
    { "id": "target", "label": "process()", "sublabel": "Service", "group": "center" },
    { "id": "callee1", "label": "save()", "sublabel": "Repo", "group": "right" }
  ],
  "edges": [
    { "from": "caller1", "to": "target", "style": "solid" },
    { "from": "target", "to": "callee1", "style": "dashed", "label": "async" }
  ]
}
```

#### `split` (Native SWT `SashForm`)
Weighted horizontal or vertical split container nesting any `ViewSpec` panes:
- `orientation`: `"horizontal"` (default) or `"vertical"`
- `panes`: non-empty array of nested `ViewSpec` objects
- `weights`: optional positive integer array matching `panes.length`

```json
{
  "kind": "split",
  "title": "Compare",
  "orientation": "horizontal",
  "weights": [55, 45],
  "panes": [
    { "kind": "tree", "roots": [] },
    { "kind": "graph", "layout": "focus", "nodes": [], "edges": [] }
  ]
}
```

#### `form` (Native SWT Form Controls)
Validated native SWT input form (`FormSpecSupport`) whose submitted values merge into `onSubmit`:
- `description` (`string`, optional), `submitLabel` (`string`, default `"Submit"`), `onSubmit` (`ViewAction`, required)
- `fields`: `[{ "name": "...", "label": "...", "type": "text"|"textarea"|"checkbox"|"select"|"number", "defaultValue": "...", "placeholder": "...", "options": ["..."], "required": true|false }]`
- Submission merges field key/value pairs directly into:
  - `emit.payload` / `custom.args` (as a JSON object)
  - `eclipse-command.params` / `toolkit-view.params`
  - `inspect.expression` and `inspect.file` (when non-blank field `"expression"` or `"file"` is present)

#### `html` (Optional WebView2 / SWT `Browser` Escape Hatch)
Renders custom HTML/JS/SVG via `SWT.EDGE` (WebView2 on Windows), falling back cleanly to `SWT.NONE` and then a read-only `StyledText` notice when no browser backend exists:
- `html` (or alias `content`): inline HTML document string
- `url`: external or `http(s)`/`file` URL
- `state`: optional initial JSON object exposed at `window.equimacs.state`

---

## 2. Interactive `ViewAction` Types (7)

Toolbar items, table rows, tree nodes, key-value entries, graph nodes, and forms dispatch typed actions off the UI thread (`ViewActionDispatcher`):

| `type` | Required Fields | Optional Fields | Behavior |
|--------|----------------|-----------------|----------|
| `open-file` | `file` | `line`, `endLine` | Opens file in Eclipse editor and highlights the line range |
| `eclipse-command` | `commandId` | `file`, `offset`, `params` | Navigates if `file` given, then executes parameterized workbench command |
| `show-view` | `viewId` | `secondaryId` | Activates an Eclipse workbench view |
| `custom` | `target`, `action` | `args` | Invokes an external `IBridgeCommandHandler` registered for `custom:<target>` |
| `emit` | `name` | `payload` | Publishes a `ViewAction` bridge event observable via `wait-event` |
| `inspect` | `targetKind` (`jdi-frame` \| `jdi-value` \| `jdt-symbol`) | `frameId`, `expression`, `file`, `offset`, `viewId` | Evaluates & opens the Moldable Inspector on the target |
| `toolkit-view` | `tool` | `params` | Opens/refreshes a built-in toolkit view (`call-explorer`, `trace`, `trace-clear`, `junit`, `junit-rerun`) |

---

## 3. Live Event Bindings (`live`)

Any top-level `view-open` or `view-update` JSON payload may include a top-level `live` object alongside the `ViewSpec`:

```json
{
  "kind": "markdown",
  "title": "Live Monitor",
  "content": "Waiting for LogpointHit...",
  "live": {
    "events": ["LogpointHit", "TestRunFinished", "ViewAction"],
    "onEvent": { "type": "toolkit-view", "tool": "trace", "params": { "limit": "200", "live": "true" } },
    "pushToHtml": false,
    "debounceMs": 200
  }
}
```

- `events` (`string[]`): bridge event names to match against the event's `"event"` field (e.g. `BreakpointHit`, `LogpointHit`, `TestRunFinished`, `ViewAction`, `StepCompleted`).
- `onEvent` (`ViewAction`, optional): debounced action dispatched after coalescing rapid event bursts (`debounceMs` defaults to `150`, clamped to `50..5000`).
- `pushToHtml` (`boolean`, optional): when `true`, immediately pushes matching event JSON objects into live HTML views via `window.equimacs.onEvent`.
- Closing the view unregisters its live binding automatically; hot-reloading `org.equimacs.eclipse.bridge` rehydrates active bindings automatically (`ModdableViewCommandHandler.rehydrateLiveBindings`).

---

## 4. Optional WebView2 JS Bridge (`window.equimacs`)

For `kind: "html"` views, `HtmlBridgeInjector` injects `window.equimacs` (both on `setText` and `setUrl` completion) backed by asynchronous SWT `BrowserFunction` callbacks (`__equimacsAction` and `__equimacsRequest`) so the UI thread never deadlocks:

- `window.equimacs.viewId`: logical `viewId` string
- `window.equimacs.state`: current JSON state object pushed on open and on matching-source `view-update`
- `window.equimacs.action(obj)`: dispatch any raw `ViewAction` JSON object
- `window.equimacs.openFile(file, line, endLine)`
- `window.equimacs.showView(viewId, secondaryId)`
- `window.equimacs.eclipseCommand(commandId, file, offset, params)`
- `window.equimacs.custom(target, action, args)`
- `window.equimacs.inspect(spec)`: dispatches an `inspect` action
- `window.equimacs.toolkitView(tool, params)`
- `window.equimacs.emit(name, payload)`
- `window.equimacs.request(type, params)`: returns a `Promise` resolved asynchronously with the typed bridge response result
- `window.equimacs.onUpdate(fn)` / `window.equimacs.onEvent(fn)`: register listeners; both return an unsubscribe function

**Security guardrails**: `window.equimacs.request(type, params)` routes through `IBridgeService.dispatchJson(...)`, which explicitly blocks `Shutdown`, `Reload`, and `GogoExec` from view scripts.

---

## 5. Moldable Inspector SPI (`IMoldableViewProvider`)

### Commands
- `eqm-cli inspect-frame <frameId> [--open true|false | --no-open] [--view-id <id>]`
- `eqm-cli inspect-value <frameId> <expression...> [--expr <expr>] [--depth <n>] [--open true|false | --no-open] [--view-id <id>]`
- `eqm-cli inspect-symbol <file>:<offset> [--open true|false | --no-open] [--view-id <id>]`
- `eqm-cli inspect-providers`

**Headless vs. IDE behavior**: `InspectFrame`, `InspectValue`, and `InspectSymbol` live in `org.equimacs.debug`, so they work in both Eclipse IDE and headless `eqmd`. In headless mode (when `org.equimacs.eclipse.ui` is not present to handle `ViewOpen`), the response succeeds with `"viewOpened": false`, an explanatory `"viewOpenNote"`, the matched `"providers"` list, `"tabs"`, and the complete assembled `"spec"` (`TabsSpec`).

### Built-in Providers

| Provider ID | `targetKind` | Contributed Tabs (priority) |
|---|---|---|
| `equimacs.builtin.frame` | `jdi-frame` | `overview` (10), `variables` (20, click row to inspect variable), `evaluate` (30, native `form`) |
| `equimacs.builtin.raw-object` | `jdi-value` | `overview` (10), `fields` (20, cycle-guarded recursive field tree up to depth `1..4`, max `100` items/node) |
| `equimacs.builtin.collection` | `jdi-value` | `elements` (15) for arrays/collections, `entries` (15) for maps |
| `equimacs.builtin.string` | `jdi-value` (`String` / `CharSequence`) | `json` (18, parsed interactive JSON tree when valid `{...}`/`[...]`), `text` (25) |
| `equimacs.builtin.jdt-symbol` | `jdt-symbol` | `overview` (10), `members` (20), `references` (30), `hierarchy` (40), `calls` (45, with `Open Call Explorer` button) |

### Tab Assembly & Override Semantics
1. Built-in providers evaluate first; external OSGi `IMoldableViewProvider` services evaluate second.
2. Tabs are deduplicated by `MoldableTab.id()` — later (external) providers **override** built-in or earlier tabs that share the same `id` (for example, returning a custom `"overview"` tab replaces the built-in overview).
3. Final tabs are sorted deterministically by `(priority ascending, label ascending, id ascending)` into a `TabsSpec`.
4. If no provider produces a valid tab, `MoldableInspectorCore` synthesizes a fallback `summary` key-value tab (`priority = 100`).
5. Exceptions thrown by an individual provider are isolated so other providers still contribute normally.

### `InspectContext.data()` Contract

All three exported SPI records live in `org.equimacs.eclipse.bridge.api` (UI-free — no SWT dependency required):
- `IMoldableViewProvider`: `id()`, `supports(InspectContext)`, `provideViews(InspectContext)`
- `InspectContext(String targetKind, String title, String typeName, String summary, Map<String, Object> data)`
- `MoldableTab(String id, String label, int priority, String specJson)`

Common `context.data()` entries populated for providers:
- **`jdi-frame`**: `frameId` (`Long`), `className`, `method`, `file`, `line` (`Integer`), `variables` (`List<Map>` with `name`, `type`, `value`).
- **`jdi-value`**: `frameId` (`Long`), `expression`, `valueString`, `isNull` (`Boolean`), optional `uniqueId` (`Long`), `isArray`, `arrayLength`, `elements` (`index`, `type`, `value`, `expression?`), `entries` (`key`, `value`, `valueType`), `fields` (`name`, `type`, `value`, `expression?`, `cycle?`, `fields?`).
- **`jdt-symbol`**: `target` (`Map` with `name`, `kind`, `fqn`, `declaringType`, `signature`, `file`, `line`, `offset`), optional `members`, `references`, `hierarchy`, `callers`, `callees`.

### Minimal External OSGi Provider Example

Create a separate OSGi bundle importing `org.equimacs.eclipse.bridge.api` (without SWT dependencies) and drop it into `<eclipse-home>/dropins/`:

```java
package com.example.inspector;

import java.util.List;
import org.equimacs.eclipse.bridge.api.IMoldableViewProvider;
import org.equimacs.eclipse.bridge.api.InspectContext;
import org.equimacs.eclipse.bridge.api.MoldableTab;

/**
 * Contributes a domain summary tab when inspecting an Order value at runtime.
 */
public final class OrderInspectorProvider implements IMoldableViewProvider {

    @Override
    public String id() {
        return "com.example.inspector.order";
    }

    @Override
    public boolean supports(InspectContext context) {
        return context != null
            && "jdi-value".equals(context.targetKind())
            && "com.example.Order".equals(context.typeName());
    }

    @Override
    public List<MoldableTab> provideViews(InspectContext context) {
        String spec = """
            {"kind":"markdown","title":"Order Audit","content":"### Order\\n%s"}
            """.formatted(context.summary().replace("\"", "\\\""));
        return List.of(new MoldableTab("order-audit", "Order Audit", 5, spec));
    }
}
```

Register it via OSGi Declarative Services (`OSGI-INF/order-inspector.xml`, referenced by `Service-Component` in `META-INF/MANIFEST.MF`):

```xml
<?xml version="1.0" encoding="UTF-8"?>
<scr:component xmlns:scr="http://www.osgi.org/xmlns/scr/v1.4.0"
               name="com.example.inspector.OrderInspectorProvider"
               immediate="true">
   <implementation class="com.example.inspector.OrderInspectorProvider"/>
   <service>
      <provide interface="org.equimacs.eclipse.bridge.api.IMoldableViewProvider"/>
   </service>
</scr:component>
```

---

## 6. Built-in Toolkit Views & `OpenFile` Range Highlight

| Command | Mode / Features | Live Refresh |
|---|---|---|
| `call-explorer <file>:<offset>` | `--direction callers\|callees\|both` (default `callers`), `--depth <1..5>` (default `3`), `--hide-jdk true\|false` / `--show-jdk` (default hide JDK), `--mode tree\|hybrid` (default `hybrid` split tree + native SWT focus graph), recursion `RECURSIVE` badge + dashed edges, focus/drill toolbar actions | On-demand via toolbar actions |
| `trace-view` | Vertical split: filterable chronological logpoint table (`--limit <1..5000>`, default `200`) + `Latest Snapshot` / `Timeline` tabs + `Clear Trace` toolbar button | Auto-refreshes on `LogpointHit` (`--live`, toggleable in toolbar) |
| `junit-view [runId]` | Horizontal split: class-grouped test tree (`PASS`/`FAIL`/`ERROR`/`IGNORED` badges) + `Summary` / `Failures` tabs with clickable stack frames resolved to workspace files + `Rerun Suite` button | Auto-refreshes on `TestRunFinished` (`--live`, toggleable in toolbar) |
| `open-file <file>[:<line>[-<endLine>]]` | Jumps editor to `<file>` and applies an auto-expiring `org.equimacs.eclipse.ui.tempHighlight` annotation (`4000ms` default, clamped `250..30000ms`) | — |

---

## 7. CLI Flag Ordering Tip

`CliArgs` treats the token immediately following any `-` / `--` flag as that option's value unless the next token itself starts with `-`. Therefore, place standalone negation flags (`--no-open`, `--no-live`, `--no-vars`, `--show-jdk`, `--failures`) **at the end** (or before another `-` flag), or use explicit boolean values (`--open false`, `--live false`, `--hide-jdk false`) before positional arguments:

```powershell
# Safe anywhere
eqm-cli inspect-frame 12345 --open false
eqm-cli junit-view --failures-only true --live false

# Safe when boolean flag is trailing (no following positional arg)
eqm-cli inspect-frame 12345 --no-open
eqm-cli call-explorer /myproject/src/Main.java:42 --direction both --show-jdk
```
