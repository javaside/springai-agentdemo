import type { Token } from "../shared/grammar";

export interface SegmentedSentence {
  text: string;
  start: number;
  end: number;
}

export interface SentenceIdInput {
  sessionId: string;
  blockId: string;
  order: number;
  normalizedText: string;
}

const SENTENCE_SEGMENTER = new Intl.Segmenter("en", { granularity: "sentence" });
const ABBREVIATIONS = ["Mr.", "Mrs.", "Ms.", "Dr.", "Prof.", "Sr.", "Jr.", "e.g.", "i.e.", "U.S."];
const TOKEN_PATTERN = /[\p{L}\p{N}]+(?:['’-][\p{L}\p{N}]+)*|[^\s]/gu;
const WORD_START_PATTERN = /^[\p{L}\p{N}]/u;

interface PendingSentence {
  text: string;
  start: number;
}

function endsWithAbbreviation(text: string): boolean {
  const trimmed = text.trimEnd();
  return ABBREVIATIONS.some((abbreviation) => trimmed.endsWith(abbreviation));
}

function trimSentence(sentence: PendingSentence): SegmentedSentence | undefined {
  const textWithoutLeadingWhitespace = sentence.text.trimStart();
  const leadingWhitespaceLength = sentence.text.length - textWithoutLeadingWhitespace.length;
  const text = textWithoutLeadingWhitespace.trimEnd();

  if (text.length === 0) {
    return undefined;
  }

  const start = sentence.start + leadingWhitespaceLength;
  return { text, start, end: start + text.length };
}

export function segmentBlock(text: string): SegmentedSentence[] {
  const sentences: SegmentedSentence[] = [];
  let pending: PendingSentence | undefined;

  const flushPending = (): void => {
    if (pending === undefined) {
      return;
    }

    const sentence = trimSentence(pending);
    if (sentence !== undefined) {
      sentences.push(sentence);
    }
    pending = undefined;
  };

  for (const part of SENTENCE_SEGMENTER.segment(text)) {
    if (pending === undefined) {
      pending = {
        text: part.segment,
        start: part.index,
      };
      continue;
    }

    if (endsWithAbbreviation(pending.text)) {
      pending.text += part.segment;
    } else {
      flushPending();
      pending = {
        text: part.segment,
        start: part.index,
      };
    }
  }

  flushPending();
  return sentences;
}

export function tokenize(sentence: string): Token[] {
  const tokens: Token[] = [];
  let previousEnd = 0;

  for (const match of sentence.matchAll(TOKEN_PATTERN)) {
    const start = match.index;
    const text = match[0];
    const end = start + text.length;
    tokens.push({
      id: tokens.length,
      text,
      start,
      end,
      leadingWhitespace: sentence.slice(previousEnd, start),
      punctuation: !WORD_START_PATTERN.test(text),
    });
    previousEnd = end;
  }

  return tokens;
}

export function rebuildTokens(tokens: readonly Token[]): string {
  return tokens.map((token) => token.leadingWhitespace + token.text).join("");
}

export async function createSentenceId(input: SentenceIdInput): Promise<string> {
  const source = `${input.sessionId}\0${input.blockId}\0${input.order}\0${input.normalizedText}`;
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(source));
  return Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, "0"))
    .join("")
    .slice(0, 24);
}
