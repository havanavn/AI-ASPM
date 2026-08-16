import { Suspense, lazy } from "react";

/**
 * CKEditor, loaded only when a page actually needs to edit prose.
 *
 * The editor is 1.2 MB of the bundle — larger than everything else combined. Loading it eagerly
 * would make the request board, the application inventory and the organization tree all wait for an
 * editor none of them contains. Split out, those pages ship about 200 kB and the editor arrives when
 * somebody opens a comment box or an edit form.
 */
const RichTextImpl = lazy(() =>
  import("./RichText").then((m) => ({ default: m.RichText })),
);

type Props = Parameters<typeof import("./RichText").RichText>[0];

export function RichText(props: Props) {
  return (
    <Suspense
      fallback={
        <div className="grid place-items-center rounded-md border border-dashed text-xs text-muted-foreground"
             style={{ minBlockSize: props.minHeight ?? "10rem" }}>
          Loading the editor…
        </div>
      }
    >
      <RichTextImpl {...props} />
    </Suspense>
  );
}
