import { Info } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";

export interface TaxonomyOption { code: string; label: string; hint: string }

/**
 * One classification field, with the taxonomy's own explanation attached to it.
 *
 * <h2>Why the guidance is here and not in a manual</h2>
 *
 * Product principle 7: the largest user population has the narrowest permissions and the least
 * training. A picker offering `A06:2025 — Insecure Design` and `CWE-863 — Incorrect Authorization`
 * is usable by somebody already fluent in the taxonomy and by nobody else — and the failure mode is not
 * a blank field. It is a confidently wrong one, which looks exactly as authoritative as a right one to
 * every report built on top of it.
 *
 * <h2>Three places, one text</h2>
 *
 * The explanations come from the database, not from this file. The same rows are read by the
 * classification agent, so a tenant that sharpens a definition changes what an assessor reads AND what
 * the agent matches on, in one edit. Two copies would drift and the drift would be silent (PP-10).
 *
 * <h2>Explanation in three places, deliberately</h2>
 *
 * <ul>
 *   <li>The <b>ⓘ</b> beside the label opens the whole list, for somebody deciding between options.
 *   <li>Each row in the open dropdown carries its own line, for somebody scanning.
 *   <li>The chosen option's line stays under the field, so a reviewer reading the form afterwards sees
 *       what the choice meant without opening anything.
 * </ul>
 *
 * Somebody unsure enough to need help is not going to hunt for it, so it is on the path either way.
 */
export function TaxonomyPicker({ id, label, help, options, value, onChange, placeholder = "Not set" }: {
  id: string;
  label: string;
  /** One sentence on what the field is FOR, above the per-option list. */
  help: string;
  options: TaxonomyOption[];
  value: string;
  onChange: (code: string) => void;
  placeholder?: string;
}) {
  const chosen = options.find((o) => o.code === value);

  return (
    <div className="flex flex-col gap-1">
      <div className="flex items-center gap-1">
        <Label htmlFor={id}>{label}</Label>
        <Popover>
          <PopoverTrigger asChild>
            <Button type="button" size="sm" variant="ghost"
                    className="size-5 p-0 text-muted-foreground hover:text-foreground"
                    aria-label={`What each ${label} option means`}>
              <Info className="size-3.5" />
            </Button>
          </PopoverTrigger>
          {/* Scrolls inside itself. The CWE list is thirty-nine entries and a popover that grows past
              the viewport puts its last options out of reach. */}
          <PopoverContent align="start" className="max-h-96 w-[32rem] overflow-y-auto">
            <p className="mb-2 text-xs text-muted-foreground">{help}</p>
            <dl className="flex flex-col gap-2">
              {options.map((o) => (
                <div key={o.code}>
                  <dt className="text-xs font-medium">{o.label}</dt>
                  <dd className="text-[11px] text-muted-foreground">
                    {o.hint || <span className="italic">No explanation recorded.</span>}
                  </dd>
                </div>
              ))}
            </dl>
          </PopoverContent>
        </Popover>
      </div>

      <Select value={value} onValueChange={onChange}>
        <SelectTrigger id={id}><SelectValue placeholder={placeholder} /></SelectTrigger>
        <SelectContent className="max-h-80">
          {options.map((o) => (
            <SelectItem key={o.code} value={o.code}>
              <span className="flex flex-col gap-0.5 py-0.5">
                <span>{o.label}</span>
                {/* Clamped to two lines: the row has to stay scannable, and the full text is one click
                    away in the ⓘ panel and stays under the field once chosen. */}
                {o.hint && (
                  <span className="line-clamp-2 max-w-[26rem] text-[11px] text-muted-foreground">
                    {o.hint}
                  </span>
                )}
              </span>
            </SelectItem>
          ))}
        </SelectContent>
      </Select>

      {chosen?.hint && (
        <span className="text-[11px] text-muted-foreground">{chosen.hint}</span>
      )}
    </div>
  );
}

/**
 * What each field is for. One sentence each, shown above the option list.
 *
 * Kept beside the component that renders them rather than in the database, because these describe the
 * PRODUCT's three fields — which exist for every tenant — while the option explanations describe a
 * tenant's own taxonomy rows and belong with the rows.
 */
export const TAXONOMY_HELP = {
  category:
    "Who is harmed and how, in words an executive can act on. One per finding — choose the mechanism "
    + "that makes the weakness exploitable, not the system it was found in.",
  owasp:
    "The OWASP Top 10:2025 entry. The names are OWASP's and are never edited. Choose "
    + "\"Not in the OWASP Top 10:2025\" as a conclusion when none fits — that is different from "
    + "leaving it undecided.",
  cwe:
    "The MITRE weakness class, using MITRE's own identifier. If you cannot determine it accurately, "
    + "choose CWE-UNKNOWN: a guessed CWE is wrong and looks authoritative, and every report built on it "
    + "inherits the error.",
} as const;
