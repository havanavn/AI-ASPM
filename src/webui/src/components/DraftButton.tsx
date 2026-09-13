import { useState } from "react";
import { Sparkles } from "lucide-react";
import { api } from "@/lib/api";
import { Button } from "@/components/ui/button";

/**
 * "Draft with AI": from the notes already typed, a draft the person edits. Attributed as generated until
 * they change or save it (PRD-AIC-019); nothing is committed by this button.
 */
export function DraftButton({ kind, title, notes, onDraft }: {
  kind: "REQUEST_DESCRIPTION" | "FINDING_WRITEUP" | "COMMENT"; title?: string; notes: string; onDraft: (text: string, attribution: string) => void;
}) {
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string | null>(null);

  async function draft() {
    setBusy(true); setMessage(null);
    try {
      const r = await api.post<{ draft?: string; modelIdentity?: string; refused?: string; detail?: string }>("/api/ui/ai/draft", { kind, title, notes });
      if (r.draft) onDraft(r.draft, `Drafted by ${r.modelIdentity} from your notes — generated content until you edit or accept it.`);
      else setMessage(`${r.refused}: ${r.detail}`);
    } catch (e) {
      setMessage(e instanceof Error ? e.message : String(e));
    } finally { setBusy(false); }
  }

  return (
    <span className="inline-flex items-center gap-2">
      <Button type="button" size="sm" variant="secondary" disabled={busy || notes.trim().length < 5} onClick={draft} title="Turn the notes typed so far into a draft">
        <Sparkles className="size-3" /> {busy ? "Drafting…" : "Draft with AI"}
      </Button>
      {message && <span className="text-xs text-destructive">{message}</span>}
    </span>
  );
}
