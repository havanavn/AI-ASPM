import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  forceCenter, forceCollide, forceLink, forceManyBody, forceSimulation, forceX, forceY,
  type Simulation, type SimulationLinkDatum, type SimulationNodeDatum,
} from "d3-force";
import { Loader2, Maximize2, RotateCcw } from "lucide-react";
import { api, ApiError } from "@/lib/api";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";

/**
 * The estate graph: one node, what it is connected to, and what the reader chooses to open.
 *
 * <h2>Why this is hand-rolled SVG over `d3-force` rather than a graph library</h2>
 *
 * Every visualisation in this interface is SVG written here — the trend line, the capacity bars, the
 * assessment plan. ADR-006 asks for a single design language, and a graph library brings a second
 * one: its own styling vocabulary, its own event model, and a canvas renderer that is one opaque
 * element to a keyboard and to a screen reader. `d3-force` is the simulation only, 89 kB unpacked,
 * ISC. The 265 kB of `d3-zoom`, `d3-drag` and `d3-selection` are deliberately not here: panning is a
 * transform and dragging is three pointer handlers, and pulling in `d3-selection` would put a second
 * DOM-mutation model next to React's.
 *
 * <h2>Shape carries the kind. Colour carries the risk. Neither carries both</h2>
 *
 * DOC-00 prohibits colour as the sole carrier of meaning in a diagram, and a graph is the easiest
 * place in a product to break that rule. So an organization node is a rounded rectangle whatever its
 * colour, an application is a circle, a project a diamond, a service a square, a repository a
 * hexagon, a domain a stadium — legible in monochrome and to anybody who cannot separate red from
 * green. Colour is then free to mean one thing only: whether the node has open critical findings.
 *
 * <h2>It expands; it does not load the estate</h2>
 *
 * Each fetch is one node's neighbourhood. The server marks which neighbours have neighbours of their
 * own, so an unopened branch is visibly different from a leaf — without that, a reader learns to
 * click everything, which is the same as having no affordance.
 *
 * <h2>A dashed stub means the picture is partial</h2>
 *
 * `boundary` says a node is connected to something outside the reader's scope. The count is not
 * disclosed and neither is the identity. It is drawn rather than omitted because a graph that
 * silently stops at the edge of a permission is a graph that looks complete, which is the first
 * product principle inverted.
 */

// -------------------------------------------------------------------------------------- the data

export interface GraphNode {
  id: string;
  kind: "ASSET" | "ORG";
  typeCode: string;
  name: string;
  lifecycleState: string | null;
  exposureDeclared: string | null;
  criticalityCode: string | null;
  /** null, never 0, where nothing has been measured (`PRD-UIX-022`). */
  findingOpen: number | null;
  criticalOpen: number | null;
  boundary: boolean;
  expandable: boolean;
}
export interface GraphEdge { from: string; to: string; kind: string }
interface Neighbourhood { root: GraphNode; nodes: GraphNode[]; edges: GraphEdge[] }

type Placed = GraphNode & SimulationNodeDatum;
type Linked = SimulationLinkDatum<Placed> & { kind: string };

/** Accountability edges are dashed, technical containment solid. A legend says so. */
const ACCOUNTABILITY = new Set(["OWNS", "PARENT"]);

const RADIUS: Record<string, number> = {
  ORG: 15, APPLICATION: 16, PROJECT: 13, SERVICE: 12, FEATURE: 9, REPOSITORY: 12, DOMAIN: 12,
};

function radiusOf(node: GraphNode): number {
  return RADIUS[node.kind === "ORG" ? "ORG" : node.typeCode] ?? 11;
}

/**
 * One node's outline, centred on the origin.
 *
 * Returned as an element rather than a path string because a stadium needs a rect with a radius and
 * a circle needs a circle: forcing everything through `<path>` would mean hand-writing arc commands
 * for shapes SVG already has.
 */
