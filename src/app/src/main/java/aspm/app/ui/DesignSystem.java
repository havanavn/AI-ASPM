package aspm.app.ui;

/**
 * The design system. DOC-08 §2–§5, ADR-006.
 *
 * <p>ADR-006 names the target: "Linear-style density and keyboard-first, Azure-grade information
 * architecture." The two are in tension and the resolution is the point — Linear is dense and shallow,
 * Azure is broad and deep, and an AppSec platform replacing an issue tracker, a spreadsheet and a mailbox
 * needs Azure's breadth rendered at Linear's density.
 *
 * <h2>What the first version got wrong</h2>
 *
 * <p>It was a correctness scaffold: the states, the tokens, the escaping and the accessibility hooks were
 * right and there was no design. A bare table with schema column names is not an interface a security team
 * works in for eight hours, and no amount of correct state handling compensates.
 *
 * <p>What is kept from it is the part that is genuinely hard and genuinely differentiating for this
 * product: colour is never the sole carrier of meaning, an unmeasured value has no numeral form, and every
 * figure carries what it was computed over. A polished interface that renders absent data as zero is worse
 * than an ugly one that does not.
 */
public final class DesignSystem {

    private DesignSystem() {
    }

    public static String css() {
        return TOKENS + RESET + SHELL + COMPONENTS + DATA + CHARTS + STATES + AUTH + ADMIN
                + UTILITIES + generated();
    }

    // ==============================================================================================
    private static final String TOKENS = """
        /* ===== Tokens. DOC-08 §3. Declared per mode; no mode derived from another. ===== */
        :root {
          /* Type. A 1.2 ratio from a 14px base, and a separate monospace scale for identifiers. */
          --fs-11: 0.6875rem; --fs-12: 0.75rem;  --fs-13: 0.8125rem; --fs-14: 0.875rem;
          --fs-16: 1rem;      --fs-20: 1.25rem;  --fs-24: 1.5rem;    --fs-32: 2rem;
          --fw-regular: 400; --fw-medium: 500; --fw-semibold: 600;
          --lh-tight: 1.25; --lh-normal: 1.5;
          --ff-sans: "Inter var", "Inter", -apple-system, BlinkMacSystemFont, "Segoe UI Variable",
                     "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
          --ff-mono: "JetBrains Mono", "SF Mono", ui-monospace, "Cascadia Mono", Menlo, Consolas,
                     monospace;

          /* Space. A 4px base. */
          --sp-1: 4px; --sp-2: 8px;  --sp-3: 12px; --sp-4: 16px;
          --sp-5: 20px; --sp-6: 24px; --sp-8: 32px; --sp-10: 40px; --sp-12: 48px;

          --radius-sm: 4px; --radius-md: 6px; --radius-lg: 10px; --radius-full: 999px;

          /* Layout. */
          --sidebar-w: 248px;
          --sidebar-w-collapsed: 56px;
          --topbar-h: 48px;
          --row-h: 40px;
          --control-h: 32px;
          --target-min: 24px;      /* WCAG 2.2 AA 2.5.8 */
          --content-max: 1600px;

          --dur-fast: 120ms; --dur-base: 180ms;
          --ease: cubic-bezier(0.2, 0, 0.2, 1);
        }

        :root[data-density="compact"] { --row-h: 32px; --control-h: 28px; --sp-3: 8px; --sp-4: 12px; }

        /* ---- Light. ---- */
        :root, :root[data-theme="light"] {
          --bg-canvas:    #f7f8fa;
          --bg-surface:   #ffffff;
          --bg-subtle:    #f2f3f5;
          --bg-inset:     #eceef1;
          --bg-hover:     #f2f3f5;
          --bg-active:    #e8eaee;
          --bg-sidebar:   #ffffff;
          --bg-overlay:   rgba(16, 18, 22, 0.44);

          --fg-default:   #14171c;   /* 15.9:1 on surface */
          --fg-muted:     #4d5560;   /*  7.6:1 */
          --fg-subtle:    #656d78;   /*  5.4:1 */
          --fg-on-accent: #ffffff;

          --border:        #dfe2e7;
          --border-strong: #c3c8d0;
          --border-focus:  #1d4ed8;

          --accent:        #1d4ed8;  /* 6.9:1 on surface */
          --accent-hover:  #163cb0;
          --accent-subtle: #eaefff;

          --sev-critical: #9f1239; --sev-critical-bg: #ffe8ee;
          --sev-high:     #9a3412; --sev-high-bg:     #fff0e6;
          --sev-medium:   #854d0e; --sev-medium-bg:   #fff8e1;
          --sev-low:      #115e59; --sev-low-bg:      #e6f7f5;
          --sev-info:     #3f4756; --sev-info-bg:     #eef0f3;

          --ok:      #15803d; --ok-bg:      #e8f7ed;
          --warn:    #a16207; --warn-bg:    #fff8e1;
          --danger:  #b91c1c; --danger-bg:  #fdeaea;
          --unknown: #656d78; --unknown-bg: #f2f3f5;

          --shadow-sm: 0 1px 2px rgba(16,18,22,.06), 0 1px 3px rgba(16,18,22,.08);
          --shadow-md: 0 4px 12px rgba(16,18,22,.08), 0 1px 3px rgba(16,18,22,.06);
          --shadow-lg: 0 16px 40px rgba(16,18,22,.16), 0 2px 8px rgba(16,18,22,.08);
        }

        /* ---- Dark. Independently chosen; PRD-UIX-006 forbids inversion. ---- */
        :root[data-theme="dark"] {
          --bg-canvas:    #0c0e12;
          --bg-surface:   #14171c;
          --bg-subtle:    #1a1e25;
          --bg-inset:     #0f1216;
          --bg-hover:     #1e232b;
          --bg-active:    #262c36;
          --bg-sidebar:   #101317;
          --bg-overlay:   rgba(0, 0, 0, 0.62);

          --fg-default:   #e9edf2;   /* 14.3:1 on surface */
          --fg-muted:     #a6b0bd;   /*  7.2:1 */
          --fg-subtle:    #7d8795;   /*  4.6:1 */
          --fg-on-accent: #0c0e12;

          --border:        #262c36;
          --border-strong: #384250;
          --border-focus:  #8ab2ff;

          --accent:        #8ab2ff;  /* 8.6:1 on surface */
          --accent-hover:  #a9c6ff;
          --accent-subtle: #16233b;

          --sev-critical: #ff9fb0; --sev-critical-bg: #37131d;
          --sev-high:     #f5b184; --sev-high-bg:     #35190c;
          --sev-medium:   #e8ce7d; --sev-medium-bg:   #2f2609;
          --sev-low:      #7fd9cd; --sev-low-bg:      #0d2b28;
          --sev-info:     #b0bac7; --sev-info-bg:     #1c2129;

          --ok:      #7ddb9c; --ok-bg:      #0e2a19;
          --warn:    #e8c46b; --warn-bg:    #2e2409;
          --danger:  #ff9c9c; --danger-bg:  #351515;
          --unknown: #a6b0bd; --unknown-bg: #1a1e25;

          --shadow-sm: 0 1px 2px rgba(0,0,0,.5);
          --shadow-md: 0 4px 14px rgba(0,0,0,.55);
          --shadow-lg: 0 18px 48px rgba(0,0,0,.7);
        }

        @media (prefers-color-scheme: dark) {
          :root:not([data-theme]) {
            --bg-canvas:#0c0e12; --bg-surface:#14171c; --bg-subtle:#1a1e25; --bg-inset:#0f1216;
            --bg-hover:#1e232b; --bg-active:#262c36; --bg-sidebar:#101317;
            --bg-overlay:rgba(0,0,0,.62);
            --fg-default:#e9edf2; --fg-muted:#a6b0bd; --fg-subtle:#7d8795; --fg-on-accent:#0c0e12;
            --border:#262c36; --border-strong:#384250; --border-focus:#8ab2ff;
            --accent:#8ab2ff; --accent-hover:#a9c6ff; --accent-subtle:#16233b;
            --sev-critical:#ff9fb0; --sev-critical-bg:#37131d;
            --sev-high:#f5b184; --sev-high-bg:#35190c;
            --sev-medium:#e8ce7d; --sev-medium-bg:#2f2609;
            --sev-low:#7fd9cd; --sev-low-bg:#0d2b28;
            --sev-info:#b0bac7; --sev-info-bg:#1c2129;
            --ok:#7ddb9c; --ok-bg:#0e2a19; --warn:#e8c46b; --warn-bg:#2e2409;
            --danger:#ff9c9c; --danger-bg:#351515; --unknown:#a6b0bd; --unknown-bg:#1a1e25;
            --shadow-sm:0 1px 2px rgba(0,0,0,.5); --shadow-md:0 4px 14px rgba(0,0,0,.55);
            --shadow-lg:0 18px 48px rgba(0,0,0,.7);
          }
        }

        /* ---- High contrast. INT-UIX-004. ---- */
        :root[data-theme="hc"] {
          --bg-canvas:#000; --bg-surface:#000; --bg-subtle:#000; --bg-inset:#000;
          --bg-hover:#1a1a1a; --bg-active:#333; --bg-sidebar:#000; --bg-overlay:rgba(0,0,0,.9);
          --fg-default:#fff; --fg-muted:#fff; --fg-subtle:#fff; --fg-on-accent:#000;
          --border:#fff; --border-strong:#fff; --border-focus:#ff0;
          --accent:#ff0; --accent-hover:#ff6; --accent-subtle:#000;
          --sev-critical:#fff; --sev-high:#fff; --sev-medium:#fff; --sev-low:#fff; --sev-info:#fff;
          --sev-critical-bg:#000; --sev-high-bg:#000; --sev-medium-bg:#000; --sev-low-bg:#000;
          --sev-info-bg:#000;
          --ok:#fff; --warn:#fff; --danger:#fff; --unknown:#fff;
          --ok-bg:#000; --warn-bg:#000; --danger-bg:#000; --unknown-bg:#000;
          --shadow-sm:none; --shadow-md:none; --shadow-lg:none;
        }

        /* ---- Print, monochrome. An executive report is printed, and a colour-only
               signal in it is a signal nobody sees. ---- */
        @media print {
          :root {
            --bg-canvas:#fff; --bg-surface:#fff; --bg-subtle:#fff; --bg-inset:#fff;
            --bg-sidebar:#fff; --fg-default:#000; --fg-muted:#000; --fg-subtle:#000;
            --border:#000; --border-strong:#000; --accent:#000;
            --sev-critical:#000; --sev-high:#000; --sev-medium:#000; --sev-low:#000; --sev-info:#000;
            --sev-critical-bg:#fff; --sev-high-bg:#fff; --sev-medium-bg:#fff; --sev-low-bg:#fff;
            --sev-info-bg:#fff; --ok:#000; --warn:#000; --danger:#000; --unknown:#000;
            --shadow-sm:none; --shadow-md:none; --shadow-lg:none;
          }
          .app-sidebar, .app-topbar, .cmdk, .skip-link, .no-print { display: none !important; }
          .app-main { padding: 0 !important; }
          .card { break-inside: avoid; border: 1px solid #000; }
        }

        @media (prefers-reduced-motion: reduce) {
          /* The DELAY is zeroed too, not only the duration. An entrance animation with fill-mode `both`
             renders its from-state during the delay, so a zero-duration animation with a 28ms stagger
             leaves the element invisible for 28ms — a flicker, on exactly the setting chosen to avoid one.
             Found by reading the stagger back against this rule rather than by looking at it. */
          *, *::before, *::after {
            animation-duration: 0s !important; animation-delay: 0s !important;
            transition-duration: 0s !important;
          }
        }
        """;

