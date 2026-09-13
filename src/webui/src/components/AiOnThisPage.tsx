import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { Bot } from "lucide-react";
import { api } from "@/lib/api";
import { Badge } from "@/components/ui/badge";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";

/**
 * What the AI does on this page, in one table the reader can check against the settings.
 *
 * A reader asked "what is the AI doing here and how" and the honest answer is a list: each
 * capability, what it reads (aggregate figures or record text), whether it is switched on, and how
 * many of its proposals are waiting for a person. Rendering the list from the same endpoint the
 * ledger uses means it cannot describe a capability that does not exist.
 */
interface Capability {
  code: string; suggestionKind: string; surface: string; dataCategory: string; enabled: boolean;
  pending: number; promoted: number; rejected: number;
}

const WHAT: Record<string, string> = {
  "coverage.caveat": "Says which organizations are too little measured for their figures to be read as posture. Precondition for every brief.",
  "narrative.draft": "Writes the executive brief per organization from queried figures; may not invent a number.",
  "exception.brief": "Summarises accepted risks approaching expiry for the approver who has to decide again.",
  "posture.answer": "Answers a typed question from facts you already see, each claim cited; refuses what it cannot ground.",
};

export function AiOnThisPage({ surface }: { surface: string }) {
  const [rows, setRows] = useState<Capability[] | null>(null);
  useEffect(() => {
    api.get<{ capabilities: Capability[] }>("/api/ui/suggestions?limit=1")
      .then((d) => setRows(d.capabilities.filter((c) => c.surface === surface)))
      .catch(() => setRows([]));
  }, [surface]);
  if (rows === null) return null;
  return (
    <Card>
      <CardHeader className="pb-2">
        <CardTitle className="flex items-center gap-2 text-base"><Bot className="size-4 text-primary" /> What the AI does on this page</CardTitle>
        <CardDescription>
          Every capability writes to a suggestion ledger and nothing else (ADR-005). A person accepts
          or dismisses each proposal; the record only changes on acceptance, and that acceptance is
          audited. Aggregate capabilities see counts and dates; record capabilities see finding text.
        </CardDescription>
      </CardHeader>
      <CardContent>
        {rows.length === 0 ? (
          <p className="text-xs text-muted-foreground">No capability is registered for this page.</p>
        ) : (
          <table className="w-full text-xs">
            <thead className="text-left text-[10px] uppercase tracking-wide text-muted-foreground">
              <tr><th className="py-1 pr-3">Capability</th><th className="py-1 pr-3">What it does</th><th className="py-1 pr-3">Reads</th><th className="py-1 pr-3">State</th><th className="py-1 pr-3 text-right">Awaiting a decision</th><th className="py-1 text-right">Accepted / dismissed</th></tr>
            </thead>
            <tbody>
              {rows.map((c) => (
                <tr key={c.code} className="border-t border-border/60">
                  <td className="py-1.5 pr-3 font-mono">{c.code}</td>
                  <td className="py-1.5 pr-3 text-muted-foreground">{WHAT[c.code] ?? c.suggestionKind.toLowerCase().replace(/_/g, " ")}</td>
                  <td className="py-1.5 pr-3"><Badge tone={c.dataCategory === "RECORD" ? "warn" : "neutral"}>{c.dataCategory.toLowerCase()}</Badge></td>
                  <td className="py-1.5 pr-3">{c.enabled ? <Badge tone="ok">on</Badge> : <Badge tone="unknown">off</Badge>}</td>
                  <td className="tabular py-1.5 pr-3 text-right">{c.pending > 0 ? <strong>{c.pending}</strong> : "—"}</td>
                  <td className="tabular py-1.5 text-right text-muted-foreground">{c.promoted} / {c.rejected}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        <p className="mt-2 text-[11px] text-muted-foreground">
          Providers, the operator-vouched private endpoints, budgets and the evaluation harness are under{" "}
          <Link className="underline" to="/settings">Settings</Link>; every invocation is recorded with its token cost.
        </p>
      </CardContent>
    </Card>
  );
}
