import { GrammarRole } from "../shared/grammar";
import type {
  CoreAnalysis,
  CoreComponent,
  DetailAnalysis,
  DetailStructure,
  Token,
  TokenRange,
} from "../shared/grammar";
import type { SentenceInput } from "../shared/protocol";
import { CORE_SCHEMA_VERSION } from "../shared/versions";

export interface ValidationError {
  path: string;
  message: string;
}

export type ValidationResult<T> = { ok: true; value: T } | { ok: false; errors: ValidationError[] };

const grammarRoles: ReadonlySet<string> = new Set(Object.values(GrammarRole));
const UNSAFE_TEXT = /<script|<iframe|javascript:|\0/i;

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function hasOnlyKeys(value: Record<string, unknown>, keys: readonly string[]): boolean {
  return Object.keys(value).every((key) => keys.includes(key));
}

function isSafeInteger(value: unknown): value is number {
  return Number.isSafeInteger(value);
}

function isSafeText(value: unknown): value is string {
  return typeof value === "string" && !UNSAFE_TEXT.test(value);
}

function addError(errors: ValidationError[], path: string, message: string): void {
  errors.push({ path, message });
}

function parseRange(
  value: Record<string, unknown>,
  path: string,
  errors: ValidationError[],
): TokenRange | undefined {
  const { startToken, endToken } = value;
  if (!isSafeInteger(startToken)) {
    addError(errors, `${path}.startToken`, "must be a safe integer");
  }
  if (!isSafeInteger(endToken)) {
    addError(errors, `${path}.endToken`, "must be a safe integer");
  }
  if (!isSafeInteger(startToken) || !isSafeInteger(endToken)) {
    return undefined;
  }
  if (startToken > endToken) {
    addError(errors, path, "token interval is reversed");
    return undefined;
  }
  return { startToken, endToken };
}

function componentEnglishLength(tokens: readonly Token[], range: TokenRange): number {
  return tokens
    .filter((token) => token.id >= range.startToken && token.id <= range.endToken)
    .reduce((length, token) => length + token.leadingWhitespace.length + token.text.length, 0);
}

function parseCoreComponent(
  value: unknown,
  tokens: readonly Token[],
  path: string,
  errors: ValidationError[],
): CoreComponent | undefined {
  if (!isRecord(value)) {
    addError(errors, path, "must be an object");
    return undefined;
  }
  if (!hasOnlyKeys(value, ["startToken", "endToken", "role", "translation"])) {
    addError(errors, path, "contains unknown fields");
  }

  const range = parseRange(value, path, errors);
  const role = value.role;
  if (typeof role !== "string" || !grammarRoles.has(role)) {
    addError(errors, `${path}.role`, "must be a known grammar role");
  }

  const translation = value.translation;
  if (!isSafeText(translation)) {
    addError(errors, `${path}.translation`, "must be a safe string");
  } else if (translation.trim().length === 0) {
    addError(errors, `${path}.translation`, "must not be empty");
  } else if (
    range !== undefined &&
    translation.length > Math.max(500, componentEnglishLength(tokens, range) * 8)
  ) {
    addError(errors, `${path}.translation`, "is too long");
  }

  if (
    range === undefined ||
    typeof role !== "string" ||
    !grammarRoles.has(role) ||
    !isSafeText(translation) ||
    translation.trim().length === 0
  ) {
    return undefined;
  }
  return { ...range, role: role as GrammarRole, translation };
}