    // ==============================================================================================
    private static final String RESET = """
        /* ===== Reset ===== */
        *, *::before, *::after { box-sizing: border-box; }
        html { font-size: 14px; -webkit-text-size-adjust: 100%; }
        body {
          margin: 0; font-family: var(--ff-sans); font-size: var(--fs-14);
          line-height: var(--lh-normal); color: var(--fg-default); background: var(--bg-canvas);
          font-feature-settings: "cv05" 1, "ss01" 1; -webkit-font-smoothing: antialiased;
        }
        h1,h2,h3,h4 { margin: 0; font-weight: var(--fw-semibold); line-height: var(--lh-tight); }
        h1 { font-size: var(--fs-20); }
        h2 { font-size: var(--fs-16); }
        h3 { font-size: var(--fs-13); }
        p { margin: 0; }
        ul, ol { margin: 0; padding: 0; list-style: none; }
        a { color: inherit; text-decoration: none; }
        a.link { color: var(--accent); }
        a.link:hover { text-decoration: underline; }
        button { font: inherit; color: inherit; background: none; border: 0; cursor: pointer; }
        code, .mono, .tabular { font-family: var(--ff-mono); font-variant-ligatures: none; }
        .tabular { font-variant-numeric: tabular-nums; }

        /* Focus is always visible and never suppressed. DOC-08 §7.1, INT-UIX-003. */
        :focus { outline: none; }
        :focus-visible {
          outline: 2px solid var(--border-focus); outline-offset: 2px; border-radius: var(--radius-sm);
        }
        .skip-link {
          position: fixed; inset-block-start: var(--sp-2); inset-inline-start: -200%;
          z-index: 100; padding: var(--sp-2) var(--sp-4); background: var(--bg-surface);
          border: 1px solid var(--border-strong); border-radius: var(--radius-md);
          box-shadow: var(--shadow-md);
        }
        .skip-link:focus-visible { inset-inline-start: var(--sp-2); }
        """;

