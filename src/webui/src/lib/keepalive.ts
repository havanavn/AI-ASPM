/**
 * Keeps a session alive while somebody is actually using the interface — and ends the screen when it
 * is not.
 *
 * ## Two jobs, and the second one is new
 *
 * **1. Do not sign somebody out mid-sentence.** The idle limit is enforced on `last_seen_at`, which
 * advances only when a request resolves a session. In a single-page interface that is not the same as
 * "somebody is working": writing up a finding, reading a report, filling a form — none of it makes a
 * request. So a person typing for thirty-one minutes was signed out mid-sentence, and the interface
 * then navigated to sign-in, taking the unsaved write-up with it.
 *
 * **2. Do not leave an ended session on screen.** The other half was missing and was reported: when the
 * session actually ended, nothing happened. The page kept showing everything it had already fetched —
 * findings, credentials, organization structure — until somebody navigated or refreshed, and only then
 * did the next request 401 and redirect. The server was never wrong: it served nothing after expiry.
 * What was wrong is that the SCREEN outlived the session, so the idle limit bounded what the platform
 * would answer and not what an unattended monitor would show. That is the risk the limit exists for
 * (`SEC-SEC-010`: sessions accumulating on shared and personal devices), and it was only half enforced.
 *
 * ## How the countdown knows, without becoming a way to never expire
 *
 * The server reports the remaining window on every keepalive. The client holds that as a DEADLINE and
 * counts down locally — no request is made to check, because a request that checked would also touch
 * `last_seen_at` and extend the very window it was checking. The deadline moves only when something
 * genuinely extended the session:
 *
 * - a keepalive ping, which requires real interaction and a visible tab; or
 * - any successful API call the interface made, which touched the session server-side. `api.ts` reports
 *   those through {@link sessionExtended}, so a person clicking around a dashboard is not signed out by
 *   a client clock that did not hear about it.
 *
 * An unattended machine does neither, so the deadline arrives and the interface leaves for sign-in.
 *
 * ## Why the client may end the screen on its own
 *
 * Because ending a SCREEN needs no authority. Navigating to sign-in discloses nothing and grants
 * nothing; the session's real fate is decided by the server on the next request either way. If the
 * clocks disagree and this fires a little early, the cost is one sign-in. If it fired late, the cost is
 * a dashboard left readable on a desk — which is the failure being fixed.
 *
 * The ABSOLUTE limit is not reachable from here. Whatever anybody does, the session ends at
 * `absolute_expires_at` — the twelve-hour product ceiling holds regardless.
 */

/** How often to consider sending a keepalive. Not how often one is sent — see the two conditions. */
const TICK_MS = 5 * 60 * 1000;

/** How often the deadline is checked. Cheap: it compares two numbers and makes no request. */
const WATCH_MS = 10 * 1000;

/** How long before the end to warn. Long enough to finish a sentence and press a button. */
const WARN_SECONDS = 120;

/** Interaction that counts as a person being present. Deliberately not `mousemove`. */
const SIGNALS = ["pointerdown", "keydown", "scroll", "touchstart"] as const;

export interface SessionWindow {
  idleSecondsLeft: number;
  absoluteSecondsLeft: number;
}

/** Where an ended session goes. The same address and the same reason the server uses. */
const SIGN_IN = "/sign-in?expired=1";

/**
 * The idle policy in seconds, learned from the first ping.
 *
 * <p>A ping touches the session and then reads the window, so what it reports is the whole idle window
 * rather than what was left of it. That is what makes it usable as the policy length here.
 */
let idlePolicySeconds = 0;

/** Epoch millis at which this screen stops being allowed to show what it has. */
let deadline = Number.POSITIVE_INFINITY;

/** Epoch millis of the absolute ceiling. Never extended by anything. */
let absoluteDeadline = Number.POSITIVE_INFINITY;

let leaving = false;

/** Leaves for sign-in, once. */
function endSession(): void {
  if (leaving) {
    return;
  }
  leaving = true;
  // assign, not replace: the page the person was on stays in history, so signing in and pressing back
  // returns them to where they were rather than to nothing.
  window.location.assign(SIGN_IN);
}

/**
 * Called by the API client after any successful authenticated response.
 *
 * <p>That response touched `last_seen_at` server-side, so the idle window restarted and the local
 * deadline has to hear about it. Without this the countdown would sign out somebody who was clicking
 * through pages the whole time — the exact failure the keepalive was written to prevent, reintroduced
 * by the fix for the other half.
 */
export function sessionExtended(): void {
  if (idlePolicySeconds > 0) {
    deadline = Math.min(Date.now() + idlePolicySeconds * 1000, absoluteDeadline);
  }
}

