import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  forceCenter, forceCollide, forceLink, forceManyBody, forceSimulation, forceX, forceY,
  type Simulation, type SimulationLinkDatum, type SimulationNodeDatum,
} from "d3-force";
import { Loader2, Maximize2, RotateCcw, ZoomIn, ZoomOut } from "lucide-react";
import { api, ApiError } from "@/lib/api";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";

/**
 * The estate graph: one node, what it is connected to, and what the reader chooses to open.
 *
 * <h2>Hand-rolled SVG over `d3-force`, not a graph library</h2>
 *
 * Every visualisation in this interface is SVG written here — the trend line, the capacity bars, the
 * assessment plan. ADR-006 asks for a single design language, and a graph library brings a second
 * one: its own styling vocabulary, its own event model, and a canvas renderer that is one opaque
 * element to a keyboard and to a screen reader. `d3-force` is the simulation only, 89 kB unpacked,
 * ISC. `d3-zoom`, `d3-drag` and `d3-selection` are deliberately absent: panning is a transform,
 * dragging is three pointer handlers, and `d3-selection` would put a second DOM-mutation model next
 * to React's.
 *
 * <h2>Shape carries the kind. Colour carries the risk. Neither carries both</h2>
 *
 * DOC-00 prohibits colour as the sole carrier of meaning in a diagram. An organization node is a
 * rounded rectangle whatever its colour, an application a circle, a project a diamond, a service a
 * square, a repository a hexagon, a domain a stadium — legible in monochrome. Colour is then free to
 * mean one thing: the worst severity open on the node. An unmeasured node is dashed and grey, never
 * green, because `PRD-UIX-022` gives an unmeasured value no numeral form and a clean-looking node
 * nothing has looked at is the first product principle inverted.
 *
 * <h2>Focus dims the rest, and that is the readability decision</h2>
 *
 * A force graph past twenty nodes is a hairball. Rather than fight it with layout, hovering or
 * focusing a node drops everything not adjacent to it to a quarter opacity: the reader's question is
 * almost always "what is connected to THIS", and answering it by removing noise beats answering it
 * by drawing better.
 *
 * <h2>It expands; it does not load the estate</h2>
 *
 * Each fetch is one node's neighbourhood, and the server marks which neighbours have neighbours of
 * their own — so an unopened branch is visibly different from a leaf. A dashed stub on a node means
 * it connects to something outside the reader's scope: no count and no identity, because a count is
 * an oracle, but drawn rather than omitted because a graph that silently stops at a permission
 * boundary is a graph that looks complete.
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

/** Accountability edges are dashed, technical containment solid. The legend says so in words. */
const ACCOUNTABILITY = new Set(["OWNS", "PARENT"]);

const RADIUS: Record<string, number> = {
  ORG: 17, APPLICATION: 18, PROJECT: 14, SERVICE: 13, FEATURE: 10, REPOSITORY: 13, DOMAIN: 13,
};

function radiusOf(node: Pick<GraphNode, "kind" | "typeCode">): number {
  return RADIUS[node.kind === "ORG" ? "ORG" : node.typeCode] ?? 12;
}

/** The four colour states a node can be in. */
interface Risk { halo: string; ring: string; chip: string; label: string }

/**
 * *** THE CLASS NAMES ARE LITERALS, AND THAT IS NOT VERBOSITY. *** Tailwind generates a class by
 * finding it as text in the source, so `fill-${token}` generates nothing and the nodes render with
 * no colour at all. Written out, every class is real. It also keeps the tokens the badges use —
 * `sev-critical`, `sev-medium`, `tone-ok`, `tone-unknown` — so a node and the badge beside it cannot
 * disagree about what a colour means, and both follow the light and dark definitions in index.css.
 */