    // ==============================================================================================
    private static final String SHELL = """
        /* ===== Shell. Azure-grade breadth at Linear density (ADR-006). =====

           Rebuilt after the first version was described, accurately, as ugly. What changed is depth and
           motion; what did NOT change is any rule that carries meaning — colour is still never the sole
           carrier, the unmeasured state still has no numeral form, and every animation still sits inside
           prefers-reduced-motion. A polished interface that renders absent data as zero is worse than a
           plain one that does not, so the polish is layered ON the invariants rather than over them. */
        .app {
          display: grid;
          grid-template-columns: var(--sidebar-w) minmax(0, 1fr);
          grid-template-rows: var(--topbar-h) minmax(0, 1fr);
          grid-template-areas: "brand topbar" "sidebar main";
          min-block-size: 100vh;
          background: var(--bg-canvas);
        }
        @media (max-width: 900px) {
          .app { grid-template-columns: minmax(0, 1fr); grid-template-areas: "topbar" "main"; }
          .app-sidebar, .app-brand { display: none; }
        }

        /* The ambient field. A single fixed layer behind everything, pointer-events off, and it is
           decorative in the strict sense: aria-hidden in the markup and nothing reads it. Two soft radial
           tints rather than an image, so there is no asset to load and no request to make. */
        .app::before {
          content: ""; position: fixed; inset: 0; pointer-events: none; z-index: 0;
          background:
            radial-gradient(60rem 30rem at 12% -8%,
              color-mix(in oklab, var(--accent) 12%, transparent), transparent 70%),
            radial-gradient(48rem 24rem at 92% -12%,
              color-mix(in oklab, var(--sev-low) 10%, transparent), transparent 70%);
        }
        .app > * { position: relative; z-index: 1; }
        /* High contrast and print get none of it: a tint behind text is the first thing to remove when
           the requirement is legibility rather than atmosphere. */
        :root[data-theme="hc"] .app::before { display: none; }

        .app-brand {
          grid-area: brand; display: flex; align-items: center; gap: var(--sp-2);
          padding-inline: var(--sp-4);
          background: color-mix(in oklab, var(--bg-sidebar) 88%, transparent);
          backdrop-filter: saturate(140%) blur(12px);
          border-inline-end: 1px solid var(--border); border-block-end: 1px solid var(--border);
          font-weight: var(--fw-semibold); letter-spacing: -0.01em;
        }
        .app-brand .mark {
          inline-size: 22px; block-size: 22px; border-radius: var(--radius-sm);
          background: linear-gradient(135deg, var(--accent),
                      color-mix(in oklab, var(--sev-low) 60%, var(--accent)));
          color: var(--fg-on-accent);
          display: grid; place-items: center; font-size: var(--fs-11); font-weight: var(--fw-semibold);
          box-shadow: 0 0 0 1px color-mix(in oklab, var(--accent) 40%, transparent),
                      0 4px 12px color-mix(in oklab, var(--accent) 28%, transparent);
        }

        .app-topbar {
          grid-area: topbar; display: flex; align-items: center; gap: var(--sp-3);
          padding-inline: var(--sp-4);
          position: sticky; inset-block-start: 0; z-index: 20;
          background: color-mix(in oklab, var(--bg-surface) 82%, transparent);
          backdrop-filter: saturate(140%) blur(14px);
          border-block-end: 1px solid var(--border);
        }

        .app-sidebar {
          grid-area: sidebar;
          background: color-mix(in oklab, var(--bg-sidebar) 90%, transparent);
          backdrop-filter: blur(12px);
          border-inline-end: 1px solid var(--border);
          padding: var(--sp-3) var(--sp-2) var(--sp-2); overflow-y: auto;
          display: flex; flex-direction: column; gap: var(--sp-4);
        }
        .nav-group > .nav-label {
          font-size: var(--fs-11); font-weight: var(--fw-semibold); text-transform: uppercase;
          letter-spacing: 0.06em; color: var(--fg-subtle);
          padding: var(--sp-1) var(--sp-3) var(--sp-2);
        }
        .nav-item {
          display: flex; align-items: center; gap: var(--sp-2); inline-size: 100%;
          min-block-size: var(--control-h); padding: 0 var(--sp-3);
          border: 1px solid transparent;
          border-radius: var(--radius-md); color: var(--fg-muted);
          font-size: var(--fs-13); font-weight: var(--fw-medium); text-align: start;
          background: transparent; font-family: inherit;
          transition: background var(--dur-fast) var(--ease), color var(--dur-fast) var(--ease),
                      border-color var(--dur-fast) var(--ease), transform var(--dur-fast) var(--ease);
        }
        .nav-item:hover {
          background: var(--bg-hover); color: var(--fg-default);
          border-color: var(--border);
        }
        .nav-item[aria-current="page"] {
          color: var(--accent); font-weight: var(--fw-semibold);
          background: linear-gradient(90deg,
            color-mix(in oklab, var(--accent) 16%, transparent), transparent 85%);
          border-color: color-mix(in oklab, var(--accent) 26%, transparent);
        }
        /* Not colour alone: a left rail marks the current item in monochrome and high contrast. */
        .nav-item[aria-current="page"]::before {
          content: ""; inline-size: 3px; block-size: 16px; border-radius: var(--radius-full);
          background: currentColor; margin-inline-start: calc(var(--sp-3) * -1 + 1px);
          margin-inline-end: calc(var(--sp-2) - 1px);
          box-shadow: 0 0 8px currentColor;
        }
        .nav-item .count {
          margin-inline-start: auto; font-size: var(--fs-11);
          color: var(--fg-subtle); font-variant-numeric: tabular-nums;
        }
        .nav-icon { inline-size: 16px; block-size: 16px; flex: none; opacity: .85; }

        /* The foot: the account link and sign-out, pinned below the sections. Present for EVERY
           principal, because it is the only route to a password change and a principal with no role at
           all must still reach it. */
        .nav-foot {
          margin-block-start: auto; padding-block-start: var(--sp-2);
          border-block-start: 1px solid var(--border);
          display: flex; flex-direction: column; gap: 2px;
        }
        .nav-foot form { margin: 0; }
        .nav-signout { cursor: pointer; }
        .nav-signout:hover { color: var(--danger); border-color: var(--danger); }

        .app-main {
          grid-area: main; min-inline-size: 0;
          padding: var(--sp-6) var(--sp-8) var(--sp-12);
        }
        .page { max-inline-size: var(--content-max); margin-inline: auto; }
        .page-header {
          display: flex; align-items: flex-start; gap: var(--sp-4);
          padding-block-end: var(--sp-5); flex-wrap: wrap;
        }
        .page-header .titles { min-inline-size: 0; }
        .page-header h1 {
          font-size: var(--fs-32); letter-spacing: -0.03em; line-height: 1.1;
          /* The gradient sits on the text, and a plain colour is declared first so a browser that does
             not support background-clip renders a legible heading rather than a transparent one. */
          color: var(--fg-default);
          background: linear-gradient(100deg, var(--fg-default) 30%,
                      color-mix(in oklab, var(--accent) 70%, var(--fg-default)));
          -webkit-background-clip: text; background-clip: text;
          -webkit-text-fill-color: transparent;
        }
        :root[data-theme="hc"] .page-header h1 {
          background: none; -webkit-text-fill-color: currentColor; color: var(--fg-default);
        }
        .page-header .subtitle { color: var(--fg-muted); font-size: var(--fs-13); margin-block-start: 4px;
                                 max-inline-size: 84ch; }
        .page-actions { margin-inline-start: auto; display: flex; gap: var(--sp-2); }

        /* Breadcrumbs begin at the caller's scope root, never at a tenant root they cannot see
           (PRD-UIX-010). */
        .breadcrumbs { display: flex; align-items: center; gap: var(--sp-1); flex-wrap: wrap;
                       font-size: var(--fs-12); color: var(--fg-muted); margin-block-end: var(--sp-2); }
        .breadcrumbs a:hover { color: var(--fg-default); text-decoration: underline; }
        .breadcrumbs .sep { color: var(--fg-subtle); }
        """;

    // ==============================================================================================
    private static final String COMPONENTS = """
        /* ===== Components ===== */
        .btn {
          display: inline-flex; align-items: center; justify-content: center; gap: var(--sp-2);
          min-block-size: var(--control-h); min-inline-size: var(--target-min);
          padding: 0 var(--sp-3); border-radius: var(--radius-md);
          border: 1px solid var(--border-strong); background: var(--bg-surface);
          font-size: var(--fs-13); font-weight: var(--fw-medium); white-space: nowrap;
          transition: background var(--dur-fast) var(--ease), border-color var(--dur-fast) var(--ease);
        }
        .btn:hover { background: var(--bg-hover); }
        .btn-primary {
          background: var(--accent); border-color: var(--accent); color: var(--fg-on-accent);
        }
        .btn-primary:hover { background: var(--accent-hover); border-color: var(--accent-hover); }
        .btn-ghost { border-color: transparent; background: transparent; color: var(--fg-muted); }
        .btn-ghost:hover { background: var(--bg-hover); color: var(--fg-default); }
        .btn-sm { min-block-size: 26px; padding: 0 var(--sp-2); font-size: var(--fs-12); }

        .kbd {
          display: inline-flex; align-items: center; gap: 2px;
          padding: 1px var(--sp-1); min-inline-size: 18px; justify-content: center;
          border: 1px solid var(--border); border-block-end-width: 2px;
          border-radius: var(--radius-sm); background: var(--bg-subtle);
          font-family: var(--ff-mono); font-size: var(--fs-11); color: var(--fg-muted);
        }

        .card {
          background: var(--bg-surface); border: 1px solid var(--border);
          border-radius: var(--radius-lg); box-shadow: var(--shadow-sm);
        }
        .card-header {
          display: flex; align-items: center; gap: var(--sp-2);
          padding: var(--sp-3) var(--sp-4); border-block-end: 1px solid var(--border);
        }
        .card-header h2 { font-size: var(--fs-13); color: var(--fg-muted);
                          text-transform: uppercase; letter-spacing: 0.05em; }
        .card-header .card-actions { margin-inline-start: auto; }
        .card-body { padding: var(--sp-4); }
        .card-footer { padding: var(--sp-2) var(--sp-4); border-block-start: 1px solid var(--border);
                       font-size: var(--fs-12); color: var(--fg-subtle); }

        .grid { display: grid; gap: var(--sp-4); }
        .grid-kpi { grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); }
        .grid-2 { grid-template-columns: repeat(auto-fit, minmax(360px, 1fr)); }

        /* Pills. Every one carries a text label; colour is reinforcement, never the signal. */
        .pill {
          display: inline-flex; align-items: center; gap: var(--sp-1);
          padding: 1px var(--sp-2); border-radius: var(--radius-full);
          font-size: var(--fs-11); font-weight: var(--fw-semibold);
          letter-spacing: 0.02em; white-space: nowrap;
          border: 1px solid transparent;
        }
        .pill::before {
          content: ""; inline-size: 6px; block-size: 6px; border-radius: var(--radius-full);
          background: currentColor; flex: none;
        }
        /* Shape differs per severity, so the rank survives monochrome and colour-blindness. */
        .pill-critical { color: var(--sev-critical); background: var(--sev-critical-bg);
                         border-color: color-mix(in srgb, var(--sev-critical) 30%, transparent); }
        .pill-critical::before { border-radius: 1px; transform: rotate(45deg); }
        .pill-high     { color: var(--sev-high); background: var(--sev-high-bg);
                         border-color: color-mix(in srgb, var(--sev-high) 30%, transparent); }
        .pill-high::before { border-radius: 1px; }
        .pill-medium   { color: var(--sev-medium); background: var(--sev-medium-bg);
                         border-color: color-mix(in srgb, var(--sev-medium) 30%, transparent); }
        .pill-low      { color: var(--sev-low); background: var(--sev-low-bg);
                         border-color: color-mix(in srgb, var(--sev-low) 30%, transparent); }
        .pill-info     { color: var(--sev-info); background: var(--sev-info-bg); }
        .pill-ok       { color: var(--ok); background: var(--ok-bg); }
        .pill-warn     { color: var(--warn); background: var(--warn-bg); }
        .pill-danger   { color: var(--danger); background: var(--danger-bg); }
        .pill-unknown  { color: var(--unknown); background: var(--unknown-bg); }
        .pill-unknown::before { background: none; border: 1px dashed currentColor; }

        .scope-switch {
          display: inline-flex; align-items: center; gap: var(--sp-2);
          min-block-size: var(--control-h); padding: 0 var(--sp-2) 0 var(--sp-3);
          border: 1px solid var(--border); border-radius: var(--radius-md);
          background: var(--bg-surface); font-size: var(--fs-13); max-inline-size: 320px;
        }
        .scope-switch .label { color: var(--fg-subtle); font-size: var(--fs-11);
                               text-transform: uppercase; letter-spacing: .05em; }
        .scope-switch .value { font-weight: var(--fw-medium); overflow: hidden;
                               text-overflow: ellipsis; white-space: nowrap; }

        .cmd-trigger {
          display: inline-flex; align-items: center; gap: var(--sp-2);
          min-block-size: var(--control-h); padding: 0 var(--sp-2) 0 var(--sp-3);
          inline-size: min(420px, 40vw);
          border: 1px solid var(--border); border-radius: var(--radius-md);
          background: var(--bg-subtle); color: var(--fg-subtle); font-size: var(--fs-13);
        }
        .cmd-trigger:hover { background: var(--bg-hover); }
        .cmd-trigger .kbd { margin-inline-start: auto; }

        dialog.cmdk {
          inline-size: min(640px, 92vw); padding: 0; border: 1px solid var(--border-strong);
          border-radius: var(--radius-lg); background: var(--bg-surface); color: var(--fg-default);
          box-shadow: var(--shadow-lg); margin-block-start: 12vh;
        }
        dialog.cmdk::backdrop { background: var(--bg-overlay); }
        .cmdk input {
          inline-size: 100%; border: 0; border-block-end: 1px solid var(--border);
          background: transparent; color: var(--fg-default);
          padding: var(--sp-4); font-size: var(--fs-16);
        }
        .cmdk-list { max-block-size: 50vh; overflow-y: auto; padding: var(--sp-2); }
        .cmdk-item {
          display: flex; align-items: center; gap: var(--sp-3);
          padding: var(--sp-2) var(--sp-3); border-radius: var(--radius-md);
          font-size: var(--fs-13); color: var(--fg-default);
        }
        .cmdk-item:hover, .cmdk-item:focus-visible { background: var(--bg-hover); }
        .cmdk-item .hint { margin-inline-start: auto; color: var(--fg-subtle); font-size: var(--fs-11); }
        .cmdk-group { font-size: var(--fs-11); text-transform: uppercase; letter-spacing: .06em;
                      color: var(--fg-subtle); padding: var(--sp-3) var(--sp-3) var(--sp-1); }

        .banner {
          display: flex; gap: var(--sp-3); align-items: flex-start;
          padding: var(--sp-3) var(--sp-4); border-radius: var(--radius-md);
          border: 1px solid var(--border); background: var(--bg-surface);
          font-size: var(--fs-13); margin-block-end: var(--sp-5);
          border-inline-start: 3px solid var(--warn);
        }
        .banner strong { font-weight: var(--fw-semibold); }

        .toolbar {
          display: flex; align-items: center; gap: var(--sp-2); flex-wrap: wrap;
          padding-block-end: var(--sp-3);
        }
        .toolbar .spacer { margin-inline-start: auto; }
        .field {
          display: inline-flex; align-items: center; min-block-size: var(--control-h);
          border: 1px solid var(--border); border-radius: var(--radius-md);
          background: var(--bg-surface); padding-inline: var(--sp-2);
        }
        .field input, .field select {
          border: 0; background: transparent; color: var(--fg-default);
          font: inherit; font-size: var(--fs-13); min-inline-size: 8ch; padding-block: 0;
        }
        .field label { font-size: var(--fs-11); color: var(--fg-subtle);
                       text-transform: uppercase; letter-spacing: .05em; margin-inline-end: var(--sp-1); }
        """;

