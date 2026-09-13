import { useCallback, useEffect, useState } from "react";
import { api } from "@/lib/api";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";

/**
 * A person's own notification preferences (PRD-NTF-020, PRD-NTF-021, PRD-NTF-034).
 *
 * One control reduces everything that is not mandatory; individual categories can be muted; quiet
 * hours defer rather than drop. Mandatory categories are shown and cannot be touched — the server
 * refuses them too, so the checkbox being disabled is a courtesy, not the control.
 */

interface Category { code: string; label: string; mandatory: boolean }
interface Payload {
  muteNonMandatory: boolean; mutedCategories: string[]; quietStartMinute: number | null; quietEndMinute: number | null;
  timezone: string; locale: string; categories: Category[];
}

function minutesToTime(m: number | null): string {
  if (m === null || m === undefined) return "";
  return `${String(Math.floor(m / 60)).padStart(2, "0")}:${String(m % 60).padStart(2, "0")}`;
}
function timeToMinutes(t: string): number | null {
  const m = /^(\d{1,2}):(\d{2})$/.exec(t.trim());
  if (!m) return null;
  const v = Number(m[1]) * 60 + Number(m[2]);
  return v >= 0 && v < 1440 ? v : null;
}

export function NotificationPreferences() {
  const [data, setData] = useState<Payload | null>(null);
  const [mute, setMute] = useState(false);
  const [muted, setMuted] = useState<Set<string>>(new Set());
  const [quietStart, setQuietStart] = useState("");
  const [quietEnd, setQuietEnd] = useState("");
  const [timezone, setTimezone] = useState("UTC");
  const [locale, setLocale] = useState("en");
  const [busy, setBusy] = useState(false);
  const [saved, setSaved] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);

  const load = useCallback(() => {
    api.get<Payload>("/api/ui/account/notification-preferences").then((d) => {
      setData(d);
      setMute(d.muteNonMandatory);
      setMuted(new Set(d.mutedCategories));
      setQuietStart(minutesToTime(d.quietStartMinute));
      setQuietEnd(minutesToTime(d.quietEndMinute));
      setTimezone(d.timezone);
      setLocale(d.locale);
    }).catch((e) => setProblem(e.message));
  }, []);
  useEffect(load, [load]);

  async function save() {
    setBusy(true);
    setProblem(null);
    setSaved(false);
    const start = quietStart ? timeToMinutes(quietStart) : null;
    const end = quietEnd ? timeToMinutes(quietEnd) : null;
    if ((quietStart && start === null) || (quietEnd && end === null)) {
      setProblem("Quiet hours are HH:MM.");
      setBusy(false);
      return;
    }
    try {
      await api.post("/api/ui/account/notification-preferences", {
        muteNonMandatory: mute, mutedCategories: [...muted], quietStartMinute: start, quietEndMinute: end, timezone, locale,
      });
      setSaved(true);
      load();
    } catch (e) {
      setProblem(e instanceof Error ? e.message : String(e));
    } finally { setBusy(false); }
  }

  if (!data) return null;

  return (
    <Card>
      <CardHeader>
        <CardTitle>Notifications</CardTitle>
        <CardDescription>
          What reaches you outside the platform. The bell menu always shows everything; these settings govern email and chat.
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        <label className="flex items-center gap-2 text-sm">
          <Checkbox checked={mute} onCheckedChange={(v) => setMute(v === true)} />
          Mute everything that is not mandatory
        </label>
        <div className="grid gap-2 sm:grid-cols-2 lg:grid-cols-3">
          {data.categories.map((c) => (
            <label key={c.code} className={"flex items-center gap-2 rounded-md border p-2 text-sm " + (c.mandatory ? "opacity-70" : "")}>
              <Checkbox checked={c.mandatory ? false : (mute || muted.has(c.code))} disabled={c.mandatory || mute}
                        onCheckedChange={() => setMuted((was) => {
                          const next = new Set(was);
                          if (!next.delete(c.code)) next.add(c.code);
                          return next;
                        })} />
              <span className="flex-1">Mute {c.label.toLowerCase()}</span>
              {c.mandatory && <Badge tone="warn">mandatory</Badge>}
            </label>
          ))}
        </div>
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          <div className="flex flex-col gap-1">
            <Label>Quiet hours start</Label>
            <Input value={quietStart} placeholder="22:00" onChange={(e) => setQuietStart(e.target.value)} />
          </div>
          <div className="flex flex-col gap-1">
            <Label>Quiet hours end</Label>
            <Input value={quietEnd} placeholder="07:00" onChange={(e) => setQuietEnd(e.target.value)} />
          </div>
          <div className="flex flex-col gap-1">
            <Label>Time zone</Label>
            <Input value={timezone} placeholder="Asia/Ho_Chi_Minh" onChange={(e) => setTimezone(e.target.value)} />
          </div>
          <div className="flex flex-col gap-1">
            <Label>Language of messages</Label>
            <Select value={locale} onValueChange={setLocale}>
              <SelectTrigger><SelectValue /></SelectTrigger>
              <SelectContent>
                <SelectItem value="en">English</SelectItem>
                <SelectItem value="vi">Tiếng Việt</SelectItem>
              </SelectContent>
            </Select>
          </div>
        </div>
        <p className="text-[11px] text-muted-foreground">
          Quiet hours defer non-mandatory messages to the end of the window; they never drop them and never delay a mandatory one.
        </p>
        {problem && <p className="text-sm text-destructive">{problem}</p>}
        <div className="flex items-center gap-3">
          <Button size="sm" disabled={busy} onClick={save}>Save preferences</Button>
          {saved && <span className="text-xs text-tone-ok">Saved.</span>}
        </div>
      </CardContent>
    </Card>
  );
}
