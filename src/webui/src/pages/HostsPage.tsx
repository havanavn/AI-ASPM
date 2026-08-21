import { useEffect, useMemo, useState } from "react";
import { Link, useSearchParams } from "react-router-dom";
import { Globe, Search } from "lucide-react";
import { api } from "@/lib/api";
import { humanise } from "@/components/AttributeFields";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";

interface Hit {
  host: string; exposure: string | null;
  assetId: string; assetName: string; assetTypeCode: string;
  environment: string;
  owningNodeName: string | null; ownerAncestors: string[];
  applicationId: string | null; applicationName: string | null;
}
interface Payload { rows: Hit[]; query: string; capped: boolean }

/** Where a hit sends you: the project or service itself, or the application when that is the asset. */
function linkFor(hit: Hit): string | null {
  if (hit.assetTypeCode === "PROJECT") return `/projects/${hit.assetId}`;
  if (hit.assetTypeCode === "APPLICATION") return `/applications/${hit.assetId}`;
  return hit.applicationId ? `/applications/${hit.applicationId}` : null;
}

/**
 * Host lookup — whose is this hostname, and what is it part of.
 *
 * **This is the question an incident starts with**, and until this page existed the platform could
 * not answer it. A domain is an asset on the far end of a `PUBLISHED_ON` edge, not a column on
 * anything, so no name search on any list could reach one and no page listed them. An inventory that
 * cannot be queried by the identifier the outside world uses — the hostname in the alert, the subject
 * on the certificate, the Host header in the log line — is an inventory nobody opens during the hour
 * it matters.
 *
 * **Matched as a substring.** People paste URLs and fragments, not the exact stored form; requiring
 * equality would answer "not found" for a host that is recorded, which is the worst answer this page
 * could give. Searching a parent domain therefore returns every subdomain under it, which is the
 * other half of what it gets used for.
 *
 * **One host can appear several times, and that is the answer, not a duplicate.** The same name
 * serving production for one project and UAT for another is two edges and two rows, because they are
 * two different facts and collapsing them would hide one of them.
 */
