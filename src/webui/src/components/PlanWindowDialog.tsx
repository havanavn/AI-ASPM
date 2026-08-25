import { useMemo, useState } from "react";
import * as Dialog from "@radix-ui/react-dialog";
import { CalendarPlus, Loader2, Trash2, TriangleAlert } from "lucide-react";
import { api } from "@/lib/api";
import { cn } from "@/lib/utils";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { DateField } from "@/components/DateField";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
} from "@/components/ui/select";

/** A thing a window can be planned against. */
export interface PlanTarget {
  id: string;
  name: string;
  /** APPLICATION or PROJECT — shown so a reader can tell which granularity they picked. */
  typeCode: string;
  /** The review interval its criticality tier carries, where one is configured. */
  intervalMonths?: number | null;
  /** Windows already planned for it, so the dialog can say it is adding to a plan, not making one. */
  plannedWindows?: number;
}

/** One proposed window, before it is saved. */
interface Proposal {
  key: string;
  label: string;
  startsOn: string;
  endsOn: string;
  include: boolean;
}

/** The default length of a planned engagement, in days. */
const DEFAULT_DAYS = 14;

/** The offered frequencies. Twelve is monthly, which a regulated estate does use. */
const PER_YEAR = [1, 2, 3, 4, 6, 12] as const;

function iso(date: Date): string {
  return date.toISOString().slice(0, 10);
}

function addDays(isoDate: string, days: number): string {
  const date = new Date(`${isoDate}T00:00:00Z`);
  date.setUTCDate(date.getUTCDate() + days);
  return iso(date);
}

/**
 * How many reviews a year the tenant's own policy implies for these targets.
 *
 * <p>The tenant already states its cadence once, as `interval_months` per criticality tier, and
 * making somebody restate it as "times per year" in this dialog is the interface asking a question it
 * can already answer. Where the selected targets disagree — a CRITICAL and a LOW application picked
 * together — the MOST frequent wins, because under-planning a critical target is the worse error and
 * the planner can always uncheck rows.
 *
 * <p>Falls back to one where no target carries an interval. Not zero: somebody opened this dialog in
 * order to plan something.
 */
function impliedPerYear(targets: PlanTarget[]): number {
  const intervals = targets
    .map((t) => t.intervalMonths)
    .filter((m): m is number => typeof m === "number" && m > 0);
  if (intervals.length === 0) return 1;
  const shortest = Math.min(...intervals);
  const implied = Math.max(1, Math.round(12 / shortest));
  // Snapped to an offered value so the picker shows what was computed rather than a blank.
  return PER_YEAR.reduce((best, option) =>
    Math.abs(option - implied) < Math.abs(best - implied) ? option : best, PER_YEAR[0]);
}

/**
 * Evenly spaced windows across one year.
 *
 * <p>Spread rather than stacked: four reviews all falling in March is not quarterly, and the whole
 * reason this page exists is that a plan can be over-committed in one fortnight while the year as a
 * whole looks fine. The first window sits at the start of the first period, which puts a quarterly
 * plan in March, June, September and December rather than in January — one month of runway before the
 * first engagement, since a plan is usually made for a year that has not started.
 */
function propose(year: number, perYear: number, days: number): Proposal[] {
  const step = 12 / perYear;
  return Array.from({ length: perYear }, (_, index) => {
    const month = Math.round(index * step + Math.min(2, step - 1));
    const start = new Date(Date.UTC(year, Math.min(11, month), 1));
    const startsOn = iso(start);
    return {
      key: `w${index}`,
      label: perYear === 4 ? `Q${index + 1}`
        : perYear === 12 ? start.toLocaleString("en", { month: "short", timeZone: "UTC" })
        : `#${index + 1}`,
      startsOn,
      endsOn: addDays(startsOn, Math.max(0, days - 1)),
      include: true,
    };
  });
}

/**
 * Laying out planned assessment windows for one or many targets.
 *
 * <h2>Why this is not the intake form</h2>
 *
 * A request needs a scope descriptor, a title and a payload. A plan for next September has none of
 * them, and the previous "Schedule" button — which opened the intake form pre-filled — made the
 * periodic obligation unplannable for exactly that reason: the form insisted on information that does
 * not exist yet. A window carries what is known at planning time and nothing else
 * (`PRD-ASM-015`).
 *
 * <h2>Propose, then edit</h2>
 *
 * The stored plan is the windows, not a recurrence rule (`PRD-ASM-017`, and V070's header carries the
 * argument). A rule would be fewer keystrokes and would silently rewrite last quarter's plan the day
 * somebody edited it. So the dialog computes a proposal — from the tenant's own review interval — and
 * the planner adjusts it before saving. What is saved is what was agreed.
 *
 * <h2>The multiplication is stated before it happens</h2>
 *
 * Nine applications at four a year is thirty-six windows, and a planner who did not expect that
 * number should see it on the button rather than in the plan afterwards.
 */