const RISK: Record<"critical" | "open" | "clear" | "unmeasured", Risk> = {
  critical: { halo: "fill-sev-critical/12", ring: "stroke-sev-critical",
              chip: "fill-sev-critical", label: "critical findings open" },
  open:     { halo: "fill-sev-medium/12", ring: "stroke-sev-medium",
              chip: "fill-sev-medium", label: "findings open" },
  clear:    { halo: "fill-tone-ok/10", ring: "stroke-tone-ok",
              chip: "fill-tone-ok", label: "measured, nothing open" },
  unmeasured: { halo: "fill-tone-unknown/10", ring: "stroke-tone-unknown",
                chip: "fill-tone-unknown", label: "nothing measured" },
};

function risk(node: GraphNode): Risk {
  if ((node.criticalOpen ?? 0) > 0) return RISK.critical;
  if ((node.findingOpen ?? 0) > 0) return RISK.open;
  if (node.findingOpen === null) return RISK.unmeasured;
  return RISK.clear;
}

/** One node's outline, centred on the origin. Shapes SVG already has, rather than hand-cut paths. */
function Outline({ node, className, scale = 1, style }: {
  node: Pick<GraphNode, "kind" | "typeCode">;
  className?: string;
  scale?: number;
  style?: React.CSSProperties;
}) {
  const r = radiusOf(node) * scale;
  const props = { className, style };
  if (node.kind === "ORG") {
    return <rect {...props} x={-r * 1.55} y={-r * 0.8} width={r * 3.1} height={r * 1.6} rx={5} />;
  }
  switch (node.typeCode) {
    case "PROJECT":
      return <polygon {...props}
                      points={`0,${-r * 1.3} ${r * 1.3},0 0,${r * 1.3} ${-r * 1.3},0`} />;
    case "SERVICE":
      return <rect {...props} x={-r} y={-r} width={r * 2} height={r * 2} rx={3} />;
    case "FEATURE":
      return <polygon {...props}
                      points={`0,${-r * 1.35} ${r * 1.2},${r * 0.85} ${-r * 1.2},${r * 0.85}`} />;
    case "REPOSITORY": {
      const points = [0, 1, 2, 3, 4, 5].map((i) => {
        const a = (Math.PI / 3) * i - Math.PI / 6;
        return `${(r * 1.15 * Math.cos(a)).toFixed(1)},${(r * 1.15 * Math.sin(a)).toFixed(1)}`;
      }).join(" ");
      return <polygon {...props} points={points} />;
    }
    case "DOMAIN":
      return <rect {...props} x={-r * 1.65} y={-r * 0.72} width={r * 3.3} height={r * 1.44}
                   rx={r * 0.72} />;
    default:
      return <circle {...props} r={r} />;
  }
}

// ------------------------------------------------------------------------------------ the canvas