export function HostsPage() {
  const [params, setParams] = useSearchParams();
  const [q, setQ] = useState(params.get("q") ?? "");
  const [data, setData] = useState<Payload | null>(null);
  const [error, setError] = useState<string | null>(null);
  const query = params.get("q") ?? "";

  useEffect(() => {
    if (!query) { setData(null); return; }
    let live = true;
    setError(null);
    api.get<Payload>(`/api/ui/hosts?q=${encodeURIComponent(query)}`)
      .then((d) => live && setData(d))
      .catch((e) => live && setError(e.message));
    return () => { live = false; };
  }, [query]);

  // Grouped by host, so a name serving three things reads as one host with three attachments rather
  // than as three unrelated rows that happen to share a spelling.
  const grouped = useMemo(() => {
    const out = new Map<string, Hit[]>();
    for (const hit of data?.rows ?? []) {
      const bucket = out.get(hit.host);
      if (bucket) bucket.push(hit); else out.set(hit.host, [hit]);
    }
    return [...out.entries()];
  }, [data]);

  function search(value: string) {
    const next = new URLSearchParams();
    if (value.trim()) next.set("q", value.trim());
    setParams(next, { replace: true });
  }

  return (
    <div className="flex flex-col gap-5">
      <div>
        <h1 className="text-lg font-semibold tracking-tight">Host lookup</h1>
        <p className="text-xs text-muted-foreground">
          A hostname in, whose it is out. Matched anywhere in the name, so a parent domain returns
          everything recorded beneath it.
        </p>
      </div>

      <Card>
        <CardContent className="flex flex-wrap items-end gap-3">
          <div className="flex min-w-72 flex-1 flex-col gap-1">
            <div className="relative">
              <Search className="pointer-events-none absolute left-2.5 top-2.5 size-4 text-muted-foreground" />
              <Input autoFocus value={q} className="pl-8"
                     placeholder="uat-pay.example.vn, or just example.vn"
                     onChange={(e) => setQ(e.target.value)}
                     onKeyDown={(e) => e.key === "Enter" && search(q)} />
            </div>
          </div>
          <Button size="sm" onClick={() => search(q)}>Look up</Button>
        </CardContent>
      </Card>

      {error && <Card><CardContent className="text-sm text-destructive">{error}</CardContent></Card>}

      {!query && (
        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2">
              <Globe className="size-4" /> What this searches
            </CardTitle>
            <CardDescription>
              Domains recorded against an application, a project or a service — the production and
              UAT hosts on a project record, and anything an importer has attached. A host nobody has
              recorded cannot be found here, and that absence is itself worth knowing: the platform
              does not discover hosts, it holds the ones somebody entered.
            </CardDescription>
          </CardHeader>
        </Card>
      )}

      {query && data && grouped.length === 0 && (
        <Card><CardContent className="py-10 text-center text-sm text-muted-foreground">
          {/* Not "no results". The distinction matters here more than almost anywhere else on the
              platform: a host that is genuinely unrecorded is shadow IT, and reading that as "the
              search is broken" loses the finding. */}
          Nothing recorded matches <strong>{query}</strong>. Either this host belongs to nothing in
          the inventory — which is worth pursuing on its own — or it is recorded under a different
          spelling. Try a shorter fragment.
        </CardContent></Card>
      )}

      {grouped.map(([host, hits]) => (
        <Card key={host} className="overflow-hidden">
          <CardHeader className="pb-3">
            <CardTitle className="font-mono text-sm">{host}</CardTitle>
            <CardDescription>
              {hits.length === 1
                ? "Attached to one asset."
                : `Attached to ${hits.length} assets — each row is a separate fact, not a duplicate.`}
              {hits[0]?.exposure && (
                <> Exposure recorded on the host: <Badge>{humanise(hits[0].exposure)}</Badge></>
              )}
            </CardDescription>
          </CardHeader>
          <div className="overflow-x-auto">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Attached to</TableHead>
                  <TableHead>Kind</TableHead>
                  <TableHead>Environment</TableHead>
                  <TableHead>Application</TableHead>
                  <TableHead>Organization</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {hits.map((hit) => {
                  const href = linkFor(hit);
                  return (
                    <TableRow key={`${hit.assetId}-${hit.environment}`}>
                      <TableCell className="text-sm font-medium">
                        {href
                          ? <Link to={href} className="text-primary hover:underline">{hit.assetName}</Link>
                          : hit.assetName}
                      </TableCell>
                      <TableCell><Badge>{hit.assetTypeCode}</Badge></TableCell>
                      <TableCell>
                        {hit.environment === "UNSPECIFIED"
                          // A blank here reads as production to most people, and being wrong about
                          // that during an incident is the expensive direction.
                          ? <Badge tone="unknown">not stated</Badge>
                          : <Badge tone={hit.environment === "PRODUCTION" ? "critical" : "neutral"}>
                              {humanise(hit.environment)}
                            </Badge>}
                      </TableCell>
                      <TableCell className="text-xs">
                        {hit.applicationId
                          ? <Link to={`/applications/${hit.applicationId}`}
                                  className="text-primary hover:underline">{hit.applicationName}</Link>
                          : hit.assetTypeCode === "APPLICATION"
                            ? <span className="text-muted-foreground">this is the application</span>
                            : <span className="italic text-tone-unknown">under no application</span>}
                      </TableCell>
                      <TableCell className="text-xs text-muted-foreground">
                        {hit.ownerAncestors.length > 0 && (
                          <span>{hit.ownerAncestors.join(" › ")} › </span>
                        )}
                        {hit.owningNodeName ?? <span className="italic">unowned</span>}
                      </TableCell>
                    </TableRow>
                  );
                })}
              </TableBody>
            </Table>
          </div>
        </Card>
      ))}

      {data?.capped && (
        <p className="text-[11px] text-muted-foreground">
          Showing the first 200 attachments. Narrow the search — this list is capped and is not
          telling you it found only this many.
        </p>
      )}
    </div>
  );
}