function Outline({ node, className }: { node: GraphNode; className: string }) {
  const r = radiusOf(node);
  if (node.kind === "ORG") {
    return <rect className={className} x={-r * 1.5} y={-r * 0.78} width={r * 3} height={r * 1.56}
                 rx={4} />;
  }
  switch (node.typeCode) {
    case "PROJECT":       // diamond
      return <polygon className={className}
                      points={`0,${-r * 1.25} ${r * 1.25},0 0,${r * 1.25} ${-r * 1.25},0`} />;
    case "SERVICE":       // square
      return <rect className={className} x={-r} y={-r} width={r * 2} height={r * 2} rx={2} />;
    case "FEATURE":       // triangle
      return <polygon className={className}
                      points={`0,${-r * 1.3} ${r * 1.2},${r * 0.9} ${-r * 1.2},${r * 0.9}`} />;
    case "REPOSITORY": {  // hexagon
      const points = [0, 1, 2, 3, 4, 5].map((i) => {
        const angle = (Math.PI / 3) * i - Math.PI / 6;
        return `${(r * 1.15 * Math.cos(angle)).toFixed(1)},${(r * 1.15 * Math.sin(angle)).toFixed(1)}`;
      }).join(" ");
      return <polygon className={className} points={points} />;
    }
    case "DOMAIN":        // stadium
      return <rect className={className} x={-r * 1.6} y={-r * 0.7} width={r * 3.2}
                   height={r * 1.4} rx={r * 0.7} />;
    default:              // APPLICATION, and anything a tenant adds later
      return <circle className={className} r={r} />;
  }
}

/** Risk, and only risk. An unmeasured node is not "clean" and is not coloured as though it were. */
function riskClass(node: GraphNode): string {
  if ((node.criticalOpen ?? 0) > 0) return "fill-tone-critical/15 stroke-tone-critical";
  if ((node.findingOpen ?? 0) > 0) return "fill-tone-warn/15 stroke-tone-warn";
  if (node.findingOpen === null) return "fill-muted stroke-muted-foreground/50";
  return "fill-tone-ok/10 stroke-tone-ok/70";
}

// ------------------------------------------------------------------------------------ the canvas

