import { useEffect, useMemo, useState } from "react";
import { api } from "@/lib/api";
import { Prose } from "@/components/Prose";
import { Card, CardContent } from "@/components/ui/card";

interface Payload { html: string; locale: string }

/** One entry in the contents, derived from the rendered document rather than maintained beside it. */
interface Heading { id: string; text: string; level: number }

/**
 * The user guide.
 *
 * ## Why this page exists
 *
 * The guide was already written — five hundred lines in two languages — and served by the
 * server-rendered tier at `/guide`. **Nobody could reach it.** The sidebar entry is a client-side link
 * and this router had no `/guide` route, so clicking it matched the catch-all and drew an empty page:
 * 333 characters, all of them sidebar. The content was never missing; the door was.
 *
 * ## Why the HTML comes from the server
 *
 * `Prose` renders HTML produced by `Markdown.java`, which escapes the source before it introduces any
 * markup and permits only a closed set of elements. Parsing the Markdown here would be a second
 * renderer, and the disagreements between two renderers are exactly the cross-site scripting this
 * product exists to find in other people's software.
 *
 * ## The contents are derived
 *
 * Headings are read out of the rendered document after it is in the DOM, not listed in a second place
 * that goes stale the first time somebody edits the Markdown. A guide whose contents disagree with its
 * body teaches people not to use the contents.
 */
export function GuidePage() {
  const [data, setData] = useState<Payload | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [headings, setHeadings] = useState<Heading[]>([]);

  useEffect(() => {
    let live = true;
    api.get<Payload>("/api/ui/guide")
      .then((d) => live && setData(d))
      .catch((e) => live && setError(e.message));
    return () => { live = false; };
  }, []);

  // Read AFTER the document is in the DOM — read, never written.
  //
  // A first version SET the ids here, and it worked exactly once: storing the contents it had just
  // built re-rendered the component, React re-applied the same HTML, and every attribute added by hand
  // went with it. Twenty-seven links, zero targets, and a click that changed the address bar and moved
  // nothing. The ids come from `Markdown.java` now, so a re-render cannot lose them.
  useEffect(() => {
    if (!data?.html) {
      return;
    }
    const root = document.getElementById("guide-body");
    if (!root) {
      return;
    }
    const found: Heading[] = [];
    root.querySelectorAll("h3[id], h4[id], h5[id]").forEach((node) => {
      const text = (node.textContent ?? "").trim();
      if (text) {
        found.push({ id: node.id, text, level: Number(node.tagName.slice(1)) });
      }
    });
    setHeadings(found);
  }, [data]);

  const outline = useMemo(() => {
    if (headings.length === 0) {
      return [];
    }
    const top = Math.min(...headings.map((h) => h.level));
    return headings.filter((h) => h.level <= top + 1).map((h) => ({ ...h, depth: h.level - top }));
  }, [headings]);

  if (error) {
    return <Card><CardContent className="text-sm text-destructive">{error}</CardContent></Card>;
  }
  if (!data) {
    return <div className="text-sm text-muted-foreground">Loading…</div>;
  }

  return (
    <div className="flex flex-col gap-5">
      <div>
        <h1 className="text-lg font-semibold tracking-tight">User guide</h1>
        <p className="text-xs text-muted-foreground">
          What each screen is for, what the numbers mean, and why access is sometimes refused.
        </p>
      </div>

      <div className="flex flex-col gap-5 lg:flex-row lg:items-start">
        {outline.length > 0 && (
          // Sticky, because a guide is read by scrolling and a contents list that scrolls away with
          // the text is a contents list you have to scroll back up to use.
          <Card className="lg:sticky lg:top-4 lg:w-64 lg:shrink-0">
            <CardContent className="flex flex-col gap-1 py-3">
              <span className="pb-1 text-xs font-medium text-muted-foreground">On this page</span>
              {outline.map((h) => (
                <a key={h.id} href={`#${h.id}`}
                   style={{ paddingInlineStart: `${h.depth * 0.75}rem` }}
                   className="text-xs text-primary hover:underline">{h.text}</a>
              ))}
            </CardContent>
          </Card>
        )}
        <Card className="min-w-0 flex-1">
          <CardContent className="py-4">
            {data.html
              ? <div id="guide-body"><Prose html={data.html} /></div>
              : (
                // Said, not blank. An empty article region reads as "there is nothing to say", which
                // for a help page is a claim about the product rather than a document that failed to
                // load (PP-9: fail loudly, degrade explicitly).
                <p className="text-sm text-tone-warn">
                  The guide could not be loaded. This is a deployment problem, not an empty guide —
                  the document ships with the application.
                </p>
              )}
          </CardContent>
        </Card>
      </div>
    </div>
  );
}