    // ==============================================================================================
    private static final String DATA = """
        /* ===== Data. Dense, scannable, keyboard-navigable. ===== */
        .kpi { display: flex; flex-direction: column; gap: var(--sp-1); padding: var(--sp-4); }
        .kpi-label { font-size: var(--fs-12); color: var(--fg-muted); font-weight: var(--fw-medium); }
        .kpi-value {
          font-size: var(--fs-32); font-weight: var(--fw-semibold); letter-spacing: -0.03em;
          font-variant-numeric: tabular-nums; line-height: 1.1;
        }
        /* The coverage qualifier is INSIDE the figure's element. DOC-08 §10: a figure whose
           qualifier is a sibling is a figure somebody renders without it. */
        .kpi-qualifier { font-size: var(--fs-12); color: var(--fg-subtle); }
        .kpi-delta { display: inline-flex; align-items: center; gap: 2px; font-size: var(--fs-12);
                     font-weight: var(--fw-medium); }
        .kpi-delta.up   { color: var(--danger); }
        .kpi-delta.down { color: var(--ok); }
        .kpi-spark { margin-block-start: var(--sp-2); }

        .table-wrap {
          background: var(--bg-surface); border: 1px solid var(--border);
          border-radius: var(--radius-lg); overflow: hidden; box-shadow: var(--shadow-sm);
        }
        .table-scroll { overflow-x: auto; }
        table.data { inline-size: 100%; border-collapse: separate; border-spacing: 0; }
        table.data caption { text-align: start; padding: var(--sp-3) var(--sp-4);
                             font-size: var(--fs-12); color: var(--fg-muted);
                             border-block-end: 1px solid var(--border); }
        table.data th {
          position: sticky; inset-block-start: 0; z-index: 1;
          background: var(--bg-subtle); text-align: start;
          font-size: var(--fs-11); font-weight: var(--fw-semibold);
          text-transform: uppercase; letter-spacing: .05em; color: var(--fg-muted);
          padding: var(--sp-2) var(--sp-4); white-space: nowrap;
          border-block-end: 1px solid var(--border);
        }
        table.data td {
          padding: 0 var(--sp-4); block-size: var(--row-h);
          border-block-end: 1px solid var(--border); font-size: var(--fs-13);
          vertical-align: middle;
        }
        table.data tbody tr:last-child td { border-block-end: 0; }
        table.data tbody tr:hover { background: var(--bg-hover); }
        table.data tbody tr:focus-visible { background: var(--bg-hover); outline-offset: -2px; }
        td.num { text-align: end; font-variant-numeric: tabular-nums; }
        td.truncate, th.truncate { max-inline-size: 32ch; overflow: hidden;
                                   text-overflow: ellipsis; white-space: nowrap; }
        .cell-primary { font-weight: var(--fw-medium); }
        .cell-secondary { color: var(--fg-subtle); font-size: var(--fs-12); }

        .id-chip {
          font-family: var(--ff-mono); font-size: var(--fs-11); color: var(--fg-muted);
          background: var(--bg-inset); padding: 1px var(--sp-1); border-radius: var(--radius-sm);
        }

        .meter { display: flex; flex-direction: column; gap: var(--sp-1); }
        .meter-track { block-size: 6px; border-radius: var(--radius-full);
                       background: var(--bg-inset); overflow: hidden; }
        .meter-fill { block-size: 100%; background: var(--accent); }
        .meter-fill.ok { background: var(--ok); }
        .meter-fill.warn { background: var(--warn); }
        .meter-fill.danger { background: var(--danger); }
        /* An unmeasured meter has no fill and says so in words. It is never a zero-width bar,
           which reads as "measured, and none" (PRD-UIX-022). */
        .meter-track.unmeasured { background: repeating-linear-gradient(
            45deg, var(--bg-inset), var(--bg-inset) 4px, var(--bg-subtle) 4px, var(--bg-subtle) 8px); }
        """;

