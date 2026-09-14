import { useCallback, useEffect, useRef, useState } from "react";
import { Link } from "react-router-dom";
import {
  ChevronLeft, ExternalLink, History, Loader2, MessageSquarePlus, Send, ShieldAlert, Trash2, X,
} from "lucide-react";
import { api } from "@/lib/api";
import { cn } from "@/lib/utils";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";

/**
 * The copilot: a conversation about the posture, on every page but the sign-in one.
 *
 * <h2>Why the facts are on the screen and not behind a link</h2>
 *
 * Every answer arrives with the figures it was allowed to use, each numbered, and the citations in the
 * prose are the numbers. A reader who wants to check a sentence expands one row; a reader who does not
 * is not made to scroll past them. An answer whose claims cannot be followed is the failure this whole
 * surface is designed against, and hiding the facts one navigation away is how that failure gets built
 * by accident.
 *
 * <h2>Why "withheld" is printed in the answer</h2>
 *
 * A question can reach further than the asker's permissions do. The server names the parts it did not
 * look at and this prints them, because the alternative — a narrower answer that reads like a complete
 * one — is worse than saying "I could not see the roster". `PRD-AIC-048`.
 *
 * <h2>Why the panel is not modal</h2>
 *
 * Half the screen, no backdrop: the page behind it stays readable, because the question is usually
 * about what is on that page. A modal would make the reader choose between the figure and the question
 * about the figure.
 */

interface Fact { ref: string; text: string; link: string | null }
interface Msg {
  id: string; role: "USER" | "ASSISTANT"; text: string; citations: string[]; facts: Fact[];
  topics: string[]; withheld: string[]; modelIdentity: string | null; createdAt: string;
  refused: string | null; generated: boolean;
}
interface Thread {
  id: string; title: string; updatedAt: string; messages: number;
  focusAssetId: string | null; focusAssetName: string | null;
}
interface Opening {
  conversations: Thread[];
  starters: { text: string; pack: string }[];
  packs: { code: string; label: string; permission: string; readable: boolean }[];
  individualWorkload: boolean;
}

const THREAD_KEY = "copilot.thread";

