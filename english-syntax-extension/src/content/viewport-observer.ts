import type { CandidateBlock } from "./document-scanner";

export type ViewportCandidateCallback = (candidate: CandidateBlock) => void;

export class ViewportObserver {
  private readonly candidatesByElement = new Map<Element, CandidateBlock>();
  private readonly candidatesById = new Map<string, CandidateBlock>();
  private readonly emitted = new Set<string>();
  private readonly intersectionObserver?: IntersectionObserver;
  private listening = false;
  private frameId: number | undefined;
  private disconnected = false;

  constructor(private readonly callback: ViewportCandidateCallback) {
    if (typeof globalThis.IntersectionObserver === "function") {
      this.intersectionObserver = new IntersectionObserver(
        (entries) => {
          for (const entry of entries) {
            if (!entry.isIntersecting) continue;
            const candidate = this.candidatesByElement.get(entry.target);
            if (candidate !== undefined) this.emit(candidate);
          }
        },
        { rootMargin: "100% 0px 100% 0px" },
      );
    }
  }

  observe(blocks: readonly CandidateBlock[]): void {
    if (this.disconnected) return;
    for (const block of blocks) {
      this.candidatesByElement.set(block.element, block);
      this.candidatesById.set(block.id, block);
      this.intersectionObserver?.observe(block.element);
    }
    if (this.intersectionObserver === undefined) {
      this.startFallback();
      this.checkFallback();
    }
  }

  invalidate(blockId: string): void {
    this.emitted.delete(blockId);
  }

  isVisible(element: Element): boolean {
    const viewportHeight = window.innerHeight;
    const rectangle = element.getBoundingClientRect();
    return rectangle.bottom >= 0 && rectangle.top <= viewportHeight;
  }

  disconnect(): void {
    if (this.disconnected) return;
    this.disconnected = true;
    this.intersectionObserver?.disconnect();
    if (this.listening) {
      window.removeEventListener("scroll", this.scheduleFallback);
      window.removeEventListener("resize", this.scheduleFallback);
      this.listening = false;
    }
    if (this.frameId !== undefined) cancelAnimationFrame(this.frameId);
    this.frameId = undefined;
    this.candidatesByElement.clear();
    this.candidatesById.clear();
    this.emitted.clear();
  }

  private readonly scheduleFallback = (): void => {
    if (this.frameId !== undefined || this.disconnected) return;
    this.frameId = -1;
    const id = requestAnimationFrame(() => {
      this.frameId = undefined;
      this.checkFallback();
    });
    if (this.frameId !== undefined) this.frameId = id;
  };

  private startFallback(): void {
    if (this.listening) return;
    window.addEventListener("scroll", this.scheduleFallback, { passive: true });
    window.addEventListener("resize", this.scheduleFallback);
    this.listening = true;
  }

  private checkFallback(): void {
    const viewportHeight = window.innerHeight;
    for (const candidate of this.candidatesById.values()) {
      const rectangle = candidate.element.getBoundingClientRect();
      if (rectangle.bottom >= -viewportHeight && rectangle.top <= viewportHeight * 2) {
        this.emit(candidate);
      }
    }
  }

  private emit(candidate: CandidateBlock): void {
    if (this.emitted.has(candidate.id)) return;
    this.emitted.add(candidate.id);
    this.callback(candidate);
  }
}
