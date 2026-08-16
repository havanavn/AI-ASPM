import { useState } from "react";
import { MessageSquare, Send } from "lucide-react";
import { api } from "@/lib/api";
import { Prose } from "@/components/Prose";
import { RichText } from "@/components/RichTextLazy";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";

export interface CommentRow {
  id: string;
  author: string | null;
  createdAt: string | null;
  editCount: number;
  redacted: boolean;
  html: string | null;
}

/**
 * When a comment was posted, to the second, in the reader's own timezone.
 *
 * The server sends the instant with its offset, so the browser can do the conversion and the page
 * never asserts a wall-clock time in a zone it does not know. The UTC form stays in the tooltip,
 * because two people in different offices comparing a thread need one form they both agree on.
 */
function PostedAt({ at }: { at: string | null }) {
  if (!at) return null;
  const when = new Date(at);
  if (Number.isNaN(when.getTime())) return <span className="font-mono">{at}</span>;
  return (
    <time dateTime={at} title={`${at} (UTC)`} className="font-mono">
      {when.toLocaleString(undefined, {
        year: "numeric", month: "2-digit", day: "2-digit",
        hour: "2-digit", minute: "2-digit", second: "2-digit", hour12: false,
      })}
    </time>
  );
}

export function Comments({ comments, postTo, uploadTo, finding = null, onPosted }: {
  comments: CommentRow[];
  postTo: string;
  uploadTo: string;
  finding?: string | null;
  onPosted: (next: CommentRow[]) => void;
}) {
  const [draft, setDraft] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function post() {
    setBusy(true);
    setError(null);
    try {
      const result = await api.post<{ comments: CommentRow[] }>(postTo, { body: draft });
      onPosted(result.comments);
      setDraft("");
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          <MessageSquare className="size-4" /> Comments
        </CardTitle>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        {comments.length === 0 && (
          <p className="text-sm text-muted-foreground">No comment yet.</p>
        )}
        {comments.map((comment) => (
          <div key={comment.id} className="rounded-md border bg-muted/30 p-3">
            <div className="mb-1.5 flex flex-wrap items-baseline gap-2 text-xs text-muted-foreground">
              <span className="font-medium text-foreground">{comment.author ?? "unknown"}</span>
              <PostedAt at={comment.createdAt} />
              {comment.editCount > 0 && <span>edited ×{comment.editCount}</span>}
            </div>
            {comment.redacted ? (
              // The comment is not deleted, and saying so is the point. The record of what happened
              // is inviolable; what was erased is the payload, and the entry stays to prove it.
              <p className="text-sm italic text-muted-foreground">
                This comment was redacted. The entry remains in the record.
              </p>
            ) : (
              <Prose html={comment.html} />
            )}
          </div>
        ))}

        <div className="flex flex-col gap-2 border-t pt-4">
          <RichText value={draft} onChange={setDraft} uploadTo={uploadTo} finding={finding}
                    minHeight="7rem" />
          {error && <p className="text-xs text-destructive">{error}</p>}
          <div className="flex justify-end">
            <Button size="sm" disabled={busy || draft.trim() === ""} onClick={post}>
              <Send /> {busy ? "Posting…" : "Post comment"}
            </Button>
          </div>
        </div>
      </CardContent>
    </Card>
  );
}