export function Copilot({ permitted }: { permitted: boolean }) {
  const [open, setOpen] = useState(false);
  const [opening, setOpening] = useState<Opening | null>(null);
  const [thread, setThread] = useState<string | null>(() => {
    try { return sessionStorage.getItem(THREAD_KEY); } catch { return null; }
  });
  const [messages, setMessages] = useState<Msg[]>([]);
  const [followUps, setFollowUps] = useState<string[]>([]);
  const [draft, setDraft] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [showHistory, setShowHistory] = useState(false);
  const [openFacts, setOpenFacts] = useState<Set<string>>(new Set());
  const bottom = useRef<HTMLDivElement>(null);
  const input = useRef<HTMLTextAreaElement>(null);

  const load = useCallback(() => {
    api.get<Opening>("/api/ui/ai/copilot")
      .then(setOpening)
      .catch(() => setOpening(null));
  }, []);

  useEffect(() => { if (open && !opening) load(); }, [open, opening, load]);

  // Restores the thread the person was in, including after a full page reload.
  useEffect(() => {
    if (!open || !thread || messages.length > 0) return;
    api.get<{ messages: Msg[] }>(`/api/ui/ai/copilot/${thread}`)
      .then((d) => setMessages(d.messages))
      .catch(() => { setThread(null); try { sessionStorage.removeItem(THREAD_KEY); } catch { /* ignore */ } });
  }, [open, thread, messages.length]);

  useEffect(() => { bottom.current?.scrollIntoView({ behavior: "smooth" }); }, [messages, busy]);

  // Escape closes; the launcher keeps the focus ring so the keyboard can get back in.
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => { if (e.key === "Escape") setOpen(false); };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [open]);

  async function send(question: string) {
    const asked = question.trim();
    if (asked.length < 3 || busy) return;
    setBusy(true);
    setError(null);
    setDraft("");
    setFollowUps([]);
    // Shown immediately, with the identifier the server will give it replaced on the way back. A
    // question that vanishes for two seconds reads as a question that was not received.
    setMessages((m) => [...m, {
      id: `local-${Date.now()}`, role: "USER", text: asked, citations: [], facts: [], topics: [],
      withheld: [], modelIdentity: null, createdAt: "", refused: null, generated: false,
    }]);
    try {
      const r = await api.post<{ conversation: Thread; answer: Msg; followUps: string[] }>(
        "/api/ui/ai/copilot", { question: asked, conversationId: thread });
      setThread(r.conversation.id);
      try { sessionStorage.setItem(THREAD_KEY, r.conversation.id); } catch { /* per-viewer convenience */ }
      setMessages((m) => [...m, r.answer]);
      setFollowUps(r.followUps ?? []);
      load();
    } catch (e) {
      setError(e instanceof Error ? e.message : "The question could not be answered.");
    } finally {
      setBusy(false);
      input.current?.focus();
    }
  }

  function begin() {
    setThread(null);
    setMessages([]);
    setFollowUps([]);
    setShowHistory(false);
    try { sessionStorage.removeItem(THREAD_KEY); } catch { /* ignore */ }
    input.current?.focus();
  }

  async function openThread(id: string) {
    setShowHistory(false);
    setThread(id);
    try { sessionStorage.setItem(THREAD_KEY, id); } catch { /* ignore */ }
    const d = await api.get<{ messages: Msg[] }>(`/api/ui/ai/copilot/${id}`);
    setMessages(d.messages);
    setFollowUps([]);
  }

  async function drop(id: string) {
    await api.post(`/api/ui/ai/copilot/${id}/clear`, {});
    if (id === thread) begin();
    load();
  }

  // Not rendered at all without `aic.copilot.use` (V082). Not a security control — the dispatcher
  // answers 404 to the operations and product principle 4 says a filtered control is never the
  // authorization — but a launcher that opens onto a permission error is a feature that looks broken
  // to the one population the tenant decided not to give it to.
  if (!permitted) {
    return null;
  }

  return (
    <>
      {/* The launcher. Bottom right of every page the shell wraps, which is every page behind
          sign-in. Labelled for the keyboard and for a reader who cannot see the mark. */}
      <button type="button" onClick={() => setOpen((o) => !o)}
              aria-label={open ? "Close the AI copilot" : "Ask the AI copilot"}
              aria-expanded={open}
              className={cn("fixed bottom-5 right-5 z-40 flex size-14 items-center justify-center rounded-full",
                "bg-card shadow-lg ring-1 ring-border transition-transform hover:scale-105",
                "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary",
                open && "scale-95")}>
        {open
          ? <X className="size-6 text-muted-foreground" />
          : <img src="/brand/copilot.png" alt="" width={40} height={40} className="size-10" />}
      </button>

      {open && (
        <aside role="dialog" aria-label="AI copilot"
               className="fixed inset-y-0 right-0 z-40 flex w-full flex-col border-l bg-background shadow-2xl sm:w-1/2">
          <header className="flex h-14 shrink-0 items-center gap-2 border-b px-4">
            <img src="/brand/copilot.png" alt="" width={24} height={24} className="size-6" />
            <div className="min-w-0 flex-1">
              <div className="truncate text-sm font-semibold tracking-tight">AI copilot</div>
              <div className="truncate text-[11px] text-muted-foreground">
                Answers from what you are permitted to see, with the figures behind every claim.
              </div>
            </div>
            <Button variant="ghost" size="icon" title="Earlier conversations"
                    aria-label="Earlier conversations" onClick={() => setShowHistory((h) => !h)}>
              <History className="size-4" />
            </Button>
            <Button variant="ghost" size="icon" title="Start a new conversation"
                    aria-label="Start a new conversation" onClick={begin}>
              <MessageSquarePlus className="size-4" />
            </Button>
            <Button variant="ghost" size="icon" aria-label="Close" onClick={() => setOpen(false)}>
              <X className="size-4" />
            </Button>
          </header>

          {showHistory ? (
            <div className="flex-1 overflow-y-auto p-4">
              <button type="button" className="mb-3 flex items-center gap-1 text-xs text-primary hover:underline"
                      onClick={() => setShowHistory(false)}>
                <ChevronLeft className="size-3.5" /> Back to the conversation
              </button>
              {(opening?.conversations ?? []).length === 0 && (
                <p className="text-xs text-muted-foreground">Nothing yet. Ask something and it will be here.</p>
              )}
              <ul className="flex flex-col gap-1">
                {(opening?.conversations ?? []).map((t) => (
                  <li key={t.id} className="flex items-start gap-2 rounded-md border p-2">
                    <button type="button" className="min-w-0 flex-1 text-left" onClick={() => void openThread(t.id)}>
                      <div className="truncate text-xs font-medium">{t.title}</div>
                      <div className="text-[10px] text-muted-foreground">
                        {t.updatedAt} · {t.messages} messages
                        {t.focusAssetName && <> · about {t.focusAssetName}</>}
                      </div>
                    </button>
                    <button type="button" aria-label={`Clear ${t.title}`} title="Clear"
                            className="text-muted-foreground hover:text-destructive"
                            onClick={() => void drop(t.id)}>
                      <Trash2 className="size-3.5" />
                    </button>
                  </li>
                ))}
              </ul>
            </div>
          ) : (
            <div className="flex-1 overflow-y-auto px-4 py-3">
              {messages.length === 0 && <Opener opening={opening} onPick={(q) => void send(q)} />}
              <div className="flex flex-col gap-3">
                {messages.map((m) => (
                  <Bubble key={m.id} message={m}
                          expanded={openFacts.has(m.id)}
                          onToggleFacts={() => setOpenFacts((s) => {
                            const next = new Set(s);
                            if (next.has(m.id)) next.delete(m.id); else next.add(m.id);
                            return next;
                          })} />
                ))}
                {busy && (
                  <div className="flex items-center gap-2 text-xs text-muted-foreground">
                    <Loader2 className="size-3.5 animate-spin" /> Retrieving the figures and composing an answer…
                  </div>
                )}
                {error && <p className="text-xs text-destructive">{error}</p>}
                <div ref={bottom} />
              </div>
              {followUps.length > 0 && !busy && (
                <div className="mt-3 flex flex-wrap gap-1.5">
                  {followUps.map((f) => (
                    <button key={f} type="button" onClick={() => void send(f)}
                            className="rounded-full border px-2.5 py-1 text-[11px] text-muted-foreground hover:border-primary hover:text-foreground">
                      {f}
                    </button>
                  ))}
                </div>
              )}
            </div>
          )}

          <footer className="shrink-0 border-t p-3">
            <div className="flex items-end gap-2">
              <textarea ref={input} rows={2} value={draft} disabled={busy}
                        placeholder="Ask about risk, coverage, commitments, workload… in English or Vietnamese"
                        onChange={(e) => setDraft(e.target.value)}
                        onKeyDown={(e) => {
                          if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); void send(draft); }
                        }}
                        className="min-h-16 flex-1 resize-none rounded-md border bg-background px-3 py-2 text-sm
                                   outline-none focus-visible:ring-2 focus-visible:ring-primary" />
              <Button size="icon" disabled={busy || draft.trim().length < 3} aria-label="Send"
                      onClick={() => void send(draft)}>
                {busy ? <Loader2 className="size-4 animate-spin" /> : <Send className="size-4" />}
              </Button>
            </div>
            <p className="mt-1.5 text-[10px] text-muted-foreground">
              Every answer is composed from figures queried within your own scope; nothing here changes a record.
              Enter sends, Shift+Enter starts a line.
            </p>
          </footer>
        </aside>
      )}
    </>
  );
}