export function ForceGraph({ rootId, onOpen }: {
  rootId: string;
  /** Called when a reader asks to leave the graph for the record itself. */
  onOpen?: (node: GraphNode) => void;
}) {
  const [nodes, setNodes] = useState<Placed[]>([]);
  const [links, setLinks] = useState<Linked[]>([]);
  const [expanded, setExpanded] = useState<Set<string>>(new Set());
  const [loading, setLoading] = useState<string | null>(rootId);
  const [error, setError] = useState<string | null>(null);
  const [focus, setFocus] = useState<string | null>(null);
  const [hover, setHover] = useState<string | null>(null);
  const [view, setView] = useState({ x: 0, y: 0, k: 1 });
  const [size, setSize] = useState({ width: 960, height: 560 });
  const [, redraw] = useState(0);

  const shell = useRef<HTMLDivElement | null>(null);
  const frame = useRef<SVGSVGElement | null>(null);
  const simulation = useRef<Simulation<Placed, Linked> | null>(null);
  const dragging = useRef<{ id: string; dx: number; dy: number; moved: boolean } | null>(null);
  const panning = useRef<{ x: number; y: number; ox: number; oy: number } | null>(null);

  // The canvas is whatever the container gives it. A fixed viewBox was the reason the graph looked
  // small in a large dialog: it scaled one drawing to fit rather than drawing more of the estate.
  useEffect(() => {
    const element = shell.current;
    if (!element || typeof ResizeObserver === "undefined") return;
    const observer = new ResizeObserver((entries) => {
      const box = entries[0]?.contentRect;
      if (box && box.width > 40 && box.height > 40) {
        setSize({ width: Math.round(box.width), height: Math.round(box.height) });
      }
    });
    observer.observe(element);
    return () => observer.disconnect();
  }, []);

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
          Object.assign(existing, incoming);       // facts refresh; position survives
        } else {
          byId.set(incoming.id, {
            ...incoming,
            x: (centre?.x ?? 0) + (Math.random() - 0.5) * 80,
            y: (centre?.y ?? 0) + (Math.random() - 0.5) * 80,
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

  useEffect(() => {
    if (!nodes.length) return;
    const present = new Set(nodes.map((n) => n.id));
    // *** GUARDED, AND THE GUARD IS NOT DECORATION. *** d3's forceLink THROWS on a link whose
    // endpoint is not in the node array, and a throw here unmounts the whole React tree — which
    // presents as a blank tab, because the error boundary is above this component and the dialog
    // portal goes with it. A dropped edge is a visibly incomplete graph; a thrown one is a page
    // somebody has to reload.
    const usable = links.filter((l) => present.has(idOf(l.source)) && present.has(idOf(l.target)));

    const sim = forceSimulation<Placed, Linked>(nodes)
      .force("link", forceLink<Placed, Linked>(usable).id((d) => d.id).distance(110).strength(0.65))
      .force("charge", forceManyBody().strength(-460).distanceMax(620))
      .force("collide", forceCollide<Placed>((d) => radiusOf(d) + 16))
      .force("centre", forceCenter(0, 0).strength(0.05))
      .force("x", forceX(0).strength(0.015))
      .force("y", forceY(0).strength(0.015));

    if (stillness) {
      sim.stop();
      for (let i = 0; i < 260; i += 1) sim.tick();
      redraw((n) => n + 1);
    } else {
      sim.alpha(0.9).alphaDecay(0.028).on("tick", () => redraw((n) => n + 1));
    }
    simulation.current = sim;
    return () => { sim.stop(); simulation.current = null; };
  }, [nodes, links, stillness]);

  // ------------------------------------------------------------------------------ pan, zoom, drag

  const toGraph = useCallback((event: { clientX: number; clientY: number }) => {
    const svg = frame.current;
    if (!svg) return { x: 0, y: 0 };
    const box = svg.getBoundingClientRect();
    const scale = size.width / (box.width || size.width);
    return {
      x: ((event.clientX - box.left) * scale - size.width / 2 - view.x) / view.k,
      y: ((event.clientY - box.top) * scale - size.height / 2 - view.y) / view.k,
    };
  }, [size, view]);

  const onPointerDown = (event: React.PointerEvent<SVGSVGElement>) => {
    if (dragging.current) return;
    panning.current = { x: event.clientX, y: event.clientY, ox: view.x, oy: view.y };
  };
  const onPointerMove = (event: React.PointerEvent<SVGSVGElement>) => {
    if (dragging.current) {
      const point = toGraph(event);
      const node = nodes.find((n) => n.id === dragging.current!.id);
      if (node) {
        dragging.current.moved = true;
        node.fx = point.x - dragging.current.dx;
        node.fy = point.y - dragging.current.dy;
        simulation.current?.alpha(0.3).restart();
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
      // Released rather than pinned: a node left where it was dropped turns the layout into a
      // hand-drawn diagram nobody maintains.
      if (node) { node.fx = null; node.fy = null; }
      dragging.current = null;
      simulation.current?.alpha(0.2).restart();
    }
    panning.current = null;
  };
  const zoom = (factor: number) =>
    setView((v) => ({ ...v, k: Math.min(3, Math.max(0.3, v.k * factor)) }));

  // -------------------------------------------------------------------------------- the keyboard

  const neighboursOf = useCallback((id: string) => {
    const out = new Set<string>();
    for (const link of links) {
      if (idOf(link.source) === id) out.add(idOf(link.target));
      else if (idOf(link.target) === id) out.add(idOf(link.source));
    }
    return out;
  }, [links]);

  const move = (from: string, dx: number, dy: number) => {
    const origin = nodes.find((n) => n.id === from);
    if (!origin) return;
    let best: { id: string; score: number } | null = null;
    for (const id of neighboursOf(from)) {
      const node = nodes.find((n) => n.id === id);
      if (!node) continue;
      const vx = (node.x ?? 0) - (origin.x ?? 0);
      const vy = (node.y ?? 0) - (origin.y ?? 0);
      const length = Math.hypot(vx, vy) || 1;
      const alignment = (vx * dx + vy * dy) / length;
      if (alignment > 0.3 && (!best || alignment > best.score)) best = { id, score: alignment };
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

  const lit = hover ?? focus;
  const adjacent = useMemo(() => (lit ? neighboursOf(lit) : null), [lit, neighboursOf]);
  const isLit = (id: string) => !adjacent || id === lit || adjacent.has(id);

  if (error) {
    return (
      <div className="grid h-full place-items-center rounded-md border border-dashed p-8 text-sm">
        <div className="text-center">
          <p className="text-destructive">{error}</p>
          <Button size="sm" variant="ghost" className="mt-2" onClick={() => fetchAround(rootId)}>
            <RotateCcw className="size-3" /> Try again
          </Button>
        </div>
      </div>
    );
  }

  const transform =
    `translate(${size.width / 2 + view.x} ${size.height / 2 + view.y}) scale(${view.k})`;

  return (
    <div className="flex h-full min-h-0 flex-col gap-2">
      <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-[11px] text-muted-foreground">
        <Legend />
        <span className="flex-1" />
        {loading && (
          <span className="flex items-center gap-1">
            <Loader2 className="size-3 animate-spin" /> loading
          </span>
        )}
        <span className="tabular">{nodes.length} nodes · {links.length} edges</span>
        <span className="flex items-center gap-0.5">
          <Button size="sm" variant="ghost" className="size-6 p-0" aria-label="Zoom out"
                  onClick={() => zoom(1 / 1.25)}><ZoomOut className="size-3.5" /></Button>
          <Button size="sm" variant="ghost" className="size-6 p-0" aria-label="Zoom in"
                  onClick={() => zoom(1.25)}><ZoomIn className="size-3.5" /></Button>
          <Button size="sm" variant="ghost" aria-label="Recentre"
                  onClick={() => setView({ x: 0, y: 0, k: 1 })}>
            <Maximize2 className="size-3" /> Recentre
          </Button>
        </span>
      </div>

      <div ref={shell} className="relative min-h-0 flex-1 overflow-hidden rounded-lg border
                                  bg-[radial-gradient(circle_at_50%_40%,var(--color-muted),var(--color-card))]">
        <svg ref={frame} viewBox={`0 0 ${size.width} ${size.height}`}
             width="100%" height="100%"
             className="block cursor-grab touch-none select-none active:cursor-grabbing"
             role="application"
             aria-label="Estate graph. Tab moves between nodes, the arrow keys move to a connected
                         node, and enter expands or opens one."
             onPointerDown={onPointerDown} onPointerMove={onPointerMove}
             onPointerUp={endPointer} onPointerLeave={endPointer}
             onPointerCancel={endPointer}
             onWheel={(event) => { event.preventDefault(); zoom(event.deltaY < 0 ? 1.1 : 0.9); }}>
          <defs>
            {/* Depth without a gradient on every node: one shadow, referenced. */}
            <filter id="graph-lift" x="-40%" y="-40%" width="180%" height="180%">
              <feDropShadow dx="0" dy="1" stdDeviation="1.6" floodOpacity="0.18" />
            </filter>
            {["contains", "owns"].map((kind) => (
              <marker key={kind} id={`graph-arrow-${kind}`} viewBox="0 0 8 8" refX="7" refY="4"
                      markerWidth="6" markerHeight="6" orient="auto-start-reverse">
                <path d="M0,1 L7,4 L0,7 z"
                      className={kind === "owns" ? "fill-muted-foreground/50"
                        : "fill-muted-foreground/80"} />
              </marker>
            ))}
          </defs>

          <g transform={transform}>
            {links.map((link, index) => {
              const source = typeof link.source === "object" ? link.source : null;
              const target = typeof link.target === "object" ? link.target : null;
              if (!source || !target) return null;
              const accountability = ACCOUNTABILITY.has(link.kind);
              const shown = isLit(source.id) && isLit(target.id);
              // A gentle curve rather than a straight line: two nodes with edges both ways stop
              // overlapping, and a dense cluster reads as strands instead of a single grey mass.
              const mx = ((source.x ?? 0) + (target.x ?? 0)) / 2;
              const my = ((source.y ?? 0) + (target.y ?? 0)) / 2;
              const nx = -((target.y ?? 0) - (source.y ?? 0)) * 0.12;
              const ny = ((target.x ?? 0) - (source.x ?? 0)) * 0.12;
              return (
                <path key={`${source.id}-${target.id}-${link.kind}-${index}`}
                      d={`M${source.x},${source.y} Q${mx + nx},${my + ny} ${target.x},${target.y}`}
                      fill="none"
                      className={accountability
                        ? "stroke-muted-foreground/45" : "stroke-muted-foreground/70"}
                      strokeWidth={accountability ? 1.1 : 1.5}
                      strokeDasharray={accountability ? "5 4" : undefined}
                      markerEnd={`url(#graph-arrow-${accountability ? "owns" : "contains"})`}
                      style={{ opacity: shown ? 1 : 0.1, transition: "opacity 140ms" }} />
              );
            })}

            {nodes.map((node) => {
              const r = radiusOf(node);
              const isRoot = node.id === rootId;
              const isFocus = focus === node.id;
              const unopened = node.expandable && !expanded.has(node.id);
              const tone = risk(node);
              const shown = isLit(node.id);
              return (
                <g key={node.id} data-node={node.id} tabIndex={0} role="button"
                   transform={`translate(${node.x ?? 0} ${node.y ?? 0})`}
                   className="cursor-pointer outline-none"
                   style={{ opacity: shown ? 1 : 0.22, transition: "opacity 140ms" }}
                   aria-label={describe(node, unopened, tone.label)}
                   onKeyDown={(event) => onNodeKeyDown(event, node)}
                   onFocus={() => setFocus(node.id)}
                   onPointerEnter={() => setHover(node.id)}
                   onPointerLeave={() => setHover(null)}
                   onPointerDown={(event) => {
                     event.stopPropagation();
                     const point = toGraph(event);
                     dragging.current = {
                       id: node.id, dx: point.x - (node.x ?? 0), dy: point.y - (node.y ?? 0),
                       moved: false,
                     };
                   }}
                   onClick={(event) => {
                     event.stopPropagation();
                     // A drag is not a click. Without this, moving a node also expanded it.
                     if (dragging.current?.moved) return;
                     if (unopened) fetchAround(node.id); else onOpen?.(node);
                   }}>
                  {/* The risk halo. Outside the shape so it reads at a glance from across the
                      canvas, where a 1px stroke does not. */}
                  <Outline node={node} scale={1.42} className={`${tone.halo} stroke-none`} />
                  {(isFocus || isRoot) && (
                    <Outline node={node} scale={1.72} className="fill-none stroke-primary/70"
                             style={{ strokeWidth: isRoot ? 1.6 : 1.2,
                                      strokeDasharray: isRoot ? undefined : "3 3" }} />
                  )}
                  <Outline node={node} className={`fill-card ${tone.ring}`}
                           style={{ strokeWidth: isRoot ? 2.4 : 1.7, filter: "url(#graph-lift)" }} />

                  {/* The scope boundary: a dashed stub, never a count. */}
                  {node.boundary && (
                    <line x1={r * 1.15} y1={-r * 1.15} x2={r * 2.1} y2={-r * 2.1}
                          className="stroke-muted-foreground" strokeWidth={1.3}
                          strokeDasharray="2 2" />
                  )}

                  {/* The affordance for an unopened branch: a plus, not a colour. */}
                  {unopened && (
                    <g className="fill-card stroke-muted-foreground" strokeWidth={1}>
                      <circle cx={0} cy={r + 12} r={6.5} />
                      <line x1={-3} y1={r + 12} x2={3} y2={r + 12} className="stroke-foreground"
                            strokeWidth={1.4} />
                      <line x1={0} y1={r + 9} x2={0} y2={r + 15} className="stroke-foreground"
                            strokeWidth={1.4} />
                    </g>
                  )}

                  {/* The count, on the node, where the eye already is. Absent when nothing has been
                      measured — a zero would read as "measured, and clean". */}
                  {(node.findingOpen ?? 0) > 0 && (
                    <g transform={`translate(${r * 1.05} ${-r * 1.05})`}>
                      <circle r={8} className={tone.chip} />
                      <text textAnchor="middle" dy="3"
                            className="pointer-events-none fill-card text-[8px] font-semibold">
                        {node.findingOpen! > 99 ? "99+" : node.findingOpen}
                      </text>
                    </g>
                  )}

                  <text y={-r - 11} textAnchor="middle"
                        className="pointer-events-none fill-foreground text-[10.5px] font-medium"
                        style={{ paintOrder: "stroke", stroke: "var(--color-card)",
                                 strokeWidth: 3, strokeLinejoin: "round" }}>
                    {node.name.length > 30 ? `${node.name.slice(0, 29)}…` : node.name}
                  </text>
                  <text y={r + (unopened ? 26 : 18)} textAnchor="middle"
                        className="pointer-events-none fill-muted-foreground text-[9px]"
                        style={{ paintOrder: "stroke", stroke: "var(--color-card)",
                                 strokeWidth: 2.5, strokeLinejoin: "round" }}>
                    {node.kind === "ORG" ? node.typeCode.toLowerCase().replace(/_/g, " ")
                      : node.findingOpen === null ? "not measured" : node.typeCode.toLowerCase()}
                  </text>
                </g>
              );
            })}
          </g>
        </svg>
      </div>

      <Selected node={nodes.find((n) => n.id === (focus ?? rootId))} onOpen={onOpen} />
    </div>
  );
}

// ------------------------------------------------------------------------------------- fragments

const SHAPES: [string, Pick<GraphNode, "kind" | "typeCode">][] = [
  ["organization", { kind: "ORG", typeCode: "ORG" }],
  ["application", { kind: "ASSET", typeCode: "APPLICATION" }],
  ["project", { kind: "ASSET", typeCode: "PROJECT" }],
  ["service", { kind: "ASSET", typeCode: "SERVICE" }],
  ["repository", { kind: "ASSET", typeCode: "REPOSITORY" }],
  ["domain", { kind: "ASSET", typeCode: "DOMAIN" }],
];

function Legend() {
  return (
    <span className="flex flex-wrap items-center gap-x-3 gap-y-1">
      {SHAPES.map(([label, shape]) => (
        <span key={label} className="flex items-center gap-1">
          <svg width="15" height="15" viewBox="-11 -11 22 22" aria-hidden="true">
            <Outline node={shape} scale={0.5}
                     className="fill-card stroke-muted-foreground" style={{ strokeWidth: 1.4 }} />
          </svg>
          {label}
        </span>
      ))}
      <span className="mx-1 h-3 w-px bg-border" />
      {([["critical", "fill-sev-critical/25 stroke-sev-critical"],
         ["open", "fill-sev-medium/25 stroke-sev-medium"],
         ["clear", "fill-tone-ok/25 stroke-tone-ok"],
         ["not measured", "fill-tone-unknown/25 stroke-tone-unknown"]] as const).map(
        ([label, classes]) => (
          <span key={label} className="flex items-center gap-1">
            <svg width="11" height="11" viewBox="-6 -6 12 12" aria-hidden="true">
              <circle r="5" className={classes} strokeWidth="1.4" />
            </svg>
            {label}
          </span>
        ))}
      <span className="mx-1 h-3 w-px bg-border" />
      <span className="flex items-center gap-1">
        <svg width="16" height="10" viewBox="0 0 16 10" aria-hidden="true">
          <line x1="0" y1="5" x2="16" y2="5" className="stroke-muted-foreground/45"
                strokeWidth="1.1" strokeDasharray="5 4" />
        </svg>
        owns
      </span>
      <span className="flex items-center gap-1">
        <svg width="16" height="10" viewBox="0 0 16 10" aria-hidden="true">
          <line x1="0" y1="5" x2="16" y2="5" className="stroke-muted-foreground/70" strokeWidth="1.5" />
        </svg>
        contains
      </span>
      <span className="flex items-center gap-1">
        <svg width="14" height="14" viewBox="-7 -7 14 14" aria-hidden="true">
          <line x1="-4" y1="4" x2="4" y2="-4" className="stroke-muted-foreground" strokeWidth="1.3"
                strokeDasharray="2 2" />
        </svg>
        beyond your scope
      </span>
    </span>
  );
}

function Selected({ node, onOpen }: { node?: GraphNode; onOpen?: (n: GraphNode) => void }) {
  if (!node) return null;
  return (
    <div className="flex flex-wrap items-center gap-2 rounded-md border bg-muted/40 px-3 py-2 text-xs">
      <span className="font-medium">{node.name}</span>
      <Badge tone="neutral">
        {node.kind === "ORG" ? node.typeCode.toLowerCase().replace(/_/g, " ")
          : node.typeCode.toLowerCase()}
      </Badge>
      {node.exposureDeclared && (
        <Badge tone="info">{node.exposureDeclared.toLowerCase().replace(/_/g, " ")}</Badge>
      )}
      {node.criticalityCode && <Badge tone="warn">{node.criticalityCode}</Badge>}
      {node.findingOpen === null
        ? <Badge tone="unknown">nothing measured</Badge>
        : (node.criticalOpen ?? 0) > 0
          ? <Badge tone="critical">{node.criticalOpen} critical of {node.findingOpen} open</Badge>
          : <Badge tone={node.findingOpen > 0 ? "medium" : "ok"}>{node.findingOpen} open</Badge>}
      {node.boundary && (
        <span className="text-muted-foreground">· connects beyond your scope</span>
      )}
      <span className="flex-1" />
      {onOpen && <Button size="sm" variant="ghost" onClick={() => onOpen(node)}>Open record</Button>}
    </div>
  );
}

function describe(node: GraphNode, unopened: boolean, risky: string): string {
  const kind = node.kind === "ORG" ? "organization node" : node.typeCode.toLowerCase();
  const findings = node.findingOpen === null ? "nothing measured"
    : `${node.findingOpen} open finding${node.findingOpen === 1 ? "" : "s"}`;
  return [node.name, kind, findings, risky === findings ? null : risky,
    node.boundary ? "connects beyond your scope" : null,
    unopened ? "press enter to expand" : "press enter to open the record"]
    .filter(Boolean).join(", ");
}

function idOf(end: string | Placed | number | undefined): string {
  return typeof end === "object" && end !== null ? end.id : String(end);
}