    // ==============================================================================================
    private static final String CHARTS = """
        /* ===== Charts. Server-rendered SVG, with a tabular alternative (INT-UIX-006). ===== */
        .chart { inline-size: 100%; block-size: auto; overflow: visible; }
        .chart .axis { stroke: var(--border); stroke-width: 1; }
        .chart .grid-line { stroke: var(--border); stroke-width: 1; stroke-dasharray: 2 3; opacity: .7; }
        .chart .tick { fill: var(--fg-subtle); font-size: 10px; font-family: var(--ff-sans); }
        .chart .series-line { fill: none; stroke: var(--accent); stroke-width: 2;
                              stroke-linejoin: round; stroke-linecap: round; }
        .chart .series-area { fill: var(--accent); opacity: .10; }
        .chart .point { fill: var(--bg-surface); stroke: var(--accent); stroke-width: 2; }
        .chart .bar { fill: var(--accent); }
        .chart .bar-critical { fill: var(--sev-critical); }
        .chart .bar-high     { fill: var(--sev-high); }
        .chart .bar-medium   { fill: var(--sev-medium); }
        .chart .bar-low      { fill: var(--sev-low); }
        .chart .bar-unmeasured { fill: url(#hatch); }
        .spark .series-line { stroke-width: 1.5; }

        /* The tabular alternative is present in the DOM, not generated on demand: INT-UIX-006 requires
           it to convey the same information, and a details element keeps it out of the way without
           putting it behind script. */
        details.chart-data { margin-block-start: var(--sp-2); }
        details.chart-data > summary {
          font-size: var(--fs-12); color: var(--fg-muted); cursor: pointer;
          min-block-size: var(--target-min); display: flex; align-items: center;
        }
        details.chart-data table { inline-size: auto; margin-block-start: var(--sp-2); }
        details.chart-data th, details.chart-data td {
          font-size: var(--fs-12); padding: 2px var(--sp-3); block-size: auto;
          text-align: start; border-block-end: 1px solid var(--border);
        }
        """;

    // ==============================================================================================
    private static final String STATES = """
        /* ===== The seven states of DOC-08 §9. Distinguished by treatment, never by colour alone. ===== */
        .state {
          display: flex; flex-direction: column; align-items: flex-start; gap: var(--sp-2);
          padding: var(--sp-8) var(--sp-6); text-align: start;
          border: 1px dashed var(--border-strong); border-radius: var(--radius-lg);
          background: var(--bg-surface);
        }
        .state-icon { inline-size: 28px; block-size: 28px; color: var(--fg-subtle); }
        .state-label { font-size: var(--fs-14); font-weight: var(--fw-semibold); }
        .state-detail { font-size: var(--fs-13); color: var(--fg-muted); max-inline-size: 60ch; }
        .state-loading  { border-style: solid; }
        .state-empty    { border-style: solid; }
        .state-filtered { border-style: dashed; }
        .state-unmeasured {
          border-style: dashed; border-inline-start-width: 3px;
          border-inline-start-color: var(--unknown);
        }
        .state-withheld { border-style: dotted; }
        .state-degraded { border-style: double; border-width: 3px; }
        .state-error    { border-style: solid; border-inline-start: 3px solid var(--danger); }
        .state-inline { padding: 0; border: 0; background: none; flex-direction: row;
                        align-items: baseline; }
        .state-inline .state-label { font-size: var(--fs-12); color: var(--unknown);
                                     font-weight: var(--fw-medium); }
        """;

    // ==============================================================================================
    private static final String AUTH = """
        /* ===== Unauthenticated surfaces. Motion, all of it inside prefers-reduced-motion. ===== */
        /* THE LOGIN SURFACE BORROWS THE APPLICATION'S PALETTE, VALUE FOR VALUE.
        
           Setting data-theme="dark" made this page dark, and it was still visibly not the same dark.
           This tier's dark theme is #0c0e12 canvas over #14171c surface with a pale #8ab2ff accent; the
           application's is oklch(0.17 0.015 265) over oklch(0.21 0.017 265) with a mid-blue primary.
           Both are good palettes and they are not the same one, so signing in still handed you off to a
           slightly different product one screen later.
        
           These are copied from `src/webui/src/index.css`'s `.dark` block, unchanged, because they are
           the palette of the place this page sends people. Every pair here — muted text on surface,
           foreground on accent — is one the application already uses at that size, so nothing about
           contrast is being invented; the ratios annotated on this tier's own tokens above do NOT
           describe this block.
        
           This is a second copy of a palette, which is the drift this codebase is otherwise careful to
           avoid. It is scoped to the auth surface deliberately: the honest repair is ONE palette across
           both tiers (ADR-006 says one design language), and that would restyle every server-rendered
           page — a larger change than a login screen, and not one to make quietly inside it. Recorded
           here so the next person finds the reason rather than the symptom. */
        .auth-body {
          --bg-canvas:     oklch(0.17 0.015 265);
          --bg-surface:    oklch(0.21 0.017 265);
          --bg-subtle:     oklch(0.26 0.018 265);
          --fg-default:    oklch(0.95 0.005 260);
          --fg-muted:      oklch(0.68 0.012 260);
          --fg-subtle:     oklch(0.68 0.012 260);
          --border:        oklch(1 0 0 / 12%);
          /* Measured, not chosen by eye. At 22% the unfocused input border scored 2.01:1 against the
             field it outlines, and WCAG 1.4.11 asks 3:1 of a non-text interface component. Somebody
             landing on the two-factor page could barely see where to type and clicked the box to make
             it visible — which is exactly how it was reported: "the form is dim until I click it".
             36% measures 3.33:1 on this surface; 38% leaves a little headroom.

             My own regression, from aligning this page's palette to the application's: the text
             contrast was checked and the component contrast was not. */
          --border-strong: oklch(1 0 0 / 38%);
          --border-focus:  oklch(0.68 0.16 262);
          --accent:        oklch(0.68 0.16 262);
          --accent-hover:  oklch(0.74 0.15 262);
          --fg-on-accent:  oklch(0.17 0.015 265);

          min-block-size: 100vh; display: grid; place-items: center;
          padding: var(--sp-6); position: relative; overflow-x: hidden;
          background: var(--bg-canvas); color: var(--fg-default);
        }

        /* A drifting gradient. Decorative and aria-hidden: it carries no meaning, which is the only
           condition under which motion behind text is acceptable at all. */
        .auth-aurora {
          position: fixed; inset: 0; z-index: -1; pointer-events: none;
          background:
            radial-gradient(60rem 40rem at 12% 18%, color-mix(in srgb, var(--accent) 22%, transparent), transparent 60%),
            radial-gradient(50rem 34rem at 88% 12%, color-mix(in srgb, var(--sev-low) 18%, transparent), transparent 62%),
            radial-gradient(46rem 32rem at 70% 92%, color-mix(in srgb, var(--sev-critical) 14%, transparent), transparent 60%);
          filter: blur(2px);
          animation: aurora-drift 34s ease-in-out infinite alternate;
        }
        @keyframes aurora-drift {
          0%   { transform: translate3d(0, 0, 0) scale(1); }
          50%  { transform: translate3d(-2%, 1.5%, 0) scale(1.06); }
          100% { transform: translate3d(1.5%, -2%, 0) scale(1.02); }
        }
        /* A faint grid over it. Two layers read as depth where one reads as a wallpaper. */
        .auth-body::before {
          content: ""; position: fixed; inset: 0; z-index: -1; pointer-events: none;
          background-image:
            linear-gradient(to right, color-mix(in srgb, var(--fg-default) 5%, transparent) 1px, transparent 1px),
            linear-gradient(to bottom, color-mix(in srgb, var(--fg-default) 5%, transparent) 1px, transparent 1px);
          background-size: 44px 44px;
          mask-image: radial-gradient(70% 60% at 50% 40%, black, transparent 75%);
        }

        .auth-main { inline-size: min(27rem, 100%); }

        .auth-panel {
          background: color-mix(in srgb, var(--bg-surface) 88%, transparent);
          backdrop-filter: blur(14px) saturate(140%);
          border: 1px solid color-mix(in srgb, var(--border-strong) 70%, transparent);
          border-radius: 16px;
          box-shadow: var(--shadow-lg);
          padding: var(--sp-8) var(--sp-8) var(--sp-6);
          animation: panel-rise var(--dur-base) var(--ease) both;
        }
        @keyframes panel-rise {
          from { opacity: 0; transform: translateY(10px) scale(.99); }
          to   { opacity: 1; transform: none; }
        }

        .auth-brand {
          display: flex; align-items: center; gap: var(--sp-2);
          font-weight: var(--fw-semibold); letter-spacing: -0.01em;
          margin-block-end: var(--sp-6);
        }
        /* The mark is now the product's logo, so it gets no tinted plate behind it: the file carries
           its own colour, and a gradient chip under it would be a second brand competing with the
           first. Sized in the markup as well as here, so it reserves its space before the image
           arrives and the wordmark does not jump. */
        .auth-brand .auth-logo {
          inline-size: 28px; block-size: 28px; flex: none;
          filter: drop-shadow(0 4px 10px color-mix(in srgb, var(--fg-default) 25%, transparent));
        }

        .auth-title { font-size: var(--fs-24); letter-spacing: -0.02em; }
        .auth-lede { color: var(--fg-muted); font-size: var(--fs-13); margin-block: var(--sp-2) var(--sp-6); }

        .auth-form { display: flex; flex-direction: column; gap: var(--sp-4); }
        /* Each field enters slightly after the one above it. Sequenced by a custom property rather
           than by nth-child selectors, so adding a field does not require touching the stylesheet. */
        .auth-field { display: flex; flex-direction: column; gap: 6px;
                      animation: field-in var(--dur-base) var(--ease) both; }
        .auth-form .auth-field:nth-of-type(2) { animation-delay: 60ms; }
        .auth-form .auth-field:nth-of-type(3) { animation-delay: 120ms; }
        @keyframes field-in { from { opacity: 0; transform: translateY(6px); } to { opacity: 1; } }

        .auth-field label { font-size: var(--fs-12); font-weight: var(--fw-medium); color: var(--fg-muted); }
        .auth-field input {
          font: inherit; font-size: var(--fs-16);
          min-block-size: 44px; padding: 0 var(--sp-3);
          color: var(--fg-default); background: var(--bg-surface);
          border: 1px solid var(--border-strong); border-radius: 10px;
          transition: border-color var(--dur-fast) var(--ease), box-shadow var(--dur-fast) var(--ease);
        }
        /* 16px is not a style choice: a smaller font on an input makes iOS Safari zoom the viewport,
           which INT-UIX-004's 200% requirement then interacts with badly. */
        .auth-field input:hover { border-color: var(--fg-subtle); }
        .auth-field input:focus-visible {
          outline: none;
          border-color: var(--accent);
          box-shadow: 0 0 0 4px color-mix(in srgb, var(--accent) 22%, transparent);
        }
        .auth-hint { font-size: var(--fs-12); color: var(--fg-subtle); }

        .auth-submit {
          margin-block-start: var(--sp-2);
          min-block-size: 44px; border-radius: 10px; border: 0;
          font-size: var(--fs-14); font-weight: var(--fw-semibold);
          color: var(--fg-on-accent);
          background: linear-gradient(140deg, var(--accent), color-mix(in srgb, var(--accent) 62%, var(--sev-low)));
          box-shadow: 0 8px 22px color-mix(in srgb, var(--accent) 30%, transparent);
          transition: transform var(--dur-fast) var(--ease), box-shadow var(--dur-fast) var(--ease),
                      filter var(--dur-fast) var(--ease);
        }
        .auth-submit:hover { filter: brightness(1.06); box-shadow: 0 10px 26px color-mix(in srgb, var(--accent) 38%, transparent); }
        .auth-submit:active { transform: translateY(1px) scale(.995); }

        .auth-error, .auth-notice {
          border-radius: 10px; padding: var(--sp-3) var(--sp-4);
          font-size: var(--fs-13); margin-block-end: var(--sp-4);
          animation: shake-in 260ms var(--ease) both;
        }
        .auth-error {
          color: var(--danger); background: var(--danger-bg);
          border: 1px solid color-mix(in srgb, var(--danger) 34%, transparent);
        }
        .auth-notice {
          color: var(--ok); background: var(--ok-bg);
          border: 1px solid color-mix(in srgb, var(--ok) 34%, transparent);
          animation: field-in var(--dur-base) var(--ease) both;
        }
        @keyframes shake-in {
          0%   { opacity: 0; transform: translateX(0); }
          40%  { opacity: 1; transform: translateX(-4px); }
          70%  { transform: translateX(3px); }
          100% { transform: none; }
        }

        /* The QR. A fixed frame so the panel does not reflow between versions, and a white plate that
           does not follow the theme — a QR is not a themable surface. */
        .auth-qr {
          display: grid; place-items: center;
          margin-block-end: var(--sp-4);
          padding: var(--sp-3);
          background: #ffffff;
          border: 1px solid var(--border);
          border-radius: 12px;
          animation: field-in var(--dur-base) var(--ease) both;
        }
        .auth-qr .qr { inline-size: min(216px, 60vw); block-size: auto; display: block; }

        .auth-secret {
          display: flex; flex-direction: column; gap: 6px;
          padding: var(--sp-3) var(--sp-4); margin-block-end: var(--sp-4);
          border: 1px dashed var(--border-strong); border-radius: 10px;
          background: var(--bg-inset);
        }
        .auth-secret-label { font-size: var(--fs-11); text-transform: uppercase;
                             letter-spacing: .06em; color: var(--fg-subtle); }
        .auth-secret-value { font-family: var(--ff-mono); font-size: var(--fs-16);
                             letter-spacing: .08em; word-break: break-all; }

        .auth-alt { margin-block-start: var(--sp-5); font-size: var(--fs-13); }
        .auth-alt a { color: var(--accent); }
        .auth-alt a:hover { text-decoration: underline; }
        .auth-footnote { margin-block-start: var(--sp-4); text-align: center;
                         font-size: var(--fs-12); color: var(--fg-subtle); }
        """;