export function ForceGraph({ rootId, onOpen, height = 520 }: {
  rootId: string;
  /** Called when a reader asks to leave the graph for the record itself. */
  onOpen?: (node: GraphNode) => void;
  height?: number;
}) {
  const [nodes, setNodes] = useState<Placed[]>([]);
  const [links, setLinks] = useState<Linked[]>([]);
  const [expanded, setExpanded] = useState<Set<string>>(new Set());
  const [loading, setLoading] = useState<string | null>(rootId);
  const [error, setError] = useState<string | null>(null);
  const [focus, setFocus] = useState<string | null>(null);
  const [view, setView] = useState({ x: 0, y: 0, k: 1 });
  const [, redraw] = useState(0);

  const frame = useRef<SVGSVGElement | null>(null);
  const simulation = useRef<Simulation<Placed, Linked> | null>(null);
  const dragging = useRef<{ id: string; dx: number; dy: number } | null>(null);
  const panning = useRef<{ x: number; y: number; ox: number; oy: number } | null>(null);

  // A reader who has asked for less motion gets a graph that is already settled rather than one that
  // animates into place. The layout is the same; only the arriving at it is skipped.
  const stillness = useMemo(
    () => typeof window !== "undefined"
      && window.matchMedia?.("(prefers-reduced-motion: reduce)").matches, []);

  const merge = useCallback((payload: Neighbourhood) => {
    setNodes((current) => {
      const byId = new Map(current.map((n) => [n.id, n]));
      const centre = byId.get(payload.root.id);
      for (const incoming of [payload.root, ...payload.nodes]) {
        const existing = byId.get(incoming.id);
        if (existing) {
          Object.assign(existing, incoming);   // facts refresh; position survives
        } else {
          byId.set(incoming.id, {
            ...incoming,
            // New nodes start near the node they were opened from, so an expansion reads as
            // growth from that point rather than as the whole graph rearranging.
            x: (centre?.x ?? 0) + (Math.random() - 0.5) * 60,
            y: (centre?.y ?? 0) + (Math.random() - 0.5) * 60,
          });
        }
      }
      return [...byId.values()];
    });
    setLinks((current) => {
      const seen = new Set(current.map((l) => `${idOf(l.source)}>${idOf(l.target)}:${l.kind}`));
      const added: Linked[] = [];
      for (const edge of payload.edges) {
        const key = `${edge.from}>${edge.to}:${edge.kind}`;
        if (!seen.has(key)) {
          seen.add(key);
          added.push({ source: edge.from, target: edge.to, kind: edge.kind });
        }
      }
      return [...current, ...added];
    });
  }, []);

  const fetchAround = useCallback((id: string) => {
    setLoading(id);
    setError(null);
    api.get<Neighbourhood>(`/api/ui/graph/${id}`)
      .then((payload) => {
        merge(payload);
        setExpanded((e) => new Set(e).add(id));
      })
      .catch((failure) => setError((failure as ApiError).message))
      .finally(() => setLoading(null));
  }, [merge]);

  useEffect(() => {
    setNodes([]); setLinks([]); setExpanded(new Set()); setView({ x: 0, y: 0, k: 1 });
    setFocus(rootId);
    fetchAround(rootId);
  }, [rootId, fetchAround]);

  // The simulation is rebuilt whenever the node or link set changes, and reheated rather than
  // restarted from scratch: positions carry over, so an expansion nudges the layout instead of
  // reshuffling a picture the reader had already made sense of.
  useEffect(() => {
    if (!nodes.length) return;
    const sim = forceSimulation<Placed, Linked>(nodes)
      .force("link", forceLink<Placed, Linked>(links).id((d) => d.id).distance(96).strength(0.7))
      .force("charge", forceManyBody().strength(-380))
      .force("collide", forceCollide<Placed>((d) => radiusOf(d) + 12))
      .force("centre", forceCenter(0, 0).strength(0.06))
      .force("x", forceX(0).strength(0.02))
      .force("y", forceY(0).strength(0.02));

    if (stillness) {
      sim.stop();
      for (let i = 0; i < 240; i += 1) sim.tick();
      redraw((n) => n + 1);
    } else {
      sim.alpha(0.9).on("tick", () => redraw((n) => n + 1));
    }
    simulation.current = sim;
    return () => { sim.stop(); simulation.current = null; };
  }, [nodes, links, stillness]);

  // ------------------------------------------------------------------------------ pan, zoom, drag

  const onPointerDown = (event: React.PointerEvent<SVGSVGElement>) => {
    if (dragging.current) return;
    panning.current = { x: event.clientX, y: event.clientY, ox: view.x, oy: view.y };
    (event.target as Element).setPointerCapture?.(event.pointerId);
  };
  const onPointerMove = (event: React.PointerEvent<SVGSVGElement>) => {
    if (dragging.current) {
      const point = toGraph(event, frame.current, view);
      const node = nodes.find((n) => n.id === dragging.current!.id);
      if (node) {
        node.fx = point.x - dragging.current.dx;
        node.fy = point.y - dragging.current.dy;
        simulation.current?.alpha(0.35).restart();
      }
      return;
    }
    if (panning.current) {
      setView((v) => ({
        ...v,
        x: panning.current!.ox + (event.clientX - panning.current!.x),
        y: panning.current!.oy + (event.clientY - panning.current!.y),
      }));
    }
  };
  const endPointer = () => {
    if (dragging.current) {
      const node = nodes.find((n) => n.id === dragging.current!.id);
      // Released, not pinned. A node that stayed where it was dropped would slowly turn the layout
      // into a hand-drawn diagram nobody maintains.
      if (node) { node.fx = null; node.fy = null; }
      dragging.current = null;
      simulation.current?.alpha(0.2).restart();
    }
    panning.current = null;
  };
  const onWheel = (event: React.WheelEvent<SVGSVGElement>) => {
    event.preventDefault();
    setView((v) => ({ ...v, k: Math.min(2.5, Math.max(0.35, v.k * (event.deltaY < 0 ? 1.1 : 0.9))) }));
  };

  // -------------------------------------------------------------------------------- the keyboard

  /**
   * Arrow keys move to the neighbour furthest in that direction; Enter opens what is focused.
   *
   * ADR-006 is keyboard-first, and a graph is where that is usually abandoned. Each node is a real
   * focusable element, so tab order works without this; the arrows exist because tabbing through
   * forty nodes to reach the one next to you is technically accessible and practically not.
   */
  const move = (from: string, dx: number, dy: number) => {
    const origin = nodes.find((n) => n.id === from);
    if (!origin) return;
    const neighbours = links
      .map((l) => (idOf(l.source) === from ? idOf(l.target)
        : idOf(l.target) === from ? idOf(l.source) : null))
      .filter((id): id is string => !!id);
    let best: { id: string; score: number } | null = null;
    for (const id of new Set(neighbours)) {
      const node = nodes.find((n) => n.id === id);
      if (!node) continue;
      const vx = (node.x ?? 0) - (origin.x ?? 0);
      const vy = (node.y ?? 0) - (origin.y ?? 0);
      const length = Math.hypot(vx, vy) || 1;
      const alignment = (vx * dx + vy * dy) / length;
      if (alignment > 0.35 && (!best || alignment > best.score)) {
        best = { id, score: alignment };
      }
    }
    if (best) {
      setFocus(best.id);
      frame.current?.querySelector<SVGGElement>(`[data-node="${best.id}"]`)?.focus();
    }
  };

  const onNodeKeyDown = (event: React.KeyboardEvent<SVGGElement>, node: Placed) => {
    const arrows: Record<string, [number, number]> = {
      ArrowRight: [1, 0], ArrowLeft: [-1, 0], ArrowUp: [0, -1], ArrowDown: [0, 1],
    };
    const direction = arrows[event.key];
    if (direction) {
      event.preventDefault();
      move(node.id, direction[0], direction[1]);
    } else if (event.key === "Enter" || event.key === " ") {
      event.preventDefault();
      if (node.expandable && !expanded.has(node.id)) fetchAround(node.id);
      else onOpen?.(node);
    }
  };

  // ------------------------------------------------------------------------------------ rendering

  const box = { width: 900, height };
  const transform = `translate(${box.width / 2 + view.x} ${box.height / 2 + view.y}) scale(${view.k})`;

  if (error) {
    return (
      <div className="grid place-items-center rounded-md border border-dashed p-8 text-sm">
        <p className="text-destructive">{error}</p>
        <Button size="sm" variant="ghost" className="mt-2" onClick={() => fetchAround(rootId)}>
          <RotateCcw className="size-3" /> Try again
        </Button>
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-2">
      <div className="flex flex-wrap items-center gap-2 text-[11px] text-muted-foreground">
        <Legend />
        <span className="flex-1" />
        {loading && <span className="flex items-center gap-1"><Loader2 className="size-3 animate-spin" /> loading…</span>}
        <Button size="sm" variant="ghost" onClick={() => setView({ x: 0, y: 0, k: 1 })}>
          <Maximize2 className="size-3" /> Recentre
        </Button>
      </div>

      <svg ref={frame} viewBox={`0 0 ${box.width} ${box.height}`}
           className="w-full cursor-grab touch-none rounded-md border bg-card active:cursor-grabbing"
           style={{ blockSize: height }}
           role="application"
           aria-label="Estate graph. Use tab to move between nodes, the arrow keys to move to a
                       connected node, and enter to expand or open one."
           onPointerDown={onPointerDown} onPointerMove={onPointerMove}
           onPointerUp={endPointer} onPointerLeave={endPointer} onWheel={onWheel}>
        <g transform={transform}>
          {links.map((link, index) => {
            const source = typeof link.source === "object" ? link.source : null;
            const target = typeof link.target === "object" ? link.target : null;
            if (!source || !target) return null;
            return (
              <line key={`${idOf(link.source)}-${idOf(link.target)}-${link.kind}-${index}`}
                    x1={source.x} y1={source.y} x2={target.x} y2={target.y}
                    className={ACCOUNTABILITY.has(link.kind)
                      ? "stroke-muted-foreground/45" : "stroke-muted-foreground/70"}
                    strokeWidth={1.2}
                    strokeDasharray={ACCOUNTABILITY.has(link.kind) ? "4 3" : undefined} />
            );
          })}

          {nodes.map((node) => {
            const r = radiusOf(node);
            const isFocus = focus === node.id;
            const unopened = node.expandable && !expanded.has(node.id);
            return (
              <g key={node.id} data-node={node.id} tabIndex={0} role="button"
                 transform={`translate(${node.x ?? 0} ${node.y ?? 0})`}
                 className="cursor-pointer outline-none"
                 aria-label={describe(node, unopened)}
                 onKeyDown={(event) => onNodeKeyDown(event, node)}
                 onFocus={() => setFocus(node.id)}
                 onPointerDown={(event) => {
                   event.stopPropagation();
                   const point = toGraph(event, frame.current, view);
                   dragging.current = {
                     id: node.id, dx: point.x - (node.x ?? 0), dy: point.y - (node.y ?? 0),
                   };
                 }}
                 onClick={(event) => {
                   event.stopPropagation();
                   if (unopened) fetchAround(node.id); else onOpen?.(node);
                 }}>
                {isFocus && <Outline node={node} className="fill-none stroke-primary"
                                     key="focus-ring" />}
                <Outline node={node} className={`${riskClass(node)} stroke-[1.4]`} />

                {/* A dashed stub, not a count: this node touches something outside your scope. */}
                {node.boundary && (
                  <line x1={r * 1.1} y1={-r * 1.1} x2={r * 1.9} y2={-r * 1.9}
                        className="stroke-muted-foreground" strokeWidth={1.2}
                        strokeDasharray="2 2" />
                )}

                {/* The affordance for an unopened branch. A plus, not a colour. */}
                {unopened && (
                  <>
                    <line x1={-3} y1={r + 8} x2={3} y2={r + 8} className="stroke-foreground" strokeWidth={1.4} />
                    <line x1={0} y1={r + 5} x2={0} y2={r + 11} className="stroke-foreground" strokeWidth={1.4} />
                  </>
                )}

                <text y={-r - 7} textAnchor="middle"
                      className="pointer-events-none fill-foreground text-[10px] font-medium">
                  {node.name.length > 26 ? `${node.name.slice(0, 25)}…` : node.name}
                </text>
                <text y={r + 20} textAnchor="middle"
                      className="pointer-events-none fill-muted-foreground text-[9px]">
                  {node.kind === "ORG" ? node.typeCode.toLowerCase().replace(/_/g, " ")
                    : node.findingOpen === null ? "not measured"
                    : `${node.findingOpen} open`}
                </text>
              </g>
            );
          })}
        </g>
      </svg>

      {focus && <Selected node={nodes.find((n) => n.id === focus)} onOpen={onOpen} />}
    </div>
  );
}

// ------------------------------------------------------------------------------------- fragments

function Legend() {
  return (
    <span className="flex flex-wrap items-center gap-x-3 gap-y-1">
      {[["organization", "rect"], ["application", "circle"], ["project", "diamond"],
        ["service", "square"], ["repository", "hexagon"], ["domain", "stadium"]]
        .map(([label, shape]) => (
          <span key={label} className="flex items-center gap-1">
            <svg width="14" height="14" viewBox="-8 -8 16 16" aria-hidden="true">
              <Outline node={{ kind: shape === "rect" ? "ORG" : "ASSET",
                               typeCode: { circle: "APPLICATION", diamond: "PROJECT",
                                           square: "SERVICE", hexagon: "REPOSITORY",
                                           stadium: "DOMAIN", rect: "ORG" }[shape as string]!,
                             } as GraphNode}
                       className="fill-muted stroke-muted-foreground" />
            </svg>
            {label}
          </span>
        ))}
      <span className="flex items-center gap-1">
        <svg width="14" height="10" viewBox="0 0 14 10" aria-hidden="true">
          <line x1="0" y1="5" x2="14" y2="5" className="stroke-muted-foreground/45"
                strokeWidth="1.2" strokeDasharray="4 3" />
        </svg>
        owns
      </span>
      <span className="flex items-center gap-1">
        <svg width="14" height="10" viewBox="0 0 14 10" aria-hidden="true">
          <line x1="0" y1="5" x2="14" y2="5" className="stroke-muted-foreground/70" strokeWidth="1.2" />
        </svg>
        contains
      </span>
      <span className="flex items-center gap-1">
        <svg width="14" height="14" viewBox="-7 -7 14 14" aria-hidden="true">
          <line x1="-4" y1="4" x2="4" y2="-4" className="stroke-muted-foreground" strokeWidth="1.2"
                strokeDasharray="2 2" />
        </svg>
        connects beyond your scope
      </span>
    </span>
  );
}

function Selected({ node, onOpen }: { node?: GraphNode; onOpen?: (n: GraphNode) => void }) {
  if (!node) return null;
  return (
    <div className="flex flex-wrap items-center gap-2 rounded-md border bg-muted/40 px-3 py-2 text-xs">
      <span className="font-medium">{node.name}</span>
      <Badge tone="neutral">{node.kind === "ORG" ? "organization" : node.typeCode.toLowerCase()}</Badge>
      {node.exposureDeclared && <Badge tone="info">{node.exposureDeclared.toLowerCase()}</Badge>}
      {node.criticalityCode && <Badge tone="warn">{node.criticalityCode}</Badge>}
      {/* Absent and zero are different answers, and the badge says which. */}
      {node.findingOpen === null
        ? <span className="italic text-tone-unknown">nothing measured</span>
        : <span>{node.findingOpen} open{(node.criticalOpen ?? 0) > 0
            ? `, ${node.criticalOpen} critical` : ""}</span>}
      {node.boundary && (
        <span className="text-muted-foreground">
          · connects to something outside your scope
        </span>
      )}
      <span className="flex-1" />
      {onOpen && <Button size="sm" variant="ghost" onClick={() => onOpen(node)}>Open record</Button>}
    </div>
  );
}

function describe(node: GraphNode, unopened: boolean): string {
  const kind = node.kind === "ORG" ? "organization node" : node.typeCode.toLowerCase();
  const findings = node.findingOpen === null ? "nothing measured"
    : `${node.findingOpen} open finding${node.findingOpen === 1 ? "" : "s"}`;
  return [node.name, kind, findings,
    node.boundary ? "connects beyond your scope" : null,
    unopened ? "press enter to expand" : "press enter to open the record"]
    .filter(Boolean).join(", ");
}

function idOf(end: string | Placed | number | undefined): string {
  return typeof end === "object" && end !== null ? end.id : String(end);
}

function toGraph(event: { clientX: number; clientY: number }, svg: SVGSVGElement | null,
                 view: { x: number; y: number; k: number }) {
  if (!svg) return { x: 0, y: 0 };
  const box = svg.getBoundingClientRect();
  const scale = 900 / box.width;             // the viewBox is 900 wide whatever the element is
  return {
    x: ((event.clientX - box.left) * scale - 450 - view.x) / view.k,
    y: ((event.clientY - box.top) * scale - (svg.viewBox.baseVal.height / 2) - view.y) / view.k,
  };
}
