import { useState } from "react";
import { MessageSquareText, Sparkles } from "lucide-react";
import { Link } from "react-router-dom";
import { api } from "@/lib/api";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";

/**
 * A question about the posture, answered from facts the caller may already see, every claim cited.
 *
 * The facts are shown beside the answer, because a citation is only useful if the reader can follow it.
 * The answer is labelled generated (PRD-AIC-036); nothing here writes anything.
 */

interface Fact { ref: string; text: string; link: string | null }
interface Reply { answer?: string; citations?: string[]; insufficient?: boolean; modelIdentity?: string; generated?: boolean; refused?: string; detail?: string; facts: Fact[] }

export function AskPosture({ scopeNodeId }: { scopeNodeId?: string }) {
  const [question, setQuestion] = useState("");
  const [reply, setReply] = useState<Reply | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function ask() {
    setBusy(true); setError(null);
    try { setReply(await api.post<Reply>("/api/ui/ai/ask", { question, scopeNodeId })); }
    catch (e) { setError(e instanceof Error ? e.message : String(e)); }
    finally { setBusy(false); }
  }

  return (
    <Card>
      <CardHeader className="pb-2">
        <CardTitle className="flex items-center gap-2 text-base"><MessageSquareText className="size-4 text-primary" /> Ask about the posture</CardTitle>
        <CardDescription>
          Answered from the figures you can already see, each claim cited to a fact listed below the answer. A model writes the
          sentences; every number in them comes from a query, and an answer that cannot be checked is refused rather than shown.
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-3">
        <div className="flex flex-wrap gap-2">
          <Input className="max-w-xl" value={question} placeholder="Which open findings should we look at first, and why?"
                 onChange={(e) => setQuestion(e.target.value)} onKeyDown={(e) => { if (e.key === "Enter" && question.trim().length > 2) void ask(); }} />
          <Button size="sm" disabled={busy || question.trim().length < 3} onClick={ask}><Sparkles className="size-3" /> {busy ? "Asking…" : "Ask"}</Button>
        </div>
        {error && <p className="text-sm text-destructive">{error}</p>}
        {reply && (
          <div className="flex flex-col gap-2 rounded-md border p-3 text-sm">
            {reply.answer ? (
              <>
                <div className="flex items-center gap-2"><Badge tone="info">generated</Badge>
                  <span className="text-xs text-muted-foreground">{reply.modelIdentity}{reply.insufficient ? " · the model says the facts are insufficient" : ""}</span></div>
                <p className="whitespace-pre-wrap">{reply.answer}</p>
              </>
            ) : (
              <div><Badge tone="warn">not answered</Badge> <span className="text-xs">{reply.refused}: {reply.detail}</span></div>
            )}
            <details className="text-xs text-muted-foreground">
              <summary className="cursor-pointer">The facts the answer may cite ({reply.facts.length})</summary>
              <ul className="mt-1 flex flex-col gap-0.5">
                {reply.facts.map((f) => (
                  <li key={f.ref} className={reply.citations?.includes(f.ref) ? "text-foreground" : ""}>
                    <span className="font-mono">{f.ref}</span> {f.text}{f.link && <> · <Link className="underline" to={f.link}>open</Link></>}
                  </li>
                ))}
              </ul>
            </details>
          </div>
        )}
      </CardContent>
    </Card>
  );
}