/** The empty state: what it can look at, what it cannot, and something to press. */
function Opener({ opening, onPick }: { opening: Opening | null; onPick: (q: string) => void }) {
  if (!opening) return <p className="text-xs text-muted-foreground">Loading…</p>;
  const unreadable = opening.packs.filter((p) => !p.readable);
  return (
    <div className="mb-4 flex flex-col gap-3">
      <p className="text-sm text-muted-foreground">
        Ask about the security posture of the estate you can reach — a product, an application, the
        commitments, the plan, the workload. Every figure comes from a query, every claim is cited, and
        anything your permissions do not reach is named rather than quietly left out.
      </p>
      <div className="flex flex-col gap-1.5">
        {opening.starters.map((s) => (
          <button key={s.text} type="button" onClick={() => onPick(s.text)}
                  className="rounded-md border px-3 py-2 text-left text-xs hover:border-primary hover:bg-muted/50">
            {s.text}
          </button>
        ))}
      </div>
      {unreadable.length > 0 && (
        <p className="text-[11px] text-muted-foreground">
          <ShieldAlert className="mr-1 inline size-3" />
          Outside your permissions, so it will say so rather than answer:{" "}
          {unreadable.map((p) => p.label.toLowerCase()).join("; ")}.
        </p>
      )}
    </div>
  );
}