function parseCoreSentence(
  value: unknown,
  request: SentenceInput,
  sentenceIndex: number,
  modelProfileId: string,
  errors: ValidationError[],
): CoreAnalysis | undefined {
  const path = `sentences[${sentenceIndex}]`;
  if (!isRecord(value)) {
    addError(errors, path, "must be an object");
    return undefined;
  }
  if (!hasOnlyKeys(value, ["sentenceId", "components"])) {
    addError(errors, path, "contains unknown fields");
  }
  if (!isSafeText(value.sentenceId) || value.sentenceId !== request.sentenceId) {
    addError(errors, `${path}.sentenceId`, "does not match the requested sentence");
  }
  if (!Array.isArray(value.components) || value.components.length === 0) {
    addError(errors, `${path}.components`, "must be a non-empty array");
    return undefined;
  }

  const components = value.components.map((component, componentIndex) =>
    parseCoreComponent(component, request.tokens, `${path}.components[${componentIndex}]`, errors),
  );
  let previousEnd = -1;
  for (const [index, component] of components.entries()) {
    if (component === undefined) {
      continue;
    }
    const componentPath = `${path}.components[${index}]`;
    const coveredTokens = request.tokens.filter(
      (token) => token.id >= component.startToken && token.id <= component.endToken,
    );
    if (
      coveredTokens.length === 0 ||
      coveredTokens[0]!.id !== component.startToken ||
      coveredTokens.at(-1)?.id !== component.endToken
    ) {
      addError(errors, componentPath, "token interval is outside the original sentence");
    }
    if (component.startToken <= previousEnd) {
      addError(errors, `${path}.components`, "components must be ordered and non-overlapping");
    }
    if (coveredTokens.length > 0 && coveredTokens.every((token) => token.punctuation)) {
      addError(errors, componentPath, "component must not contain only punctuation");
    }
    previousEnd = component.endToken;
  }

  const validComponents = components.filter(
    (component): component is CoreComponent => component !== undefined,
  );

  for (const token of request.tokens) {
    const coverage = validComponents.filter(
      (component) => token.id >= component.startToken && token.id <= component.endToken,
    ).length;
    if (!token.punctuation && coverage === 0) {
      addError(errors, `${path}.components`, `non-punctuation token ${token.id} is not covered`);
    } else if (!token.punctuation && coverage > 1) {
      addError(
        errors,
        `${path}.components`,
        `non-punctuation token ${token.id} is covered more than once`,
      );
    } else if (token.punctuation && coverage > 1) {
      addError(
        errors,
        `${path}.components`,
        `punctuation token ${token.id} is covered more than once`,
      );
    }
  }

  if (
    errors.some((error) => error.path === path || error.path.startsWith(`${path}.`)) ||
    validComponents.length !== components.length
  ) {
    return undefined;
  }
  return {
    schemaVersion: CORE_SCHEMA_VERSION,
    sentenceId: request.sentenceId,
    components: validComponents,
    modelProfileId,
  };
}

export function validateCoreBatch(
  raw: unknown,
  requests: readonly SentenceInput[],
  modelProfileId: string,
): ValidationResult<CoreAnalysis[]> {
  const errors: ValidationError[] = [];
  if (!isRecord(raw)) {
    return { ok: false, errors: [{ path: "", message: "must be an object" }] };
  }
  if (!hasOnlyKeys(raw, ["sentences"])) {
    addError(errors, "", "contains unknown fields");
  }
  if (!Array.isArray(raw.sentences)) {
    addError(errors, "sentences", "must be an array");
    return { ok: false, errors };
  }

  const requestById = new Map(requests.map((request) => [request.sentenceId, request]));
  const seen = new Set<string>();
  const analysesById = new Map<string, CoreAnalysis>();
  raw.sentences.forEach((sentence, index) => {
    const path = `sentences[${index}]`;
    if (!isRecord(sentence) || !isSafeText(sentence.sentenceId)) {
      addError(errors, `${path}.sentenceId`, "must be a safe string");
      return;
    }
    const request = requestById.get(sentence.sentenceId);
    if (request === undefined) {
      addError(errors, `${path}.sentenceId`, "was not requested");
      return;
    }
    if (seen.has(sentence.sentenceId)) {
      addError(errors, `${path}.sentenceId`, "is duplicated");
      return;
    }
    seen.add(sentence.sentenceId);
    const analysis = parseCoreSentence(sentence, request, index, modelProfileId, errors);
    if (analysis !== undefined) {
      analysesById.set(sentence.sentenceId, analysis);
    }
  });

  for (const request of requests) {
    if (!seen.has(request.sentenceId)) {
      addError(errors, "sentences", `requested sentence ${request.sentenceId} is missing`);
    }
  }
  if (errors.length > 0) {
    return { ok: false, errors };
  }
  return {
    ok: true,
    value: requests.map((request) => analysesById.get(request.sentenceId) as CoreAnalysis),
  };
}

