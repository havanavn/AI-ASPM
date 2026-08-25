import { Suspense, lazy, useState } from "react";
import { Network } from "lucide-react";
import * as Dialog from "@radix-ui/react-dialog";
import { Button } from "@/components/ui/button";
import type { GraphNode } from "@/components/ForceGraph";

/**
 * The graph, loaded when somebody asks for it and not before.
 *
 * `d3-force` and this renderer are dead weight on the request board, the settings screen and every
 * other page that does not draw a graph — the same argument `RichTextLazy` makes for the editor. Split
 * out, the graph arrives on the click that opens it.
 */
const ForceGraphImpl = lazy(() =>
  import("./ForceGraph").then((m) => ({ default: m.ForceGraph })),
);

/**
 * A "View graph" button that opens over the dashboard the reader is already on.
 *
 * <h2>A drawer rather than a page, deliberately</h2>
 *
 * A reader looking at a filtered application list and asking "what is this connected to" has not
 * finished with the list. A route would take their filters, their scroll position and their place in
 * it — the same hand-off this interface was built to remove. It also means no new entry in
 * `WebUi.ROUTES` and no class-G shell operation: the graph is one API read, not a screen.
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
   * name, where the reader already is, costs no column at all — and the accessible name still says
   * what it does, so nothing is carried by the glyph alone.
   */
  compact?: boolean;
}) {
  const [open, setOpen] = useState(false);

  return (
    <Dialog.Root open={open} onOpenChange={setOpen}>
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
        <Dialog.Overlay className="fixed inset-0 z-50 bg-black/40" />
        <Dialog.Content
          className="fixed left-1/2 top-1/2 z-50 w-[min(1000px,94vw)] -translate-x-1/2
                     -translate-y-1/2 rounded-lg border bg-background p-4 shadow-lg">
          <div className="flex items-start justify-between gap-4 pb-3">
            <div>
              <Dialog.Title className="text-sm font-semibold tracking-tight">
                {label ? `${label} — estate graph` : "Estate graph"}
              </Dialog.Title>
              <Dialog.Description className="text-xs text-muted-foreground">
                One node and what it is directly connected to. A node with a
                <span className="px-1 font-medium">+</span> has more behind it — open what you need
                rather than loading the estate.
              </Dialog.Description>
            </div>
            <Dialog.Close asChild>
              <Button variant="ghost" size="sm">Close</Button>
            </Dialog.Close>
          </div>
          {/* Mounted only while open, so closing it stops the simulation rather than leaving one
              running behind a dialog nobody is looking at. */}
          {open && rootId && (
            <Suspense fallback={
              <div className="grid h-[520px] place-items-center rounded-md border border-dashed
                              text-xs text-muted-foreground">
                Loading the graph…
              </div>
            }>
              <ForceGraphImpl rootId={rootId} onOpen={onOpenRecord} />
            </Suspense>
          )}
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}
