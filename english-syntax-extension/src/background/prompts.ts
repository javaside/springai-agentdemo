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

/**
 * Compatibility mode sends no response_format, so the exact output envelope
 * must be spelled out in the prompt itself. A schema-free model otherwise
 * guesses the shape (e.g. a top-level array) and fails validation.
 */
export const CORE_OUTPUT_SHAPE = [
  "Output exactly one JSON object of this shape, not a top-level array:",
  '{"sentences": [{"sentenceId": string, "components": [{"startToken": number, "endToken": number, "role": string, "translation": string}]}]}',
  "A component must never contain only punctuation Tokens; attach punctuation to an adjacent component or leave it uncovered.",
].join("\n");

const DETAIL_OUTPUT_SHAPE = [
  "Output exactly one JSON object of this shape:",
  '{"sentenceId": string, "focus": {"startToken": number, "endToken": number}, "structures": [{"startToken": number, "endToken": number, "role": string, "explanation": string}], "grammarPoints": [string], "explanation": string}',
  "Echo the supplied sentenceId and focus unchanged. Write explanations and grammar points in Chinese.",
].join("\n");

export function buildCorePrompt(sentences: readonly SentenceInput[]): string {
  const roles = Object.values(GrammarRole).join(", ");
  return [
    "Analyze the numbered English sentences below into core grammatical components.",
    `The role field is a closed 14-role enum: ${roles}.`,
    "Every component uses a closed Token interval [startToken, endToken]; both endpoints are inclusive Token IDs from the supplied sentence.",
    "Coverage rule: every non-punctuation Token must be covered exactly once. Components must be ordered, non-overlapping, and may include punctuation but may not contain punctuation only.",
    "Give every component a concise, non-empty Chinese translation.",
    "Keep every sentenceId and every supplied Token unchanged. Return JSON only, with no Markdown or explanatory prose.",
    CORE_OUTPUT_SHAPE,
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
    CORE_OUTPUT_SHAPE,
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
    DETAIL_OUTPUT_SHAPE,
    "Selected sentence:",
    serialize(sentence),
    "Verified core result:",
    serialize(verifiedCore),
    "Focus range:",
    serialize(focus),
  ].join("\n\n");
}