function parseDetailStructure(
  value: unknown,
  tokens: readonly Token[],
  path: string,
  errors: ValidationError[],
): DetailStructure | undefined {
  if (!isRecord(value)) {
    addError(errors, path, "must be an object");
    return undefined;
  }
  if (!hasOnlyKeys(value, ["startToken", "endToken", "role", "explanation"])) {
    addError(errors, path, "contains unknown fields");
  }
  const range = parseRange(value, path, errors);
  if (
    (range !== undefined && !tokens.some((token) => token.id === range.startToken)) ||
    (range !== undefined && !tokens.some((token) => token.id === range.endToken))
  ) {
    addError(errors, path, "token interval is outside the original sentence");
  }
  const role = value.role;
  if (!isSafeText(role) || role.trim().length === 0) {
    addError(errors, `${path}.role`, "must be a non-empty safe string");
  }
  const explanation = value.explanation;
  if (!isSafeText(explanation) || explanation.trim().length === 0) {
    addError(errors, `${path}.explanation`, "must be a non-empty safe string");
  }
  if (
    range === undefined ||
    !isSafeText(role) ||
    role.trim().length === 0 ||
    !isSafeText(explanation) ||
    explanation.trim().length === 0
  ) {
    return undefined;
  }
  return { ...range, role, explanation };
}

export function validateDetail(
  raw: unknown,
  request: SentenceInput,
  requestedFocus: TokenRange,
  modelProfileId: string,
): ValidationResult<DetailAnalysis> {
  const errors: ValidationError[] = [];
  if (!isRecord(raw)) {
    return { ok: false, errors: [{ path: "", message: "must be an object" }] };
  }
  if (!hasOnlyKeys(raw, ["sentenceId", "focus", "structures", "grammarPoints", "explanation"])) {
    addError(errors, "", "contains unknown fields");
  }
  if (!isSafeText(raw.sentenceId) || raw.sentenceId !== request.sentenceId) {
    addError(errors, "sentenceId", "does not match the requested sentence");
  }

  let focus: TokenRange | undefined;
  if (!isRecord(raw.focus) || !hasOnlyKeys(raw.focus, ["startToken", "endToken"])) {
    addError(errors, "focus", "must be a token interval");
  } else {
    focus = parseRange(raw.focus, "focus", errors);
    if (
      focus !== undefined &&
      (focus.startToken !== requestedFocus.startToken || focus.endToken !== requestedFocus.endToken)
    ) {
      addError(errors, "focus", "must match the requested focus");
    }
  }

  let structures: DetailStructure[] = [];
  if (!Array.isArray(raw.structures)) {
    addError(errors, "structures", "must be an array");
  } else {
    structures = raw.structures
      .map((structure, index) =>
        parseDetailStructure(structure, request.tokens, `structures[${index}]`, errors),
      )
      .filter((structure): structure is DetailStructure => structure !== undefined);
  }

  const grammarPoints: string[] = [];
  if (!Array.isArray(raw.grammarPoints)) {
    addError(errors, "grammarPoints", "must be an array");
  } else {
    if (raw.grammarPoints.length > 12) {
      addError(errors, "grammarPoints", "must contain at most 12 items");
    }
    raw.grammarPoints.forEach((point, index) => {
      if (!isSafeText(point) || point.trim().length === 0 || point.length > 300) {
        addError(
          errors,
          `grammarPoints[${index}]`,
          "must be a non-empty safe string of at most 300 characters",
        );
      } else {
        grammarPoints.push(point);
      }
    });
  }

  const explanation = raw.explanation;
  if (!isSafeText(explanation) || explanation.trim().length === 0) {
    addError(errors, "explanation", "must be a non-empty safe string");
  }
  if (errors.length > 0 || focus === undefined || !isSafeText(explanation)) {
    return { ok: false, errors };
  }
  return {
    ok: true,
    value: {
      sentenceId: request.sentenceId,
      focus,
      structures,
      grammarPoints,
      explanation,
      modelProfileId,
    },
  };
}