export function PlanWindowDialog({ targets, onSaved, trigger, disabled }: {
  targets: PlanTarget[];
  onSaved: () => void;
  trigger: React.ReactNode;
  disabled?: boolean;
}) {
  const [open, setOpen] = useState(false);
  const thisYear = new Date().getUTCFullYear();
  const [year, setYear] = useState(thisYear);
  const [perYear, setPerYear] = useState(() => impliedPerYear(targets));
  const [days, setDays] = useState(DEFAULT_DAYS);
  const [proposals, setProposals] = useState<Proposal[]>(
    () => propose(thisYear, impliedPerYear(targets), DEFAULT_DAYS));
  const [note, setNote] = useState("");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const today = iso(new Date());
  const included = proposals.filter((p) => p.include);
  const total = included.length * targets.length;
  // A window that already ended cannot be planned; it can only be recorded, which is what a request
  // is for. Flagged rather than refused — a planner correcting last quarter's plan is legitimate.
  const past = included.filter((p) => p.endsOn < today).length;

  function reset(nextYear: number, nextPerYear: number, nextDays: number) {
    setYear(nextYear);
    setPerYear(nextPerYear);
    setDays(nextDays);
    setProposals(propose(nextYear, nextPerYear, nextDays));
  }

  function edit(key: string, patch: Partial<Proposal>) {
    setProposals((current) => current.map((p) => {
      if (p.key !== key) return p;
      const next = { ...p, ...patch };
      // Moving the start drags the end with it, keeping the length the planner chose. Moving the end
      // alone is how they change the length, so it is not corrected back.
      if (patch.startsOn && !patch.endsOn && next.endsOn < next.startsOn) {
        next.endsOn = addDays(next.startsOn, Math.max(0, days - 1));
      }
      return next;
    }));
  }

  async function save() {
    setSaving(true);
    setError(null);
    try {
      const windows = targets.flatMap((target) =>
        included.map((p) => ({
          targetAssetId: target.id,
          startsOn: p.startsOn,
          endsOn: p.endsOn,
          note: note.trim() || null,
        })));
      await api.post("/api/ui/assessment-plan/windows", { windows });
      setOpen(false);
      onSaved();
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : String(failure));
    } finally {
      setSaving(false);
    }
  }

  const years = useMemo(
    () => [thisYear, thisYear + 1, thisYear + 2], [thisYear]);
  const alreadyPlanned = targets.reduce((sum, t) => sum + (t.plannedWindows ?? 0), 0);

  return (
    <Dialog.Root open={open} onOpenChange={(next) => {
      setOpen(next);
      if (next) reset(year, impliedPerYear(targets), days);
      else setError(null);
    }}>
      <Dialog.Trigger asChild disabled={disabled || targets.length === 0}>
        {trigger}
      </Dialog.Trigger>
      <Dialog.Portal>
        <Dialog.Overlay className="fixed inset-0 z-50 bg-black/50" />
        <Dialog.Content className="fixed left-1/2 top-1/2 z-50 flex max-h-[88vh] w-[min(46rem,94vw)]
                                   -translate-x-1/2 -translate-y-1/2 flex-col gap-4 overflow-y-auto
                                   rounded-lg border bg-background p-5 shadow-xl">
          <div>
            <Dialog.Title className="text-sm font-semibold tracking-tight">
              Plan assessment windows
            </Dialog.Title>
            <Dialog.Description className="mt-1 text-xs text-muted-foreground">
              A window is an intention — a target and two dates. It is not an assessment request and
              is counted nowhere as work in progress. Raise the request from the window when the
              time comes.
            </Dialog.Description>
          </div>

          {/* Who this is for. Named rather than counted: "36 windows for 9 applications" with no
              list is a number nobody can check before committing to it. */}
          <div className="rounded-md border bg-muted/40 px-3 py-2">
            <div className="text-[11px] font-medium uppercase tracking-wide text-muted-foreground">
              {targets.length === 1 ? "Target" : `${targets.length} targets`}
            </div>
            <div className="mt-1 flex flex-wrap gap-1">
              {targets.slice(0, 12).map((t) => (
                <Badge key={t.id} tone={t.typeCode === "PROJECT" ? "neutral" : "ok"}>
                  {t.name}
                  {t.typeCode === "PROJECT" && <span className="pl-1 opacity-70">project</span>}
                </Badge>
              ))}
              {targets.length > 12 && (
                <span className="self-center text-[11px] text-muted-foreground">
                  and {targets.length - 12} more
                </span>
              )}
            </div>
            {alreadyPlanned > 0 && (
              <p className="mt-1.5 text-[11px] text-muted-foreground">
                {alreadyPlanned} window{alreadyPlanned === 1 ? "" : "s"} already planned for
                {targets.length === 1 ? " this target" : " these targets"} — these are added to the
                plan, not replacing it.
              </p>
            )}
          </div>

          {/* The three inputs that generate a proposal. */}
          <div className="flex flex-wrap items-end gap-3">
            <div className="flex flex-col gap-1">
              <Label htmlFor="plan-per-year">Times per year</Label>
              <Select value={String(perYear)}
                      onValueChange={(v) => reset(year, Number(v), days)}>
                <SelectTrigger id="plan-per-year" className="h-8 w-24 text-xs">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {PER_YEAR.map((n) => (
                    <SelectItem key={n} value={String(n)}>{n}×</SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
            <div className="flex flex-col gap-1">
              <Label htmlFor="plan-year">Year</Label>
              <Select value={String(year)} onValueChange={(v) => reset(Number(v), perYear, days)}>
                <SelectTrigger id="plan-year" className="h-8 w-24 text-xs">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {years.map((y) => <SelectItem key={y} value={String(y)}>{y}</SelectItem>)}
                </SelectContent>
              </Select>
            </div>
            <div className="flex flex-col gap-1">
              <Label htmlFor="plan-days">Days each</Label>
              <Input id="plan-days" type="number" min={1} max={365} value={days}
                     className="h-8 w-20 text-xs"
                     onChange={(e) => reset(year, perYear, Math.max(1, Number(e.target.value) || 1))} />
            </div>
            {/* Where the default came from, said out loud. A number that appeared on its own is one
                nobody trusts or corrects. */}
            <p className="flex-1 self-center text-[11px] text-muted-foreground">
              {targets.some((t) => t.intervalMonths)
                ? "Defaulted from the review interval your criticality tiers already carry."
                : "No review interval is configured for these targets, so this starts at once a year."}
            </p>
          </div>

          {/* The proposal, editable. */}
          <div className="overflow-hidden rounded-md border">
            <table className="w-full text-xs">
              <thead className="bg-muted/50 text-left text-[11px] uppercase tracking-wide
                                text-muted-foreground">
                <tr>
                  <th className="w-10 px-3 py-2" />
                  <th className="w-16 px-2 py-2">Window</th>
                  <th className="px-2 py-2">Starts</th>
                  <th className="px-2 py-2">Ends</th>
                  <th className="w-10 px-2 py-2" />
                </tr>
              </thead>
              <tbody>
                {proposals.map((p) => (
                  <tr key={p.key} className={cn("border-t", !p.include && "opacity-45")}>
                    <td className="px-3 py-1.5">
                      <Checkbox checked={p.include}
                                aria-label={`Include ${p.label}`}
                                onCheckedChange={(v) => edit(p.key, { include: v === true })} />
                    </td>
                    <td className="px-2 py-1.5 font-medium">{p.label}</td>
                    <td className="px-2 py-1.5">
                      <DateField value={p.startsOn} className="h-7 w-36 text-xs"
                                 onChange={(v) => v && edit(p.key, { startsOn: v })} />
                    </td>
                    <td className="px-2 py-1.5">
                      <DateField value={p.endsOn} min={p.startsOn} className="h-7 w-36 text-xs"
                                 onChange={(v) => v && edit(p.key, { endsOn: v })} />
                    </td>
                    <td className="px-2 py-1.5">
                      <Button variant="ghost" size="sm" className="size-6 p-0"
                              aria-label={`Remove ${p.label}`}
                              onClick={() => setProposals((c) => c.filter((x) => x.key !== p.key))}>
                        <Trash2 className="size-3" />
                      </Button>
                    </td>
                  </tr>
                ))}
                {proposals.length === 0 && (
                  <tr>
                    <td colSpan={5} className="px-3 py-4 text-center text-muted-foreground">
                      No window proposed. Change the frequency above to start again.
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>

          <div className="flex flex-col gap-1">
            <Label htmlFor="plan-note">Note (optional)</Label>
            <Input id="plan-note" value={note} className="h-8 text-xs"
                   placeholder="why the plan sits here — e.g. after the payments migration"
                   onChange={(e) => setNote(e.target.value)} />
          </div>

          {past > 0 && (
            <p className="flex items-start gap-1.5 text-[11px] text-tone-warn">
              <TriangleAlert className="mt-px size-3.5 shrink-0" />
              {past} of these window{past === 1 ? " has" : "s have"} already ended. Saving is allowed —
              correcting a past plan is legitimate — but work that already happened belongs in a
              request, not a plan.
            </p>
          )}
          {error && <p className="text-[11px] text-destructive">{error}</p>}

          <div className="flex items-center justify-between gap-3 border-t pt-3">
            <span className="text-[11px] text-muted-foreground">
              {targets.length > 1
                ? `${included.length} window${included.length === 1 ? "" : "s"} × ${targets.length} targets`
                : `${included.length} window${included.length === 1 ? "" : "s"}`}
            </span>
            <span className="flex items-center gap-2">
              <Dialog.Close asChild>
                <Button variant="ghost" size="sm">Cancel</Button>
              </Dialog.Close>
              <Button size="sm" disabled={total === 0 || saving} onClick={save}>
                {saving ? <Loader2 className="size-3 animate-spin" />
                        : <CalendarPlus className="size-3" />}
                Save {total} window{total === 1 ? "" : "s"}
              </Button>
            </span>
          </div>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}
