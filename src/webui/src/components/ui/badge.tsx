import * as React from "react";
import { cva, type VariantProps } from "class-variance-authority";
import { cn } from "@/lib/utils";

/**
 * A badge, with one variant per MEANING rather than per colour.
 *
 * Each variant pairs its colour with a distinct border and weight, because colour alone must never
 * be the sole carrier of meaning — a monochrome print or a red/green colour deficiency has to leave
 * the badge readable (DOC-00 section 11.4). The text inside always says what it is.
 */
const badgeVariants = cva(
  "inline-flex items-center gap-1 rounded border px-1.5 py-0.5 text-[11px] font-medium leading-tight whitespace-nowrap",
  {
    variants: {
      tone: {
        neutral: "border-border bg-muted text-muted-foreground",
        critical: "border-sev-critical/40 bg-sev-critical/10 text-sev-critical font-semibold",
        high: "border-sev-high/40 bg-sev-high/10 text-sev-high font-semibold",
        medium: "border-sev-medium/40 bg-sev-medium/10 text-sev-medium",
        low: "border-sev-low/40 bg-sev-low/10 text-sev-low",
        ok: "border-tone-ok/40 bg-tone-ok/10 text-tone-ok",
        warn: "border-tone-warn/40 bg-tone-warn/10 text-tone-warn",
        info: "border-tone-info/40 bg-tone-info/10 text-tone-info",
        unknown: "border-dashed border-tone-unknown/50 bg-transparent text-tone-unknown italic",
      },
    },
    defaultVariants: { tone: "neutral" },
  },
);

function Badge({ className, tone, ...props }: React.ComponentProps<"span"> & VariantProps<typeof badgeVariants>) {
  return <span className={cn(badgeVariants({ tone }), className)} {...props} />;
}
export { Badge, badgeVariants };
