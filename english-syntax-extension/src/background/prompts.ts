import { GrammarRole } from "../shared/grammar";
import type { CoreAnalysis, TokenRange } from "../shared/grammar";
import type { SentenceInput } from "../shared/protocol";

export interface ValidationErrorDescription {
  path: string;
  message: string;
}

function serialize(value: unknown): string {
  return JSON.stringify(value, null, 2);
}

export function buildCorePrompt(sentences: readonly SentenceInput[]): string {
  const roles = Object.values(GrammarRole).join(", ");
  return [
    "Analyze the numbered English sentences below into core grammatical components.",
    `The role field is a closed 14-role enum: ${roles}.`,
    "Every component uses a closed Token interval [startToken, endToken]; both endpoints are inclusive Token IDs from the supplied sentence.",
    "Coverage rule: every non-punctuation Token must be covered exactly once. Components must be ordered, non-overlapping, and may include punctuation but may not contain punctuation only.",
    "Give every component a concise, non-empty Chinese translation.",
    "Keep every sentenceId and every supplied Token unchanged. Return JSON only, with no Markdown or explanatory prose.",
    "Numbered sentence requests:",
    serialize(sentences),
  ].join("\n\n");
}

export function buildRepairPrompt(
  sentences: readonly SentenceInput[],
  errors: readonly ValidationErrorDescription[],
  invalidJson: unknown,
): string {
  return [
    "Repair only the structure of the invalid core-analysis JSON so it satisfies every validation error.",
    "Do not change sentence IDs or Tokens. Do not add sentences and do not reinterpret the source text.",
    "Return the repaired JSON only, without a Markdown fence or prose.",
    "Original sentence IDs and Tokens:",
    serialize(sentences),
    "Validation errors:",
    serialize(errors),
    "Invalid JSON:",
    serialize(invalidJson),
  ].join("\n\n");
}

export function buildDetailPrompt(
  sentence: SentenceInput,
  verifiedCore: CoreAnalysis,
  focus: TokenRange,
): string {
  return [
    "Explain only the selected grammatical component in the single sentence below.",
    "Treat the verified core result and focus Token range as immutable. Refer only to supplied Token IDs.",
    "Return JSON only, with no Markdown or explanatory prose.",
    "Selected sentence:",
    serialize(sentence),
    "Verified core result:",
    serialize(verifiedCore),
    "Focus range:",
    serialize(focus),
  ].join("\n\n");
}
