import { describe, expect, it } from "vitest";
import { GRAMMAR_LABELS, GrammarRole } from "./grammar";

describe("grammar roles", () => {
  it("maps every role to a Chinese label", () => {
    expect(Object.keys(GRAMMAR_LABELS).sort()).toEqual(Object.values(GrammarRole).sort());
    expect(GRAMMAR_LABELS[GrammarRole.SUBJECT]).toBe("主语");
  });
});
