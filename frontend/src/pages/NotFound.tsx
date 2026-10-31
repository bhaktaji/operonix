import { Link } from "react-router-dom";
import { Compass } from "lucide-react";

export function NotFound() {
  return (
    <div className="mx-auto mt-16 max-w-md text-center">
      <div className="mx-auto mb-5 flex h-14 w-14 items-center justify-center rounded-2xl bg-white text-ink-400 shadow-sm ring-1 ring-inset ring-sand-200">
        <Compass className="h-6 w-6" />
      </div>
      <p className="text-[12px] font-semibold uppercase tracking-[0.18em] text-ink-400">404</p>
      <h1 className="mt-2 font-display text-2xl font-semibold tracking-tight text-ink-950">
        This page doesn’t exist
      </h1>
      <p className="mt-2 text-sm leading-relaxed text-ink-500">
        The link may be outdated, or the record was removed from your workspace.
      </p>
      <Link
        to="/"
        className="mt-6 inline-flex h-10 items-center rounded-lg bg-ink-900 px-4 text-sm font-medium text-white transition hover:bg-ink-800"
      >
        Back to dashboard
      </Link>
    </div>
  );
}