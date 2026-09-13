import { useCallback, useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { Bell, Check } from "lucide-react";
import { api } from "@/lib/api";
import { Button } from "@/components/ui/button";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";

/**
 * The in-product channel (PRD-NTF-018): always on, inside the authorization boundary, and the place a
 * person can rely on even when every external channel is muted or failing (PRD-NTF-045).
 *
 * Polled, not pushed: a poll every minute is one request against a table indexed for it, and it
 * survives every proxy. The count is unread rows; marking read is per row or all at once.
 */

interface Row {
  id: string; category: string; eventKind: string; title: string; body: string | null; link: string | null;
  mandatory: boolean; mergedCount: number; updatedAt: string; readAt: string | null;
}

export function NotificationBell() {
  const [rows, setRows] = useState<Row[]>([]);
  const [unread, setUnread] = useState(0);
  const [open, setOpen] = useState(false);

  const load = useCallback(() => {
    api.get<{ rows: Row[]; unread: number }>("/api/ui/notifications")
      .then((d) => { setRows(d.rows); setUnread(d.unread); })
      .catch(() => { /* an unauthenticated poll is a redirect; nothing to show */ });
  }, []);

  useEffect(() => {
    load();
    const timer = window.setInterval(load, 60_000);
    return () => window.clearInterval(timer);
  }, [load]);

  async function markRead(id: string) {
    await api.post(`/api/ui/notifications/${id}/read`, {});
    load();
  }

  return (
    <Popover open={open} onOpenChange={(o) => { setOpen(o); if (o) load(); }}>
      <PopoverTrigger asChild>
        <Button variant="ghost" size="icon" aria-label={unread > 0 ? `${unread} unread notifications` : "Notifications"} className="relative">
          <Bell />
          {unread > 0 && (
            <span className="absolute -right-0.5 -top-0.5 grid min-w-4 place-items-center rounded-full bg-primary px-1 text-[10px] font-semibold text-primary-foreground">
              {unread > 99 ? "99+" : unread}
            </span>
          )}
        </Button>
      </PopoverTrigger>
      <PopoverContent align="end" className="w-96 p-0">
        <div className="flex items-center justify-between border-b px-3 py-2">
          <span className="text-sm font-medium">Notifications</span>
          {unread > 0 && (
            <Button size="sm" variant="ghost" onClick={() => markRead("all")}>
              <Check className="size-3" /> Mark all read
            </Button>
          )}
        </div>
        <ul className="max-h-96 overflow-y-auto">
          {rows.length === 0 && <li className="px-3 py-6 text-center text-xs text-muted-foreground">Nothing yet.</li>}
          {rows.map((r) => (
            <li key={r.id} className={"border-b px-3 py-2 text-sm last:border-b-0 " + (r.readAt ? "opacity-70" : "bg-primary/5")}>
              <div className="flex items-start justify-between gap-2">
                <div className="min-w-0">
                  {r.link ? (
                    <Link to={r.link} className="block truncate font-medium hover:underline" onClick={() => { markRead(r.id); setOpen(false); }}>
                      {r.title}
                    </Link>
                  ) : <span className="block truncate font-medium">{r.title}</span>}
                  {r.body && <p className="text-xs text-muted-foreground">{r.body}</p>}
                  <p className="text-[11px] text-muted-foreground">
                    {r.updatedAt.replace("T", " ").slice(0, 16)}{r.mergedCount > 1 ? ` · ${r.mergedCount} updates` : ""}{r.mandatory ? " · mandatory" : ""}
                  </p>
                </div>
                {!r.readAt && (
                  <Button size="sm" variant="ghost" aria-label="Mark read" onClick={() => markRead(r.id)}><Check className="size-3" /></Button>
                )}
              </div>
            </li>
          ))}
        </ul>
        <div className="border-t px-3 py-2 text-right">
          <Link to="/account" className="text-xs text-primary hover:underline" onClick={() => setOpen(false)}>Notification preferences</Link>
        </div>
      </PopoverContent>
    </Popover>
  );
}