    // ==============================================================================================
    private static final String ADMIN = """
        /* ===== Administration: forms inside the shell, and the permission matrix ===== */

        /* A standalone control. The .field wrapper above is for toolbar filters — it is an inline-flex
           chrome around a borderless input, which is wrong for a form field that needs its own outline
           and full width. Separate class rather than a modifier: reusing .field here produced controls
           that collapsed to 8ch inside a grid. */
        .input {
          inline-size: 100%; min-block-size: var(--control-h);
          padding: 0 var(--sp-3); font: inherit; font-size: var(--fs-13);
          color: var(--fg-default); background: var(--bg-surface);
          border: 1px solid var(--border-strong); border-radius: var(--radius-md);
          transition: border-color var(--dur-fast) var(--ease), box-shadow var(--dur-fast) var(--ease);
        }
        .input:focus-visible {
          outline: none; border-color: var(--accent);
          box-shadow: 0 0 0 3px color-mix(in oklab, var(--accent) 25%, transparent);
        }
        select.input { padding-inline-end: var(--sp-2); }

        /* A fieldset used purely to disable a form wholesale. It must carry no border, margin or
           padding of its own, or every gated form would gain a box the ungated ones do not have — and
           the difference would read as a rendering fault rather than as a permission state. */
        fieldset.plain { border: 0; margin: 0; padding: 0; min-inline-size: 0; }
        fieldset.plain[disabled] { opacity: .55; }
        fieldset.plain[disabled] .input,
        fieldset.plain[disabled] .perm-row { cursor: not-allowed; }
        /* The hover tint is removed while disabled: a row that lights up under the pointer invites a
           click that does nothing. */
        fieldset.plain[disabled] .perm-row:hover { background: transparent; }

        .form-grid { display: grid; gap: var(--sp-4);
                     grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); }
        .form-actions { display: flex; gap: var(--sp-2); align-items: center;
                        padding-block-start: var(--sp-2); flex-wrap: wrap; }

        /* The permission matrix. Roles are columns, catalogue codes are rows: the catalogue is
           product-fixed and long, the roles are tenant data and few, so the long axis is vertical
           and scrolls. The reverse layout needs a horizontal scroll to read a single role. */
        .matrix-wrap { overflow: auto; max-block-size: 70vh; }
        table.matrix { border-collapse: separate; border-spacing: 0; font-size: var(--fs-12); }
        table.matrix th, table.matrix td {
          padding: var(--sp-2) var(--sp-3); border-block-end: 1px solid var(--border);
          text-align: center; white-space: nowrap;
        }
        table.matrix thead th {
          position: sticky; inset-block-start: 0; z-index: 2;
          background: var(--bg-subtle); font-weight: var(--fw-semibold);
          border-block-end: 1px solid var(--border-strong);
        }
        table.matrix th.row-head, table.matrix td.row-head {
          position: sticky; inset-inline-start: 0; z-index: 1;
          background: var(--bg-surface); text-align: start; font-weight: var(--fw-regular);
          font-family: var(--ff-mono); font-size: var(--fs-11);
        }
        table.matrix thead th.row-head { z-index: 3; }
        table.matrix tbody tr:hover td, table.matrix tbody tr:hover th.row-head {
          background: var(--bg-hover);
        }

        /* Granted and not-granted carry a GLYPH as well as a colour. DOC-00 prohibits colour as the
           sole carrier of meaning, and a matrix of green and grey dots is the clearest example of it. */
        .grant { display: inline-flex; align-items: center; justify-content: center;
                 inline-size: 18px; block-size: 18px; border-radius: var(--radius-sm);
                 font-size: 11px; font-weight: var(--fw-semibold); line-height: 1; }
        .grant-yes { color: var(--ok); background: var(--ok-bg); }
        .grant-no  { color: var(--fg-subtle); background: var(--bg-hover); }

        /* A value shown exactly once and never retrievable again — a reset link, a TOTP secret. The
           visual weight is deliberate: a credential rendered as ordinary text is one a reader scrolls
           past without realising this is their only chance to copy it. */
        .once {
          display: block; padding: var(--sp-3); margin-block: var(--sp-3);
          font-family: var(--ff-mono); font-size: var(--fs-12); word-break: break-all;
          color: var(--fg-default); background: var(--bg-subtle);
          border: 1px dashed var(--warn); border-radius: var(--radius-md);
        }

        /* ===== Depth, and the one rule motion obeys =====

           Every animation below is an entrance: it plays once and leaves the element where CSS would have
           put it anyway. Nothing animates a value, a position that carries meaning, or a state change, so
           the interface is identical to a reader with animation disabled — which is what makes the blanket
           prefers-reduced-motion override at the end of this file sufficient rather than approximate. */
        @keyframes aspm-rise {
          from { opacity: 0; transform: translateY(6px); }
          to   { opacity: 1; transform: none; }
        }
        @keyframes aspm-fade {
          from { opacity: 0; }
          to   { opacity: 1; }
        }

        /* Cards, tables and banners rise on load. The stagger is capped: with twenty cards a per-index
           delay would leave the last one arriving a second late, which reads as a slow page rather than a
           considered one. */
        .card, .table-wrap, .banner, .kpi {
          animation: aspm-rise var(--dur-base) var(--ease) both;
          animation-delay: calc(min(var(--i, 0), 6) * 28ms);
        }
        .nav-item { animation: aspm-fade var(--dur-base) var(--ease) both;
                    animation-delay: calc(min(var(--i, 0), 10) * 22ms); }

        /* Hover elevation on cards, and a hairline sheen along the top edge. Both are affordance rather
           than decoration: the sheen separates a card from the canvas at low contrast, where a 1px border
           alone disappears on a tinted background. */
        .card {
          position: relative;
          transition: box-shadow var(--dur-base) var(--ease), border-color var(--dur-base) var(--ease),
                      transform var(--dur-base) var(--ease);
        }
        .card::after {
          content: ""; position: absolute; inset-block-start: 0; inset-inline: var(--sp-4);
          block-size: 1px; pointer-events: none;
          background: linear-gradient(90deg, transparent,
            color-mix(in oklab, var(--accent) 40%, transparent), transparent);
          opacity: .55;
        }
        .card:hover {
          box-shadow: var(--shadow-md);
          border-color: color-mix(in oklab, var(--accent) 22%, var(--border));
        }
        :root[data-theme="hc"] .card::after { display: none; }

        /* KPI figures. The accent rule is on the left so a column of cards scans as a set, and the value
           uses tabular numerals so digits do not shift width between renders. */
        .kpi { position: relative; }
        .kpi::before {
          content: ""; position: absolute; inset-block: var(--sp-4); inset-inline-start: 0;
          inline-size: 3px; border-radius: 0 var(--radius-full) var(--radius-full) 0;
          background: linear-gradient(180deg, var(--accent),
                      color-mix(in oklab, var(--sev-low) 70%, var(--accent)));
          opacity: .8;
        }
        .kpi-value { font-variant-numeric: tabular-nums; }

        /* Table rows. A sticky header, because a scrolled table whose header has left the viewport is a
           grid of unlabelled numbers. */
        table.data thead th {
          position: sticky; inset-block-start: 0; z-index: 1;
          background: color-mix(in oklab, var(--bg-subtle) 94%, transparent);
          backdrop-filter: blur(8px);
        }
        table.data tbody tr { transition: background var(--dur-fast) var(--ease); }

        /* The primary action glows very slightly. Enough to be found without competing with a severity
           colour, which must stay the most saturated thing on any page. */
        .btn-primary {
          box-shadow: 0 1px 2px color-mix(in oklab, var(--accent) 30%, transparent),
                      0 6px 18px color-mix(in oklab, var(--accent) 20%, transparent);
        }
        .btn-primary:hover { box-shadow: 0 2px 4px color-mix(in oklab, var(--accent) 36%, transparent),
                                        0 10px 26px color-mix(in oklab, var(--accent) 26%, transparent); }
        .btn-danger {
          color: var(--danger); border-color: color-mix(in oklab, var(--danger) 40%, var(--border));
          background: var(--danger-bg);
        }
        .btn-danger:hover { border-color: var(--danger); }

        /* A banner variant that is a refusal rather than a note. .banner alone carries a warn rail. */
        .banner-danger { border-inline-start-color: var(--danger); }

        /* ===== The permission editor =====
           A checkbox per catalogue code, grouped by domain. Rows rather than a grid: the code is the
           identifier a reviewer matches against DOC-07, so it gets a monospace column of its own and the
           human label sits beside it. */
        .perm-domain { margin-block-end: var(--sp-5); }
        .perm-domain-label {
          font-size: var(--fs-11); font-weight: var(--fw-semibold); text-transform: uppercase;
          letter-spacing: .06em; color: var(--fg-subtle); padding-block-end: var(--sp-2);
          border-block-end: 1px solid var(--border); margin-block-end: var(--sp-2);
        }
        .perm-row {
          display: flex; align-items: center; gap: var(--sp-3); flex-wrap: wrap;
          padding: var(--sp-2) var(--sp-2); border-radius: var(--radius-md);
          transition: background var(--dur-fast) var(--ease);
          cursor: pointer;
        }
        .perm-row:hover { background: var(--bg-hover); }
        .perm-row:has(input:checked) {
          background: color-mix(in oklab, var(--accent) 8%, transparent);
        }
        .perm-code { font-family: var(--ff-mono); font-size: var(--fs-12); min-inline-size: 24ch; }
        .perm-label { font-size: var(--fs-13); color: var(--fg-muted); }

        /* ===== Rendered prose: finding write-ups, proofs of concept, comments =====
           The content is attacker-influenced by design, so it arrives through Markdown.render, which
           escapes first and emits a small fixed set of elements. These styles cover exactly that set;
           anything else in the markup would mean the renderer emitted something it should not. */
        .md > :first-child { margin-block-start: 0; }
        .md-p { font-size: var(--fs-13); line-height: var(--lh-normal); margin-block: var(--sp-2); }
        .md-h { font-size: var(--fs-14); font-weight: var(--fw-semibold);
                margin-block: var(--sp-4) var(--sp-2); }
        .md-list { margin-block: var(--sp-2); padding-inline-start: var(--sp-6);
                   font-size: var(--fs-13); line-height: var(--lh-normal); }
        .md-list li { margin-block: 2px; }
        .md-quote { margin-block: var(--sp-3); padding: var(--sp-2) var(--sp-3);
                    border-inline-start: 3px solid var(--border-strong);
                    color: var(--fg-muted); font-size: var(--fs-13); }
        .md-code { margin-block: var(--sp-3); padding: var(--sp-3); overflow-x: auto;
                   background: var(--bg-inset); border: 1px solid var(--border);
                   border-radius: var(--radius-md); font-family: var(--ff-mono);
                   font-size: var(--fs-12); line-height: 1.5; }
        .md-inline { padding: 1px 4px; background: var(--bg-inset);
                     border-radius: var(--radius-sm); font-family: var(--ff-mono);
                     font-size: var(--fs-12); }
        /* A payload in a proof of concept is often one very long line. It scrolls inside its block
           rather than widening the page, which is the rule the whole layout follows. */
        .md-code code { white-space: pre; }

        textarea.md-input { min-block-size: 8rem; padding: var(--sp-3); line-height: 1.5;
                            font-family: var(--ff-mono); font-size: var(--fs-12);
                            resize: vertical; }

        /* A KPI that carries a verdict. The tone is applied only on a non-zero figure — a red
           "overdue: 0" is the boy who cried wolf rendered in CSS — and the label already names the
           condition, so colour reinforces meaning rather than carrying it (DOC-00). */
        .kpi-danger::before { background: var(--danger); opacity: 1; }
        .kpi-danger .kpi-value { color: var(--danger); }
        .kpi-warn::before   { background: var(--warn); opacity: 1; }
        .kpi-warn .kpi-value   { color: var(--warn); }
        .kpi-high::before   { background: var(--sev-high); opacity: 1; }
        .kpi-high .kpi-value   { color: var(--sev-high); }
        .kpi-ok::before     { background: var(--ok); opacity: 1; }
        .kpi-ok .kpi-value     { color: var(--ok); }

        /* Tables inside rendered prose. Scroll inside their own container, like every wide thing on
           the page: the body must never scroll horizontally. */
        .md-table-wrap { overflow-x: auto; margin-block: var(--sp-3);
                         border: 1px solid var(--border); border-radius: var(--radius-md); }
        table.md-table { inline-size: 100%; border-collapse: collapse; font-size: var(--fs-12); }
        table.md-table th, table.md-table td {
          padding: var(--sp-2) var(--sp-3); text-align: start;
          border-block-end: 1px solid var(--border);
        }
        table.md-table thead th { background: var(--bg-subtle); font-weight: var(--fw-semibold); }
        table.md-table tbody tr:last-child td { border-block-end: 0; }

        /* A wide data table is scrollable, and says so. Without the shadow hint a reader takes the
           last visible column for the last column — which is how a whole column goes unnoticed. */
        .table-scroll {
          background:
            linear-gradient(to right, var(--bg-surface) 30%, transparent),
            linear-gradient(to left, var(--bg-surface) 30%, transparent) 100% 0,
            linear-gradient(to right, rgba(0,0,0,.10), transparent 12px),
            linear-gradient(to left, rgba(0,0,0,.10), transparent 12px) 100% 0;
          background-repeat: no-repeat; background-size: 40px 100%, 40px 100%, 14px 100%, 14px 100%;
          background-attachment: local, local, scroll, scroll;
        }

        /* The editor toolbar. Buttons insert Markdown into the textarea beneath them; with script
           unavailable they are absent and the textarea still works, which is the rule every control
           on this platform follows (PRD-UIX-013). */
        .md-toolbar { display: flex; gap: var(--sp-1); flex-wrap: wrap;
                      padding-block-end: var(--sp-1); }
        .md-toolbar button {
          font: inherit; font-size: var(--fs-11); padding: 2px var(--sp-2);
          border: 1px solid var(--border); border-radius: var(--radius-sm);
          background: var(--bg-surface); color: var(--fg-muted); cursor: pointer;
        }
        .md-toolbar button:hover { background: var(--bg-hover); color: var(--fg-default); }
        .md-preview { border: 1px dashed var(--border); border-radius: var(--radius-md);
                      padding: var(--sp-3); margin-block-start: var(--sp-2);
                      background: var(--bg-subtle); }
        .md-upload-status { align-self: center; }
        /* The drop target is outlined, not tinted: colour alone never carries the meaning
           (DOC-00 §11.4), and the outline reads in a monochrome or high-contrast rendering. */
        .md-editor.is-dropping .md-input { outline: 2px dashed var(--accent);
                                           outline-offset: 2px; }
        /* An inline image is bounded by the column it sits in. A screenshot pasted at full width
           would otherwise force the whole record layout to scroll sideways. */
        .md-image { display: block; max-inline-size: 100%; block-size: auto;
                    margin-block: var(--sp-3); border: 1px solid var(--border);
                    border-radius: var(--radius-md); background: var(--bg-subtle); }

        /* ===== Transition list =====
           One column. The previous layout put each move in a flex row of equal-width cards, and a
           move carrying a required-reason input was taller than one without, so the row wrapped into
           a ragged grid with buttons at different heights. A list has no such failure mode. */
        .transition-list { display: flex; flex-direction: column; gap: var(--sp-2); }
        .transition { display: flex; gap: var(--sp-3); align-items: center; flex-wrap: wrap;
                      padding: var(--sp-2) var(--sp-3); border: 1px solid var(--border);
                      border-radius: var(--radius-md); background: var(--bg-surface); }
        .transition-label { display: flex; flex-direction: column; gap: 2px;
                            flex: 1 1 16rem; min-inline-size: 0; }
        .transition-reason { flex: 1 1 14rem; min-inline-size: 10rem; }
        .transition button { flex: 0 0 auto; }
        .transition button[disabled] { opacity: .55; cursor: not-allowed; }

        .comment { padding: var(--sp-3); border: 1px solid var(--border);
                   border-radius: var(--radius-md); background: var(--bg-surface); }

        .session-row { display: flex; gap: var(--sp-3); align-items: baseline;
                       justify-content: space-between; flex-wrap: wrap;
                       padding-block: var(--sp-3); border-block-end: 1px solid var(--border); }
        .session-row:last-child { border-block-end: 0; }
        .session-meta { display: flex; flex-direction: column; gap: 2px; min-inline-size: 0; }
        .session-agent { font-size: var(--fs-11); color: var(--fg-subtle);
                         overflow-wrap: anywhere; max-inline-size: 52ch; }
        """;

