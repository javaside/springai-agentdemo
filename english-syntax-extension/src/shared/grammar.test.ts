import { describe, expect, it } from "vitest";
import { GRAMMAR_LABELS, GrammarRole } from "./grammar";

describe("grammar roles", () => {
  it("maps every role to a Chinese label", () => {
    expect(Object.keys(GRAMMAR_LABELS).sort()).toEqual(Object.values(GrammarRole).sort());
    expect(GRAMMAR_LABELS[GrammarRole.SUBJECT]).toBe("主语");
  });

  it("labels the compound-sentence roles", () => {
    expect(GRAMMAR_LABELS[GrammarRole.COORDINATE_CLAUSE]).toBe("并列分句");
    expect(GRAMMAR_LABELS[GrammarRole.CONJUNCTION]).toBe("并列连词");
  });
});
