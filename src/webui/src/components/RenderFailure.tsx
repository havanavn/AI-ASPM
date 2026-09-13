import { Component, type ErrorInfo, type ReactNode } from "react";

/**
 * The last line of defence against a blank page. React unmounts the whole tree when a render throws
 * and nothing catches it; the reader is then left with white and no way to say what happened. This
 * catches it, says so, and offers a reload — the error itself goes to the console for the engineer.
 */
export class RenderFailure extends Component<{ children: ReactNode }, { failure: Error | null }> {
  state = { failure: null as Error | null };

  static getDerivedStateFromError(failure: Error) {
    return { failure };
  }

  componentDidCatch(failure: Error, info: ErrorInfo) {
    console.error("render failed", failure, info.componentStack);
  }

  render() {
    if (!this.state.failure) return this.props.children;
    return (
      <div className="p-6 text-sm text-muted-foreground">
        <p className="mb-2 font-medium text-foreground">This page failed to render.</p>
        <p className="mb-3">
          The data was loaded, but the page could not be drawn. Reloading usually clears it; if it
          does not, the message below is what the engineer needs.
        </p>
        <pre className="mb-3 max-w-3xl overflow-x-auto rounded border bg-muted p-2 text-xs">{String(this.state.failure)}</pre>
        <button type="button" className="underline" onClick={() => window.location.reload()}>Reload</button>
        {" · "}
        <a className="underline" href="/overview">Start from the overview</a>
      </div>
    );
  }
}