/** One turn. The question as typed; the answer with its provenance, citations and facts. */
function Bubble({ message, expanded, onToggleFacts }: {
  message: Msg; expanded: boolean; onToggleFacts: () => void;
}) {
  if (message.role === "USER") {
    return (
      <div className="self-end rounded-lg rounded-br-sm bg-primary/10 px-3 py-2 text-sm max-w-[85%] whitespace-pre-wrap">
        {message.text}
      </div>
    );
  }
  const cited = new Set(message.citations);
  return (
    <div className="flex flex-col gap-1.5 rounded-lg rounded-bl-sm border bg-card px-3 py-2 text-sm">
      <div className="flex flex-wrap items-center gap-1.5">
        {/* PRD-AIC-036 in both directions: a model wrote it, or the platform composed it from the
            figures. The second is not generated content and does not wear the label. */}
        {message.generated
          ? <Badge tone="info">generated</Badge>
          : <Badge tone="neutral">composed from figures</Badge>}
        <span className="text-[10px] text-muted-foreground">
          {message.modelIdentity ?? "no model was used"}
          {message.refused && ` · ${message.refused}`}
        </span>
      </div>
      <p className="whitespace-pre-wrap leading-relaxed">{render(message.text)}</p>
      {message.withheld.length > 0 && (
        <div className="rounded-md border border-tone-warn/40 bg-tone-warn/5 p-2 text-[11px]">
          <span className="font-medium">Not looked at, for want of permission:</span>{" "}
          {message.withheld.join("; ")}
        </div>
      )}
      {message.facts.length > 0 && (
        <div className="text-[11px]">
          <button type="button" onClick={onToggleFacts} className="text-primary hover:underline">
            {expanded ? "Hide" : "Show"} the {message.facts.length} figures this answer could use
            {message.citations.length > 0 && ` · ${message.citations.length} cited`}
          </button>
          {expanded && (
            <ul className="mt-1 flex flex-col gap-1">
              {message.facts.map((f) => (
                <li key={f.ref}
                    className={cn("flex gap-1.5 rounded px-1.5 py-1",
                      cited.has(f.ref) ? "bg-primary/5 text-foreground" : "text-muted-foreground")}>
                  <span className="shrink-0 font-mono">{f.ref}</span>
                  <span className="min-w-0 flex-1">{f.text}</span>
                  {f.link && (
                    <Link to={f.link} className="shrink-0 text-primary hover:underline" title="Open the list behind it">
                      <ExternalLink className="size-3" />
                    </Link>
                  )}
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
    </div>
  );
}

/** Citation markers become small monospace chips, so prose stays prose and a claim keeps its receipt. */
function render(text: string) {
  const parts = text.split(/(\[F\d+\])/g);
  return parts.map((part, i) =>
    /^\[F\d+\]$/.test(part)
      ? <span key={i} className="mx-0.5 rounded bg-muted px-1 font-mono text-[10px] text-muted-foreground">{part.slice(1, -1)}</span>
      : <span key={i}>{part}</span>);
}
