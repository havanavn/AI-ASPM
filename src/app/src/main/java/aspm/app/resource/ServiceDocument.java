package aspm.app.resource;

import aspm.app.runtime.Dispatcher;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code GET /} — the service document. Class G, unauthenticated.
 *
 * <p>It exists because the alternative was worse in a specific way: with no route at {@code /}, opening
 * the platform in a browser returned {@code 404 NOT_FOUND}, which is the application working correctly
 * and reads as the application being down. That is a diagnosability failure, and it cost real time here.
 *
 * <h2>What it deliberately does not say</h2>
 *
 * <p>It does <b>not</b> enumerate operations, paths, or permission codes.
 *
 * <p>The dispatcher answers an unregistered path with the same body as a scope denial precisely so the API
 * surface cannot be mapped without authorization ({@code PRD-API-036}). An unauthenticated index listing
 * every operation and the permission it requires would hand back exactly what that rule withholds — and it
 * would do so from the one endpoint written to be friendly, which is how this class of disclosure usually
 * arrives.
 *
 * <p>So the document carries what an unauthenticated caller legitimately needs to proceed: that the
 * service is this platform, which API version it speaks, where to check health, and that authentication
 * is required. Nothing that narrows a guess about what exists behind it.
 */
public final class ServiceDocument {

    private ServiceDocument() {
    }

    public static Dispatcher.Response get(Dispatcher.Request request) {
        // This document used to answer the ROOT, and negotiated on Accept so that a browser typing the
        // host name got the product rather than JSON about it. The interface owns the root now, so the
        // negotiation is no longer what makes the front door work — it is kept because a person who
        // reaches /api in a browser is looking for the product, and the header states what the caller can
        // render where a user-agent list would be wrong for every client not on it.
        //
        // The redirect target does not need to know whether they are signed in: the interface redirects an
        // unauthenticated caller onward to sign-in.
        String accept = request.headers().getOrDefault("accept", "");
        if (accept.contains("text/html")) {
            return new Dispatcher.Response(303, new aspm.app.ui.InterfaceResource.Raw(""),
                    // WebUi.landing(), not a literal, so this cannot drift from wherever the interface
                    // decides its landing page is. A literal here was once the last step of a chain that
                    // ended at the old server-rendered tier however many times the other addresses were
                    // corrected — the root was still pointing at the old front door.
                    Map.of("Location", aspm.app.ui.WebUi.landing(),
                           "Content-Type", "text/html; charset=utf-8"));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("service", "ai-aspm");
        body.put("api_version", "v1");
        body.put("api_root", "/api/v1");
        body.put("health", Map.of("liveness", "/internal/health/live",
                "readiness", "/internal/health/ready"));
        body.put("authentication", "required for every operation under /api/v1");
        // The interface EXISTS. This field said "none — this tier serves JSON only; DOC-08's interface is
        // not built" while the interface was running and sign-in worked, on the one endpoint that answers
        // without authentication — so the most easily reached statement the platform makes about itself was
        // false. It was true when written and nothing brought it forward.
        //
        // The entry point is named and nothing else is: a path a caller can already guess is not a
        // disclosure, and every operation behind it is still withheld under PRD-API-036.
        //
        // The ROOT, not the sign-in page. Naming /sign-in told a caller to start at the authentication
        // form, which is a step the platform takes for them — "/" is the interface, and it redirects to
        // sign-in when there is no session. This read "/sign-in" only because the interface moved to the
        // root and a literal was rewritten with everything else.
        body.put("interface", "/");
        return Dispatcher.Response.ok(body);
    }
}