    // ==============================================================================================
    private static final String UTILITIES = """
        /* ===== Utilities ===== */
        .visually-hidden {
          position: absolute; inline-size: 1px; block-size: 1px; overflow: hidden;
          clip-path: inset(50%); white-space: nowrap;
        }
        .row { display: flex; align-items: center; gap: var(--sp-2); }
        .col { display: flex; flex-direction: column; gap: var(--sp-2); }
        .between { justify-content: space-between; }
        .wrap { flex-wrap: wrap; }
        .muted { color: var(--fg-muted); }
        .subtle { color: var(--fg-subtle); }
        .fs-12 { font-size: var(--fs-12); }
        .fs-20 { font-size: var(--fs-20); }
        .fw-semibold { font-weight: var(--fw-semibold); }
        .items-center { align-items: center; }
        .self-start { align-self: flex-start; }
        .scroll-x { overflow-x: auto; }
        .split-2 { grid-template-columns: minmax(0, 2fr) minmax(280px, 1fr); }
        .min-w-120 { min-inline-size: 120px; }
        /* The gaps. Named for the spacing step rather than for a purpose, because they are applied to
           .row and .col to override the default of --sp-2 and nothing else distinguishes them. */
        .gap-tight { gap: 2px; }
        .gap-1 { gap: var(--sp-1); }
        .gap-2 { gap: var(--sp-2); }
        .gap-3 { gap: var(--sp-3); }
        .gap-4 { gap: var(--sp-4); }
        .gap-5 { gap: var(--sp-5); }
        .mt-3 { margin-block-start: var(--sp-3); }
        .mt-4 { margin-block-start: var(--sp-4); }
        .mt-6 { margin-block-start: var(--sp-6); }
        .mb-4 { margin-block-end: var(--sp-4); }
        .mb-6 { margin-block-end: var(--sp-6); }
        .ms-2 { margin-inline-start: var(--sp-2); }
        .ms-auto { margin-inline-start: auto; }
        .me-1 { margin-inline-end: var(--sp-1); }
        .pt-2 { padding-block-start: var(--sp-2); }
        .pb-0 { padding-block-end: 0; }
        .stack-6 > * + * { margin-block-start: var(--sp-6); }
        .code-sample {
          overflow-x: auto; background: var(--bg-inset); padding: var(--sp-3);
          border-radius: var(--radius-md);
        }
        """;

