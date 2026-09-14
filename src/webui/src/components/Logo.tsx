import { cn } from "@/lib/utils";

/**
 * The product mark, as supplied by the product owner (PNG, 2026-09-13; dark wordmark 2026-09-14) and
 * served from `/brand/*`.
 *
 * <h2>Two variants, and two files for one of them</h2>
 *
 * `mark` is the shield alone — for anywhere the name is printed beside it or the space is square. It
 * carries no text, so one file serves both themes. `wordmark` is shield plus "AI ASPM", and the word
 * is navy on a light ground and near-white on a dark one, so there are two files and the theme picks.
 *
 * <h2>Why both are rendered and one is hidden</h2>
 *
 * Not a `src` chosen in JavaScript from the current theme. The theme is a class on the root element
 * that the shell toggles, and a `src` computed in render would swap the image one paint after the
 * background — a flash of the wrong mark, on the element a reader looks at first. CSS switches both in
 * the same paint, and the browser fetches only what it displays.
 *
 * <h2>Why two files rather than a filter</h2>
 *
 * The shield keeps its own colours in both. A CSS filter that lightened the word would lighten the
 * shield with it, and the mark would stop being the mark.
 */
export function Logo({ variant = "mark", className }: { variant?: "mark" | "wordmark"; className?: string }) {
  if (variant === "wordmark") {
    // Intrinsic size declared so the sidebar does not reflow between the first paint and the image.
    return (
      <>
        <img src="/brand/logo.png" alt="AI ASPM" width={168} height={56}
             className={cn("dark:hidden", className)} />
        <img src="/brand/logo-dark.png" alt="" aria-hidden="true" width={168} height={56}
             className={cn("hidden dark:block", className)} />
      </>
    );
  }
  return <img src="/brand/icon.png" alt="AI ASPM" width={24} height={24} className={className} />;
}
