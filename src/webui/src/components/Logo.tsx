/**
 * The product mark.
 *
 * <p>An {@code <img>} against {@code /brand/logo.svg}, not an inlined SVG. It was inlined once, which
 * put the artwork in three places — here, the single-page interface's head, and the server-rendered
 * page head — so changing the logo meant changing three files and the failure mode was changing two.
 * There is one file now and three references to its URL.
 *
 * <p>The mark carries its own colour and does not take a theme token. Every other colour in this
 * product means something — a severity, a state, a kind of work — and a mark that changed with the
 * palette would be read as carrying one of those meanings.
 */
export function Logo({ className }: { className?: string }) {
  return (
    <img src="/brand/logo.svg" alt="AI ASPM"
         // Dimensions come from the class, but the intrinsic size is declared so the sidebar does not
         // reflow between the first paint and the image arriving.
         width={24} height={24}
         className={className} />
  );
}
