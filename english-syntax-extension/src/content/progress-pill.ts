import type { SessionStatus } from "../shared/protocol";

const FADE_DELAY_MS = 2500;

const STYLES = `
:host {
  all: initial;
}

.pill {
  position: fixed;
  right: 16px;
  bottom: 16px;
  z-index: 2147483646;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 14px;
  border-radius: 999px;
  background: rgba(28, 28, 30, 0.88);
  color: #fff;
  font: 13px/1.3 system-ui, sans-serif;
  box-shadow: 0 2px 10px rgba(0, 0, 0, 0.28);
}

.spinner {
  inline-size: 12px;
  block-size: 12px;
  border: 2px solid #fff;
  border-top-color: transparent;
  border-radius: 50%;
  animation: spin 0.9s linear infinite;
}

@media (prefers-reduced-motion: reduce) {
  .spinner {
    animation: none;
  }
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}
`;

function isComplete(status: SessionStatus): boolean {
  return (
    status.discovered > 0 &&
    status.queued === 0 &&
    status.ready + status.failed >= status.discovered
  );
}

/**
 * A page-corner progress pill fed by SessionController status updates. Pure
 * display: it never sends commands.
 */
export class SyntaxProgressPill {
  readonly host: HTMLElement;
  readonly #pill: HTMLElement;
  readonly #spinner: HTMLElement;
  readonly #label: HTMLElement;
  readonly #document: Document;
  #fadeTimer: ReturnType<typeof setTimeout> | undefined;

  constructor(doc: Document = document) {
    this.#document = doc;
    this.host = doc.createElement("div");
    this.host.dataset.syntaxProgressPill = "";
    const shadow = this.host.attachShadow({ mode: "open" });
    const style = doc.createElement("style");
    style.textContent = STYLES;
    this.#pill = doc.createElement("div");
    this.#pill.className = "pill";
    this.#pill.setAttribute("role", "status");
    this.#spinner = doc.createElement("span");
    this.#spinner.className = "spinner";
    this.#label = doc.createElement("span");
    this.#pill.append(this.#spinner, this.#label);
    shadow.append(style, this.#pill);
  }

  update(status: SessionStatus): void {
    if (status.state === "stopped") {
      this.remove();
      return;
    }
    this.#cancelFade();
    const done = status.ready + status.failed;
    if (status.state === "paused") {
      this.#render(`⏸ 已暂停 ${done}/${status.discovered}`, false);
      return;
    }
    if (isComplete(status)) {
      this.#render(status.failed > 0 ? `✓ 完成，${status.failed} 句失败` : "✓ 解析完成", false);
      this.#fadeTimer = setTimeout(() => this.remove(), FADE_DELAY_MS);
      return;
    }
    if (status.discovered === 0) {
      this.#render("句法解析中…", true);
      return;
    }
    this.#render(`句法解析中 ${done}/${status.discovered}`, true);
  }

  remove(): void {
    this.#cancelFade();
    this.host.remove();
  }

  #render(text: string, spinning: boolean): void {
    this.#label.textContent = text;
    this.#spinner.style.display = spinning ? "" : "none";
    if (!this.host.isConnected) {
      this.#document.documentElement.append(this.host);
    }
  }

  #cancelFade(): void {
    if (this.#fadeTimer !== undefined) {
      clearTimeout(this.#fadeTimer);
      this.#fadeTimer = undefined;
    }
  }
}
