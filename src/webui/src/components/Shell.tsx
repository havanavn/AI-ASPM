import { NavLink, Outlet } from "react-router-dom";
import {
  LayoutDashboard, ClipboardList, Boxes, Building2, Package, Users, ShieldCheck,
  KeyRound, UserCircle, FolderGit2, Moon, Sun, CalendarClock, Settings2, Bug,
  GitBranch,
} from "lucide-react";
import { useEffect, useState } from "react";
import { cn } from "@/lib/utils";
import { Button } from "@/components/ui/button";
import { Separator } from "@/components/ui/separator";
import { Logo } from "@/components/Logo";
import { startKeepalive, extendNow } from "@/lib/keepalive";
import type { Session } from "@/lib/types";

/** Keyed by the path the server sends, which is the path the router owns. */
const ICONS: Record<string, typeof LayoutDashboard> = {
  "/overview": LayoutDashboard,
  "/board": ClipboardList,
  "/applications": Boxes,
  "/projects": FolderGit2,
  "/organization": Building2,
  "/components": Package,
  "/workload": Users,
  "/planning": CalendarClock,
  "/vulnerabilities": Bug,
  "/pipeline": GitBranch,
  "/composition": ShieldCheck,
  "/settings": Settings2,
  "/access": KeyRound,
  "/account": UserCircle,
};

export function Shell({ session }: { session: Session }) {
  const [dark, setDark] = useState(() => document.documentElement.classList.contains("dark"));
  useEffect(() => {
    document.documentElement.classList.toggle("dark", dark);
  }, [dark]);

  // Seconds left, once the session is close enough to ending to say so. Null the rest of the time,
  // which is almost always — a banner that is always there is a banner nobody reads.
  const [endingIn, setEndingIn] = useState<number | null>(null);

  // Started once, for as long as the interface is open. It lives on the shell rather than on a page
  // because being signed out mid-work is not a property of any one screen — and the screen where it
  // hurt most, a half-written finding, is the one least likely to make a request on its own.
  //
  // It also owns the other half: when the session ends, this is what takes the screen off the desk.
  // The callback only fires inside the warning window, so `endingIn` is either null or a countdown.
  useEffect(() => startKeepalive((w) => setEndingIn(w === null ? null : w.idleSecondsLeft)), []);

  return (
    <div className="flex min-h-screen">
      {/* The warning, not the obituary. It appears in the last two minutes and offers the one action
          that changes the outcome; without the button it would be an announcement of something the
          reader cannot affect. Pressing it sends a keepalive, which is the same thing their own
          typing would have sent had the platform been able to see it. */}
      {endingIn !== null && (
        <div role="status"
             className="fixed inset-x-0 top-0 z-50 flex items-center justify-center gap-3 bg-amber-500/95 px-4 py-2 text-sm text-black">
          <span>
            Your session ends in {Math.floor(endingIn / 60)}:{String(endingIn % 60).padStart(2, "0")}.
            You will be taken to sign-in, and anything unsaved on this screen will be lost.
          </span>
          <Button size="sm" variant="secondary"
                  onClick={() => { void extendNow().then((ok) => ok && setEndingIn(null)); }}>
            Stay signed in
          </Button>
        </div>
      )}
      <aside className="hidden w-56 shrink-0 flex-col border-r border-sidebar-border bg-sidebar md:flex">
        <div className="flex h-14 items-center gap-2 px-4">
          {/* The product's own mark, not a stock shield. ShieldCheck is still used in the nav to
              mean "software composition", and having the same glyph stand for both the product and
              one of its sections made the brand read as a section. */}
          <Logo className="size-6" />
          <span className="text-sm font-semibold tracking-tight">AI ASPM</span>
        </div>
        <Separator />
        <nav className="flex flex-1 flex-col gap-0.5 p-2">
          {/* The nav comes from the SERVER, already filtered to what this caller may reach. The
              client never decides what to show from a permission list of its own — a menu built in
              the browser is a menu an attacker edits. */}
          {session.nav.map((item) => {
            const Icon = ICONS[item.href] ?? Package;
            return (
              <NavLink
                key={item.href}
                // Used as sent. The client used to strip a /ui prefix, which was a second opinion
                // about routing and went wrong as soon as a rebuilt screen changed its path.
                to={item.href}
                className={({ isActive }) =>
                  cn(
                    "flex items-center gap-2.5 rounded-md px-2.5 py-1.5 text-sm transition-colors",
                    isActive
                      ? "bg-primary/10 font-medium text-primary"
                      : "text-muted-foreground hover:bg-accent hover:text-foreground",
                  )
                }
              >
                <Icon className="size-4 shrink-0" />
                {item.label}
              </NavLink>
            );
          })}
        </nav>
        <Separator />
        {/* The person, then where they work. This showed a raw UUID and the words "2 nodes" — an
            identifier is for the machine, and a count of nodes is a fact about the data model
            rather than about the reader's job. PRD-UIX-011 wants the current scope legible because
            somebody unsure which slice they are looking at misreads every figure on the page. */}
        <div className="flex items-center gap-2 p-3">
          <span className="grid size-7 shrink-0 place-items-center rounded-full bg-primary/10
                           text-[11px] font-semibold text-primary">
            {initials(session.displayName)}
          </span>
          <span className="min-w-0">
            <span className="block truncate text-xs font-medium">{session.displayName}</span>
            <span className="block truncate text-[11px] text-muted-foreground"
                  title={session.scopeLabel ?? undefined}>
              {session.scopeLabel ?? "No organization in scope"}
            </span>
          </span>
        </div>
      </aside>

      <div className="flex min-w-0 flex-1 flex-col">
        <header className="flex h-14 shrink-0 items-center justify-between gap-4 border-b px-5">
          <div className="md:hidden text-sm font-semibold">AI ASPM</div>
          <div className="flex-1" />
          <Button variant="ghost" size="icon" onClick={() => setDark((d) => !d)}
                  aria-label={dark ? "Switch to light theme" : "Switch to dark theme"}>
            {dark ? <Sun /> : <Moon />}
          </Button>
        </header>
        <main className="min-w-0 flex-1 p-5"><Outlet /></main>
      </div>
    </div>
  );
}

/** Up to two initials, so the sidebar has an anchor that is not a coloured square. */
function initials(name: string) {
  const parts = name.trim().split(/\s+/).filter(Boolean);
  if (parts.length === 0) return "?";
  if (parts.length === 1) return parts[0]!.slice(0, 2).toUpperCase();
  return (parts[0]![0]! + parts[parts.length - 1]![0]!).toUpperCase();
}
