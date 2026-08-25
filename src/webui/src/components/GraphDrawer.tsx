import { Component, Suspense, lazy, useEffect, useState, type ReactNode } from "react";
import { Maximize, Minimize, Network, RotateCcw } from "lucide-react";
import * as Dialog from "@radix-ui/react-dialog";
import { Button } from "@/components/ui/button";
import type { GraphNode } from "@/components/ForceGraph";

/**
 * The graph, loaded when somebody asks for it and not before.
 *
 * `d3-force` and the renderer are dead weight on the request board, the settings screen and every
 * other page that draws no graph — the argument `RichTextLazy` makes for the editor. Split out, the
 * graph arrives on the click that opens it: 25 kB, 10 kB over the wire.
 */
const ForceGraphImpl = lazy(() =>
  import("./ForceGraph").then((m) => ({ default: m.ForceGraph })),
);

/**
 * *** A CRASH INSIDE THE GRAPH USED TO TAKE THE WHOLE TAB, AND THAT IS WHAT THIS FIXES. ***
 *
 * There was no boundary between the graph and the application root, so an exception in the renderer
 * unmounted everything — including the dialog portal it was drawn in. What a reader saw was not an
 * error but a blank page, dark or light depending on their browser, with no way back except a
 * reload. Reported from use, which is the only place it showed up.
 *
 * A boundary here keeps the failure the size of the thing that failed: the dialog stays, the message
 * is on screen, and "Try again" remounts the graph without reloading the page. The message is shown
 * rather than swallowed because a graph that fails silently is one nobody reports.
 */
class GraphBoundary extends Component<{ children: ReactNode; onReset: () => void },
  { failure: Error | null }> {
  constructor(props: { children: ReactNode; onReset: () => void }) {
    super(props);
    this.state = { failure: null };
  }

  static getDerivedStateFromError(failure: Error) {
    return { failure };
  }

  componentDidCatch(failure: Error) {
    // The console is where a reader can copy it from, and where a colleague asking "what did it say"
    // can be answered. PRD-UIX-025 keeps internal detail out of a SERVED error; this is the client's
    // own stack in the client's own console, which is a different thing.
    console.error("estate graph failed", failure);
  }

  render() {
    if (this.state.failure) {
      return (
        <div className="grid h-full place-items-center rounded-lg border border-dashed p-8">
          <div className="max-w-lg text-center">
            <p className="text-sm font-medium text-destructive">The graph could not be drawn.</p>
            <p className="mt-1 text-xs text-muted-foreground">
              The rest of the page is unaffected — this is the graph failing, not the application.
            </p>
            <pre className="mt-3 max-h-32 overflow-auto rounded bg-muted px-2 py-1 text-left
                            text-[11px] whitespace-pre-wrap text-muted-foreground">
              {this.state.failure.message || String(this.state.failure)}
            </pre>
            <Button size="sm" variant="outline" className="mt-3"
                    onClick={() => { this.setState({ failure: null }); this.props.onReset(); }}>
              <RotateCcw className="size-3" /> Try again
            </Button>
          </div>
        </div>
      );
    }
    return this.props.children;
  }
}

/**
 * A "View graph" button that opens over the dashboard the reader is already on.
 *
 * <h2>A drawer rather than a page, deliberately</h2>
 *
 * A reader looking at a filtered application list and asking "what is this connected to" has not
 * finished with the list. A route would take their filters, their scroll position and their place in
 * it — the hand-off this interface was built to remove. It also means no new entry in
 * `WebUi.ROUTES` and no class-G shell operation: the graph is one API read, not a screen.
 *
 * <h2>Large by default, and larger on request</h2>
 *
 * A graph in a small box is a graph nobody can read: the layout has nowhere to spread, so the nodes
 * pile up and the labels collide. The dialog therefore takes most of the viewport, and the maximise
 * control takes all of it. The canvas measures its container rather than scaling a fixed drawing, so
 * a bigger window shows MORE graph rather than the same graph enlarged.
 */