    /**
     * The two families of class that exist only because a {@code style} attribute is forbidden.
     *
     * <h2>Why these are generated rather than written inline</h2>
     *
     * <p>{@code SEC-SEC-032} and {@code SEC-SEC-047} both require a Content Security Policy with no
     * {@code unsafe-inline}, and the policy this tier sends says {@code style-src 'self'}. A browser
     * enforcing that <b>blocks every {@code style="…"} attribute in the document</b> — the rule covers
     * attributes as well as {@code <style>} elements, and no nonce or hash can exempt an attribute.
     *
     * <p>That was not a theoretical constraint. Every meter, coverage bar and chart column on the
     * server-rendered pages had its width in a style attribute, so all of them rendered at zero and
     * every page logged dozens of blocked-style violations. <b>A bar at zero on a coverage figure is
     * the PP-1 failure arriving through the console:</b> it is indistinguishable from a real zero.
     *
     * <p>So a percentage becomes a class. One hundred and one rules is more CSS than one attribute,
     * and it is the price of a policy that holds — loosening {@code style-src} to fix a layout bug
     * would trade two mandatory requirements for three kilobytes.
     */
    private static String generated() {
        StringBuilder out = new StringBuilder(4096);
        out.append("\n/* ===== Generated: widths, because a style attribute is blocked ===== */\n");
        for (int percent = 0; percent <= 100; percent++) {
            out.append(".w-").append(percent).append(" { inline-size: ").append(percent)
                    .append("%; }\n");
        }
        // The navigation entrance stagger. The animation clamps the index at ten, so eleven classes
        // cover every list the navigation can produce and a longer one reuses the last.
        out.append("/* Navigation stagger index. */\n");
        for (int index = 0; index <= STAGGER_MAX; index++) {
            out.append(".i-").append(index).append(" { --i: ").append(index).append("; }\n");
        }
        return out.toString();
    }

    /** The largest stagger index with a class. Beyond it the animation delay no longer changes. */
    public static final int STAGGER_MAX = 10;

    /**
     * The class naming a percentage width.
     *
     * <p>Clamped rather than trusted. The callers compute a percentage from a ratio, and a rounding
     * error or a denominator larger than its numerator would otherwise emit a class that does not
     * exist — which renders as no width at all, silently.
     */
    public static String widthClass(long percent) {
        return "w-" + Math.max(0, Math.min(100, percent));
    }
}