/**
 * Tells the interface whether to warn, and just as importantly when to stop.
 *
 * <p>One function so the two answers cannot come from two places and disagree — a banner cleared by one
 * path and raised by another is how a stale warning survives.
 */
function report(onWindow?: (w: SessionWindow | null) => void): void {
  if (!onWindow || deadline === Number.POSITIVE_INFINITY) {
    return;
  }
  const secondsLeft = Math.max(0, Math.round((deadline - Date.now()) / 1000));
  onWindow(secondsLeft <= WARN_SECONDS
      ? { idleSecondsLeft: secondsLeft,
          absoluteSecondsLeft: Math.max(0, Math.round((absoluteDeadline - Date.now()) / 1000)) }
      : null);
}

/**
 * Starts the keepalive and the expiry watch. Returns a function that stops both.
 *
 * @param onWindow called with the remaining window while the session is inside the warning window, and
 *     with {@code null} as soon as it is not. <b>Both directions matter.</b> A first version only ever
 *     reported the countdown, so a warning raised at two minutes stayed on screen after the session was
 *     extended — it announced an ending that was no longer happening, which is worse than not warning
 *     at all: the next real warning would be read as the same stale banner
 */
export function startKeepalive(onWindow?: (w: SessionWindow | null) => void): () => void {
  let interacted = false;
  let stopped = false;

  const note = () => { interacted = true; };
  for (const signal of SIGNALS) {
    window.addEventListener(signal, note, { passive: true, capture: true });
  }

  const ping = async (unconditional: boolean) => {
    if (stopped) {
      return;
    }
    if (!unconditional && (!interacted || document.visibilityState !== "visible")) {
      return;
    }
    // Cleared BEFORE the request, not after. Clearing afterwards would drop any interaction that
    // happened while it was in flight, and the tick after an idle-looking gap is exactly when somebody
    // is most likely to be mid-keystroke.
    interacted = false;
    try {
      const response = await fetch("/api/ui/session/keepalive", {
        credentials: "same-origin",
        headers: { Accept: "application/json" },
      });
      if (response.status === 401 || response.status === 403) {
        // The session is gone and this is the platform saying so. It used to return silently here,
        // which is why an ended session sat on screen: the one component that KNEW said nothing.
        endSession();
        return;
      }
      if (!response.ok) {
        // A server error is not an ended session. Silent, and the deadline stands — a 500 must not
        // sign anybody out, and the next real request will report it where it means something.
        return;
      }
      const body = (await response.json()) as SessionWindow;
      idlePolicySeconds = Math.max(idlePolicySeconds, body.idleSecondsLeft);
      absoluteDeadline = Date.now() + body.absoluteSecondsLeft * 1000;
      deadline = Math.min(Date.now() + body.idleSecondsLeft * 1000, absoluteDeadline);
      // The watch decides whether that is worth showing. Reporting it here as well would raise a
      // warning on a session with eleven hours left, because a ping reports a window and not a worry.
      report(onWindow);
    } catch {
      // Offline, or the server restarted. Neither is an ended session and neither is worth
      // interrupting anybody over, so the deadline stands untouched.
    }
  };

  // One ping at startup, unconditionally, to learn the window. It touches the session — and so did the
  // request that loaded this page a moment ago, so it extends nothing that was not just extended.
  void ping(true);

  const timer = window.setInterval(() => void ping(false), TICK_MS);
  const watch = window.setInterval(() => {
    if (stopped || deadline === Number.POSITIVE_INFINITY) {
      return;
    }
    if (deadline - Date.now() <= 0) {
      endSession();
      return;
    }
    report(onWindow);
  }, WATCH_MS);

  return () => {
    stopped = true;
    window.clearInterval(timer);
    window.clearInterval(watch);
    for (const signal of SIGNALS) {
      window.removeEventListener(signal, note, { capture: true });
    }
  };
}

/** Sends one keepalive now, for a "stay signed in" control. Resolves once the window is refreshed. */
export async function extendNow(): Promise<boolean> {
  try {
    const response = await fetch("/api/ui/session/keepalive", {
      credentials: "same-origin",
      headers: { Accept: "application/json" },
    });
    if (!response.ok) {
      if (response.status === 401 || response.status === 403) {
        endSession();
      }
      return false;
    }
    const body = (await response.json()) as SessionWindow;
    idlePolicySeconds = Math.max(idlePolicySeconds, body.idleSecondsLeft);
    absoluteDeadline = Date.now() + body.absoluteSecondsLeft * 1000;
    deadline = Math.min(Date.now() + body.idleSecondsLeft * 1000, absoluteDeadline);
    return true;
  } catch {
    return false;
  }
}
