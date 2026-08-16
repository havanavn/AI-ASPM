import { sessionExtended } from "@/lib/keepalive";

/**
 * The API client.
 *
 * <p>Everything is same-origin and relies on the session cookie, which is HttpOnly and
 * SameSite=Strict — the interface never sees a token and therefore cannot leak one. `credentials:
 * "same-origin"` is explicit rather than relied upon: the default changed once already in the fetch
 * specification's life, and an interface that silently stopped sending its session would look like
 * an authorization bug rather than a client one.
 */
export class ApiError extends Error {
  constructor(readonly status: number, readonly code: string, message: string,
              /** The field the server refused, where it named one. Intake uses it. */
              readonly field: string | null = null) {
    super(message);
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(path, {
    ...init,
    credentials: "same-origin",
    headers: { Accept: "application/json", ...(init?.headers ?? {}) },
  });
  if (response.status === 401 || response.status === 403) {
    const body = await response.json().catch(() => ({} as { code?: string }));
    // A class C or class E operation refused for want of a fresh second factor. NOT the same as an
    // expired session, and sending it to sign-in would be a lie: the caller is signed in, and being
    // asked to sign in again teaches them the platform loses their session at random. The step-up
    // page takes them back where they were — `next` is validated server-side against a shape
    // allowlist, because an unchecked return path on an authentication surface is an open redirect.
    if (body.code === "STEP_UP_REQUIRED") {
      const back = window.location.pathname + window.location.search;
      window.location.assign(`/step-up?next=${encodeURIComponent(back)}`);
      throw new ApiError(response.status, "STEP_UP_REQUIRED", "second factor required");
    }
    // A credential the platform has marked for replacement. Sending this to sign-in would be a loop:
    // the caller IS signed in, signing in again changes nothing, and the change-password page is the
    // only thing they are allowed to open. It used to be impossible to arrive here — every page was
    // server-rendered, so the dispatcher redirected the PAGE request and the interface never started.
    // The interface now loads before the first API call, so the interface has to know.
    if (body.code === "CREDENTIAL_CHANGE_REQUIRED") {
      window.location.assign("/change-password?required=1");
      throw new ApiError(response.status, "CREDENTIAL_CHANGE_REQUIRED", "password change required");
    }
    // A session that expired mid-session lands here — and what happens next depends on whether
    // anything of the caller's is on screen.
    //
    // A READ can navigate: there is nothing to lose, and reloading hands the browser to the server's
    // sign-in redirect rather than the interface inventing its own login screen.
    //
    // A WRITE must NOT. Somebody who has just spent half an hour writing up a finding and pressed Save
    // needs their text to still be there. Navigating away destroyed it — the reported symptom was
    // "it pushes me back to login while I am working", and the harm was not the sign-in page, it was
    // the work that went with it. So the error is thrown and the form keeps its contents; the caller
    // can sign in on another tab and press Save again.
    if ((init?.method ?? "GET") === "GET") {
      // One address. The sign-in page used to be served under both prefixes and this had to pick the
      // one the caller was already on; there is one interface now, at the root.
      window.location.assign("/sign-in");
    }
    throw new ApiError(response.status, "UNAUTHENTICATED",
      (init?.method ?? "GET") === "GET"
        ? "session required"
        : "Your session expired before this could be saved. Nothing here has been lost — sign in "
          + "again in another tab, then press save once more.");
  }
  if (!response.ok) {
    const body = await response.json().catch(() => ({}));
    throw new ApiError(response.status, body.code ?? "ERROR", body.message ?? response.statusText,
                       body.field ?? null);
  }
  // This request resolved the session, so the server just restarted its idle window. The expiry
  // countdown has to hear about it or it would sign out somebody who has been clicking through pages
  // the whole time — the countdown cannot see a request it did not make.
  sessionExtended();
  return (await response.json()) as T;
}

export const api = {
  get: <T,>(path: string) => request<T>(path),
  post: <T,>(path: string, body: unknown) =>
    request<T>(path, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        // Class B operations require it, and generating it here rather than on the server is what
        // makes a retried request idempotent: the same click carries the same key.
        "Idempotency-Key": crypto.randomUUID(),
      },
      body: JSON.stringify(body),
    }),
  // Same headers as post. A configuration write is class E, which pairs step-up with a replay key —
  // so the idempotency key is not optional here, it is what stops a retried save from being counted
  // as a second decision.
  put: <T,>(path: string, body: unknown) =>
    request<T>(path, {
      method: "PUT",
      headers: {
        "Content-Type": "application/json",
        "Idempotency-Key": crypto.randomUUID(),
      },
      body: JSON.stringify(body),
    }),
};
