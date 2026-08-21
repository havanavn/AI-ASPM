import { Checkbox } from "@/components/ui/checkbox";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Badge } from "@/components/ui/badge";

/**
 * One field the tenant declared on an asset type.
 *
 * `permittedValues` is the dropdown. It arrives from the server because it is tenant data (ADR-027):
 * another deployment tracks different WAF vendors and a different set of user categories, and a list
 * compiled into this bundle would make that a release instead of an INSERT.
 */
export interface FieldDefinition {
  key: string;
  label: string;
  /**
   * The storage kinds a declared field can have, plus one that is not a storage kind at all.
   *
   * `HOSTNAME` is a **derived** column: the hosts an asset is published on in one environment, which
   * live as `DOMAIN` assets on the far end of an edge rather than in `asset.attributes`. It never
   * appears in the declared-field catalogue and the field editor never offers it. It is in this union
   * because the column picker and the filter bar render both kinds side by side, and making the
   * reader learn where the platform draws its internal line would be the wrong place to spend their
   * attention.
   */
  dataType: "TEXT" | "LONG_TEXT" | "URL" | "BOOLEAN" | "INTEGER" | "SINGLE_SELECT" | "MULTI_SELECT"
    | "HOSTNAME";
  permittedValues: string[];
  required: boolean;
  filterable: boolean;
  /** The question this field answers. Rendered under it, not in a tooltip — see below. */
  purpose: string | null;
  /** `HOSTNAME` only: the environment code this column is the endpoints of. */
  environment?: string;
  /** `HOSTNAME` only: `ACTIVE`, `DEPRECATED`, or `UNDECLARED` for one only the data carries. */
  lifecycleState?: string;
}

/** The value shapes the server stores per data type. */
export type AttributeValue = string | number | boolean | string[] | null;

const NONE = "__none__";

/** Turn CONSTANT_CASE into something readable without inventing a translation. */
export function humanise(value: string): string {
  return value.replace(/_/g, " ").toLowerCase();
}

/**
 * The editor for a tenant-declared attribute set.
 *
 * **Every field carries its purpose on the page, not in a tooltip.** The catalogue records why each
 * field exists, and a field whose purpose nobody can state is a field people fill in wrongly and then
 * filter on — which is worse than leaving it blank, because a wrong value looks like an answer. A
 * tooltip is not the same thing: the person who needs the sentence is the one who does not know to
 * hover.
 *
 * **MULTI_SELECT is a checkbox grid rather than a popover.** These lists are short and the person is
 * recording facts about a system they know, not searching. Showing every option at once means they
 * notice the one they were about to omit; a collapsed control means they tick what they thought of.
 *
 * The component renders whatever it is given. It has no knowledge of which fields exist — adding
 * `pci_scope` to the catalogue makes it appear here with no change to this file.
 */
export function AttributeFields({ fields, values, onChange, disabled = false }: {
  fields: FieldDefinition[];
  values: Record<string, AttributeValue>;
  onChange: (key: string, value: AttributeValue) => void;
  disabled?: boolean;
}) {
  if (fields.length === 0) {
    return (
      <p className="text-xs text-muted-foreground">
        {/* Not an error and not an empty box. A type with no declared fields is a configuration
            state somebody can act on, and saying so beats rendering nothing. */}
        No fields are declared for this type yet. They are tenant configuration — an administrator
        adds them once and every record of this type gains them.
      </p>
    );
  }
  return (
    <div className="grid gap-5 md:grid-cols-2">
      {fields.map((field) => (
        <div key={field.key}
             className={field.dataType === "LONG_TEXT" || field.dataType === "MULTI_SELECT"
               ? "md:col-span-2" : ""}>
          <OneField field={field} value={values[field.key] ?? null}
                    onChange={(v) => onChange(field.key, v)} disabled={disabled} />
        </div>
      ))}
    </div>
  );
}

