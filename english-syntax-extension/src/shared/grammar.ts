import { CORE_SCHEMA_VERSION } from "./versions";

export enum GrammarRole {
  SUBJECT = "SUBJECT",
  PREDICATE = "PREDICATE",
  OBJECT = "OBJECT",
  PREDICATIVE = "PREDICATIVE",
  ATTRIBUTE = "ATTRIBUTE",
  ADVERBIAL = "ADVERBIAL",
  COMPLEMENT = "COMPLEMENT",
  APPOSITIVE = "APPOSITIVE",
  SUBJECT_CLAUSE = "SUBJECT_CLAUSE",
  OBJECT_CLAUSE = "OBJECT_CLAUSE",
  PREDICATIVE_CLAUSE = "PREDICATIVE_CLAUSE",
  ATTRIBUTIVE_CLAUSE = "ATTRIBUTIVE_CLAUSE",
  ADVERBIAL_CLAUSE = "ADVERBIAL_CLAUSE",
  INDEPENDENT_ELEMENT = "INDEPENDENT_ELEMENT",
}

export const GRAMMAR_LABELS: Readonly<Record<GrammarRole, string>> = {
  [GrammarRole.SUBJECT]: "主语",
  [GrammarRole.PREDICATE]: "谓语",
  [GrammarRole.OBJECT]: "宾语",
  [GrammarRole.PREDICATIVE]: "表语",
  [GrammarRole.ATTRIBUTE]: "定语",
  [GrammarRole.ADVERBIAL]: "状语",
  [GrammarRole.COMPLEMENT]: "补语",
  [GrammarRole.APPOSITIVE]: "同位语",
  [GrammarRole.SUBJECT_CLAUSE]: "主语从句",
  [GrammarRole.OBJECT_CLAUSE]: "宾语从句",
  [GrammarRole.PREDICATIVE_CLAUSE]: "表语从句",
  [GrammarRole.ATTRIBUTIVE_CLAUSE]: "定语从句",
  [GrammarRole.ADVERBIAL_CLAUSE]: "状语从句",
  [GrammarRole.INDEPENDENT_ELEMENT]: "独立成分",
};

export interface Token {
  id: number;
  text: string;
  start: number;
  end: number;
  leadingWhitespace: string;
  punctuation: boolean;
}

export interface TokenRange {
  startToken: number;
  endToken: number;
}

export interface CoreComponent extends TokenRange {
  role: GrammarRole;
  translation: string;
}

export interface CoreAnalysis {
  schemaVersion: typeof CORE_SCHEMA_VERSION;
  sentenceId: string;
  components: CoreComponent[];
  modelProfileId: string;
}

export interface DetailStructure extends TokenRange {
  role: string;
  explanation: string;
}

export interface DetailAnalysis {
  sentenceId: string;
  focus: TokenRange;
  structures: DetailStructure[];
  grammarPoints: string[];
  explanation: string;
  modelProfileId: string;
}
