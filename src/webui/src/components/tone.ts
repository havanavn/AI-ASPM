import type { BoardRow } from "@/lib/types";

type Tone = "neutral" | "critical" | "high" | "medium" | "low" | "ok" | "warn" | "info" | "unknown";

/**
 * The tone for a workflow state.
 *
 * <p>Driven by the state's CATEGORY, which is workflow data, and never by the state code. A tenant
 * that renames its states keeps its colours; a switch over codes would quietly fall through to grey
 * and nobody would notice, because grey is what an unmapped value looks like anyway.
 */
export function stateTone(category: string | null): Tone {
  switch (category) {
    case "OPEN": return "info";
    case "IN_PROGRESS": return "info";
    case "WAITING_EXTERNAL": return "warn";
    case "TERMINAL": return "ok";
    default: return "neutral";
  }
}

export function severityTone(severity: string | null): Tone {
  switch (severity) {
    case "CRITICAL": return "critical";
    case "HIGH": return "high";
    case "MEDIUM": return "medium";
    case "LOW": return "low";
    default: return "unknown";
  }
}

export function dueTone(row: BoardRow): Tone {
  return row.overdue ? "critical" : "neutral";
}