function OneField({ field, value, onChange, disabled }: {
  field: FieldDefinition;
  value: AttributeValue;
  onChange: (value: AttributeValue) => void;
  disabled: boolean;
}) {
  const header = (
    <div className="flex flex-wrap items-baseline gap-2">
      <Label htmlFor={`attr-${field.key}`}>
        {field.label}
        {field.required && <span className="pl-0.5 text-destructive">*</span>}
      </Label>
      {field.filterable && (
        <span className="text-[10px] uppercase tracking-wide text-muted-foreground">filterable</span>
      )}
    </div>
  );
  const purpose = field.purpose && (
    <p className="pt-1 text-[11px] leading-tight text-muted-foreground">{field.purpose}</p>
  );

  switch (field.dataType) {
    case "LONG_TEXT":
      return (
        <div className="flex flex-col gap-1.5">
          {header}
          <textarea id={`attr-${field.key}`} rows={3} disabled={disabled}
                    className="flex w-full rounded-md border border-input bg-transparent px-3 py-2 text-sm shadow-xs outline-none focus-visible:border-ring focus-visible:ring-[3px] focus-visible:ring-ring/50 disabled:opacity-50"
                    value={typeof value === "string" ? value : ""}
                    onChange={(e) => onChange(e.target.value)} />
          {purpose}
        </div>
      );

    case "BOOLEAN":
      return (
        <div className="flex flex-col gap-1.5">
          <div className="flex items-center gap-2">
            <Checkbox id={`attr-${field.key}`} disabled={disabled} checked={value === true}
                      onCheckedChange={(v) => onChange(v === true)} />
            <Label htmlFor={`attr-${field.key}`}>{field.label}</Label>
          </div>
          {purpose}
        </div>
      );

    case "INTEGER":
      return (
        <div className="flex flex-col gap-1.5">
          {header}
          {/* No default of zero. An empty box means nobody has counted, and a zero would claim the
              project exposes nothing — product principle 1, in a number input. */}
          <Input id={`attr-${field.key}`} type="number" min={0} step={1} disabled={disabled}
                 placeholder="not counted"
                 value={value === null || value === undefined ? "" : String(value)}
                 onChange={(e) => onChange(e.target.value === "" ? null : e.target.value)} />
          {purpose}
        </div>
      );

    case "URL":
      return (
        <div className="flex flex-col gap-1.5">
          {header}
          <Input id={`attr-${field.key}`} type="url" disabled={disabled}
                 placeholder="https://"
                 value={typeof value === "string" ? value : ""}
                 onChange={(e) => onChange(e.target.value)} />
          {purpose}
        </div>
      );

    case "SINGLE_SELECT":
      return (
        <div className="flex flex-col gap-1.5">
          {header}
          <Select disabled={disabled}
                  value={typeof value === "string" && value !== "" ? value : NONE}
                  onValueChange={(v) => onChange(v === NONE ? "" : v)}>
            <SelectTrigger id={`attr-${field.key}`}><SelectValue /></SelectTrigger>
            <SelectContent>
              {/* Blank is offered explicitly. Without it a field can be set and never unset, and the
                  first wrong choice becomes permanent. */}
              <SelectItem value={NONE}>— not recorded —</SelectItem>
              {field.permittedValues.map((option) => (
                <SelectItem key={option} value={option}>{humanise(option)}</SelectItem>
              ))}
            </SelectContent>
          </Select>
          {purpose}
        </div>
      );

    case "MULTI_SELECT": {
      const chosen = Array.isArray(value) ? value : [];
      return (
        <div className="flex flex-col gap-1.5">
          {header}
          <div className="grid grid-cols-2 gap-x-4 gap-y-1.5 rounded-md border p-3 sm:grid-cols-3 lg:grid-cols-4">
            {field.permittedValues.map((option) => (
              <label key={option} className="flex items-center gap-2 text-xs">
                <Checkbox disabled={disabled} checked={chosen.includes(option)}
                          onCheckedChange={(v) => onChange(v === true
                            ? [...chosen, option]
                            : chosen.filter((c) => c !== option))} />
                {humanise(option)}
              </label>
            ))}
          </div>
          {purpose}
        </div>
      );
    }

    default:
      return (
        <div className="flex flex-col gap-1.5">
          {header}
          <Input id={`attr-${field.key}`} disabled={disabled}
                 value={typeof value === "string" ? value : ""}
                 onChange={(e) => onChange(e.target.value)} />
          {purpose}
        </div>
      );
  }
}

/**
 * The same fields, read-only, for a detail page.
 *
 * **A declared field with no value is shown, not hidden.** Hiding it makes an unfilled inventory look
 * like a complete one, and the gap is the thing somebody needs to see: "no WAF recorded" and "no WAF"
 * are different claims, and only one of them is a decision.
 */
export function AttributeSummary({ fields, values }: {
  fields: FieldDefinition[];
  values: Record<string, AttributeValue>;
}) {
  if (fields.length === 0) return null;
  return (
    <dl className="grid gap-x-6 gap-y-3 sm:grid-cols-2 lg:grid-cols-3">
      {fields.map((field) => (
        <div key={field.key} className={field.dataType === "LONG_TEXT" ? "sm:col-span-2 lg:col-span-3" : ""}>
          <dt className="text-[11px] uppercase tracking-wide text-muted-foreground">{field.label}</dt>
          <dd className="pt-0.5 text-sm"><ReadValue field={field} value={values[field.key] ?? null} /></dd>
        </div>
      ))}
    </dl>
  );
}

function ReadValue({ field, value }: { field: FieldDefinition; value: AttributeValue }) {
  const missing = <span className="text-xs italic text-tone-unknown">not recorded</span>;

  if (field.dataType === "MULTI_SELECT") {
    const chosen = Array.isArray(value) ? value : [];
    if (chosen.length === 0) return missing;
    return (
      <span className="flex flex-wrap gap-1">
        {chosen.map((c) => <Badge key={c}>{humanise(c)}</Badge>)}
      </span>
    );
  }
  if (field.dataType === "BOOLEAN") {
    return value === true ? <Badge tone="ok">yes</Badge> : <Badge tone="neutral">no</Badge>;
  }
  if (value === null || value === undefined || value === "") return missing;
  if (field.dataType === "URL") {
    return (
      <a href={String(value)} target="_blank" rel="noreferrer noopener"
         className="break-all text-primary hover:underline">{String(value)}</a>
    );
  }
  if (field.dataType === "SINGLE_SELECT") return <Badge>{humanise(String(value))}</Badge>;
  if (field.dataType === "INTEGER") return <span className="tabular">{String(value)}</span>;
  return <span className="whitespace-pre-wrap">{String(value)}</span>;
}