export function GraphDrawer({ rootId, label, onOpenRecord, disabled, compact }: {
  /** The asset or organization node the graph opens on. */
  rootId: string | null;
  /** What the button says it will show, e.g. the application's name. */
  label?: string;
  /** Where "open record" goes, per dashboard. Omit and the graph is read-only. */
  onOpenRecord?: (node: GraphNode) => void;
  disabled?: boolean;
  /**
   * Icon only, for a table row.
   *
   * An inventory table is dense by design (ADR-006), and a trailing action column after a variable
   * number of declared-field columns puts the control somewhere the eye has to hunt for. Beside the
   * name, where the reader already is, costs no column — and the accessible name still says what it
   * does, so nothing is carried by the glyph alone.
   */
  compact?: boolean;
}) {
  const [open, setOpen] = useState(false);
  const [full, setFull] = useState(false);
  const [attempt, setAttempt] = useState(0);

  // Escape collapses a maximised graph before it closes the dialog, which is what a reader who
  // pressed maximise expects the same key to undo.
  useEffect(() => {
    if (!open || !full) return;
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Escape") { event.stopPropagation(); setFull(false); }
    };
    window.addEventListener("keydown", onKey, true);
    return () => window.removeEventListener("keydown", onKey, true);
  }, [open, full]);

  return (
    <Dialog.Root open={open} onOpenChange={(next) => { setOpen(next); if (!next) setFull(false); }}>
      <Dialog.Trigger asChild>
        {compact ? (
          <Button variant="ghost" size="sm" disabled={disabled || !rootId}
                  className="size-6 p-0 text-muted-foreground hover:text-foreground"
                  aria-label={label ? `View the graph for ${label}` : "View graph"}
                  title={label ? `Graph: ${label}` : "View graph"}>
            <Network className="size-3.5" />
          </Button>
        ) : (
          <Button variant="outline" size="sm" disabled={disabled || !rootId}
                  title={rootId ? undefined : "Select a record to see its graph"}>
            <Network className="size-3" /> View graph
          </Button>
        )}
      </Dialog.Trigger>
      <Dialog.Portal>
        <Dialog.Overlay className="fixed inset-0 z-50 bg-black/50 backdrop-blur-[1px]" />
        <Dialog.Content
          className={full
            ? "fixed inset-0 z-50 flex flex-col gap-3 border-0 bg-background p-4"
            : "fixed left-1/2 top-1/2 z-50 flex h-[88vh] w-[96vw] max-w-[1700px] -translate-x-1/2"
              + " -translate-y-1/2 flex-col gap-3 rounded-lg border bg-background p-4 shadow-xl"}>
          <div className="flex items-start justify-between gap-4">
            <div className="min-w-0">
              <Dialog.Title className="truncate text-sm font-semibold tracking-tight">
                {label ? `${label} — estate graph` : "Estate graph"}
              </Dialog.Title>
              <Dialog.Description className="text-xs text-muted-foreground">
                One node and what it is directly connected to. A node with a
                <span className="px-1 font-medium">+</span> has more behind it. Hover or focus a node
                to dim everything it does not touch; drag to move one, scroll to zoom.
              </Dialog.Description>
            </div>
            <span className="flex shrink-0 items-center gap-1">
              <Button variant="ghost" size="sm" onClick={() => setFull((f) => !f)}
                      aria-label={full ? "Restore the window" : "Maximise the window"}>
                {full ? <><Minimize className="size-3" /> Restore</>
                      : <><Maximize className="size-3" /> Maximise</>}
              </Button>
              <Dialog.Close asChild>
                <Button variant="ghost" size="sm">Close</Button>
              </Dialog.Close>
            </span>
          </div>
          {/* Mounted only while open, so closing stops the simulation rather than leaving one
              running behind a dialog nobody is looking at. `attempt` remounts it after a failure. */}
          {open && rootId && (
            <div className="min-h-0 flex-1">
              <GraphBoundary key={attempt} onReset={() => setAttempt((a) => a + 1)}>
                <Suspense fallback={
                  <div className="grid h-full place-items-center rounded-lg border border-dashed
                                  text-xs text-muted-foreground">
                    Loading the graph…
                  </div>
                }>
                  <ForceGraphImpl rootId={rootId} onOpen={onOpenRecord} />
                </Suspense>
              </GraphBoundary>
            </div>
          )}
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}
