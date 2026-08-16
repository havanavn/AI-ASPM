/**
 * Server-rendered prose.
 *
 * `dangerouslySetInnerHTML` is used deliberately and is safe here for one reason: the HTML came from
 * {@code Markdown.java}, which escapes the source before it introduces any markup and permits only a
 * closed set of elements — images restricted to stored attachments, links restricted to this origin
 * or plain http/https with rel="noopener noreferrer nofollow". The alternative, rendering the
 * Markdown source in the browser, would be a SECOND renderer whose disagreements with the first are
 * exactly the cross-site scripting this product exists to find.
 *
 * The rule that follows: nothing else in this interface may use this component with a string that did
 * not come from that renderer.
 */
export function Prose({ html, empty }: { html: string | null; empty?: string }) {
  if (!html || html.trim() === "") {
    return <p className="text-sm italic text-muted-foreground">{empty ?? "Nothing recorded."}</p>;
  }
  return <div className="prose max-w-none" dangerouslySetInnerHTML={{ __html: html }} />;
}
