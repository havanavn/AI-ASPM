export interface Session {
  displayName: string;
  username: string | null;
  /** The organizations this caller reaches, by name. Null where they reach none. */
  scopeLabel: string | null;
  permissions: string[];
  nav: { href: string; labelKey: string; label: string }[];
}

export interface BoardRow {
  id: string;
  code: string;
  title: string | null;
  state: string;
  stateLabel: string;
  stateCategory: string | null;
  createdAt: string | null;
  dueAt: string | null;
  closedAt: string | null;
  overdue: boolean;
  orgNodeName: string | null;
  orgAncestors: string[];
  application: string | null;
  /** Projects the request names. More than one means a full application review. */
  projects: { id: string; name: string; namedByRequester: boolean }[];
  scopeAssets: number;
  triggerCode: string | null;
  triggerLabel: string | null;
  triggerIsFullReview: boolean;
  findingTotal: number;
  findingOpen: number;
  findingAccepted: number;
  findingSevereOpen: number;
  contact: string | null;
  assessor: string | null;
}

export interface Board {
  rows: BoardRow[];
  states: { code: string; label: string }[];
  triggers: { code: string; label: string; countsAsFullReview: boolean }[];
  /**
   * The filter option lists, each scoped to what the caller can reach — not to the rows on screen.
   * Options derived from visible rows cannot widen a selection.
   */
  organizations: { id: string; name: string; hint: string }[];
  projects: { id: string; name: string; hint: string }[];
  applications: { id: string; name: string; hint: string }[];
  assessors: { id: string; name: string; hint: string }[];
  totals: { requests: number; overdue: number; unassigned: number; openFindings: number };
}

export interface Move {
  event: string;
  toState: string;
  toStateLabel: string;
  permitted: boolean;
  reasonRequired: boolean;
  blockedReason: string | null;
  closes: boolean;
}

export interface Finding {
  id: string;
  title: string;
  severity: string | null;
  state: string;
  closureReason: string | null;
  context: string | null;
  firstDetectedAt: string | null;
  acceptedUntil: string | null;
}

export interface CommentRow {
  id: string;
  author: string | null;
  createdAt: string | null;
  editCount: number;
  redacted: boolean;
  html: string | null;
}

export interface RequestDetail {
  row: BoardRow;
  moves: Move[];
  findings: Finding[];
  people: { id: string; name: string }[];
  triggers: { id: string; code: string; label: string; countsAsFullReview: boolean }[];
  triggerId: string | null;
  contactId: string | null;
  assessorId: string | null;
  comments: CommentRow[];
  mayAct: boolean;
}
