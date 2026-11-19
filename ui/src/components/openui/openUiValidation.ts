import { createParser, type LibraryJSONSchema } from '@openuidev/lang-core';

import spec from '@/generated/openui/spec.json';

// spec.json 由 scripts/openui-generate.mjs 生成；单层断言仍会做结构兼容检查，
// 生成器改动导致 shape 漂移时会在编译期而不是 parse 时才暴露。
const parser = createParser(spec.schema as LibraryJSONSchema, spec.root);

export interface OpenUiDiagnosticError {
  code?: string;
  message?: string;
  statementId?: string;
  path?: string;
}

/**
 * parser 诊断结果：isRenderableProgram 的展开形态。
 *
 * 意图修复链路（plans/038）靠它把"错在哪"交给模型 —— `incomplete` 的程序拿不到
 * statementId，但 REPLACE 块不需要 id，所以 incomplete 也照常送修。
 */
export interface OpenUiDiagnostic {
  renderable: boolean;
  incomplete: boolean;
  hasRoot: boolean;
  errors: OpenUiDiagnosticError[];
  /** 解析中断/游离的语句名（供排查，不进模型 prompt） */
  orphaned: string[];
  /** 悬空引用的语句名（供排查，不进模型 prompt） */
  unresolved: string[];
}

type ParsedElement = {
  type?: string;
  typeName?: string;
  props?: Record<string, unknown>;
  statementId?: string;
};

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value);
}

function hasOwn(value: Record<string, unknown>, key: string): boolean {
  return Object.prototype.hasOwnProperty.call(value, key);
}

function isNumericValue(value: unknown): boolean {
  if (typeof value === 'number') {
    return Number.isFinite(value);
  }
  if (typeof value !== 'string' || value.trim() === '') {
    return false;
  }
  const cleaned = value
    .trim()
    .replace(/[０-９．，％]/g, (char) => String.fromCharCode(char.charCodeAt(0) - 0xfee0))
    .replace(/[,\s%]/g, '');
  return /^-?\d+(?:\.\d+)?(?:万|亿)?(?:元|美元)?$/.test(cleaned);
}

/**
 * `@Each` 等动态数据在解析阶段还是 AST；裸中文键此时已经被 parser 换成 "?"。
 * 递归检查动态对象模板，避免把静默丢字段误判成可渲染。
 */
function hasDiscardedObjectKey(value: unknown): boolean {
  if (Array.isArray(value)) {
    return value.some(hasDiscardedObjectKey);
  }
  if (!isRecord(value)) {
    return false;
  }
  if (
    value.k === 'Obj' &&
    Array.isArray(value.entries) &&
    value.entries.some(
      (entry) => Array.isArray(entry) && entry[0] === '?'
    )
  ) {
    return true;
  }
  return Object.values(value).some(hasDiscardedObjectKey);
}

function chartDiagnostics(element: ParsedElement): OpenUiDiagnosticError[] {
  const props = element.props ?? {};
  const statementId = element.statementId;
  const errors: OpenUiDiagnosticError[] = [];
  const push = (code: string, path: string, message: string) => {
    errors.push({ code, path, message, statementId });
  };

  const data = props.data;
  if (data === undefined || data === null) {
    push('chart-data-empty', '/data', 'Chart data must contain at least one object row');
    return errors;
  }
  // @GroupBy / @Pivot 等函数在解析阶段是动态节点，真实行要等运行时才产生。
  // 但裸中文键被 parser 丢弃时，动态对象模板里已经会出现 "?"，必须在这里拦截。
  if (!Array.isArray(data)) {
    if (hasDiscardedObjectKey(data)) {
      push(
        'chart-keys-lost',
        '/data',
        'Chart object keys were discarded by the parser; quote every data key with double quotes'
      );
    }
    return errors;
  }
  if (data.length === 0 || !data.every(isRecord)) {
    push('chart-data-empty', '/data', 'Chart data must contain at least one object row');
    return errors;
  }
  if ((props.x !== undefined && props.x !== null && typeof props.x !== 'string')
      || (props.series !== undefined && props.series !== null && !Array.isArray(props.series))) {
    return errors;
  }

  const rows = data as Record<string, unknown>[];
  const first = rows[0];
  const keys = Object.keys(first);
  if (keys.includes('?')) {
    push(
      'chart-keys-lost',
      '/data',
      'Chart object keys were discarded by the parser; quote every data key with double quotes'
    );
  }

  const requestedX = typeof props.x === 'string' && props.x.trim() ? props.x : null;
  const x = requestedX
    ?? keys.find((key) => typeof first[key] === 'string')
    ?? keys[0];
  if (!x || (requestedX !== null && !hasOwn(first, requestedX))
      || !rows.every((row) => hasOwn(row, x))) {
    push('chart-x-missing', '/x', `Chart x key "${requestedX ?? x ?? ''}" is missing from data rows`);
  }

  const requestedSeries = Array.isArray(props.series)
    ? props.series.filter((item): item is string => typeof item === 'string' && Boolean(item))
    : [];
  const series = requestedSeries.length
    ? requestedSeries
    : keys.filter((key) => key !== x && isNumericValue(first[key]));

  if (series.length === 0) {
    push('chart-series-empty', '/series', 'Chart has no numeric series to draw');
    return errors;
  }

  const missingSeries = series.filter((key) => !rows.every((row) => hasOwn(row, key)));
  if (missingSeries.length > 0) {
    push(
      'chart-series-missing',
      '/series',
      `Chart series ${missingSeries.map((key) => `"${key}"`).join(', ')} missing from data rows`
    );
  }

  const emptySeries = series.filter((key) => !rows.some((row) => isNumericValue(row[key])));
  if (emptySeries.length > 0) {
    push(
      'chart-series-no-number',
      '/series',
      `Chart series ${emptySeries.map((key) => `"${key}"`).join(', ')} has no numeric values`
    );
  }

  return errors;
}

function collectChartDiagnostics(value: unknown): OpenUiDiagnosticError[] {
  const errors: OpenUiDiagnosticError[] = [];

  const visit = (node: unknown) => {
    if (Array.isArray(node)) {
      node.forEach(visit);
      return;
    }
    if (!isRecord(node)) {
      return;
    }
    const element = node as ParsedElement;
    if (element.type === 'element' && element.typeName === 'Chart') {
      errors.push(...chartDiagnostics(element));
    }
    if (element.props) {
      Object.values(element.props).forEach(visit);
    }
  };

  visit(value);
  return errors;
}

/**
 * 与 openui 的 isValid 同义：没有截断、没有未知组件/参数错误、没有悬空引用、root 存在。
 *
 * 只在流结束后判定 —— 流中间 `incomplete` / `unresolved` 是正常状态，那时候报错会满屏闪。
 */
export function isRenderableProgram(text: string): boolean {
  return diagnoseProgram(text).renderable;
}

/** 空输入与 parse 异常共用的「尚不可渲染」诊断，避免两处字面量漂移。 */
function emptyDiagnostic(): OpenUiDiagnostic {
  return {
    renderable: false,
    incomplete: true,
    hasRoot: false,
    errors: [],
    orphaned: [],
    unresolved: []
  };
}

/** 跑一次 parser 并保留完整诊断信息；parse 抛异常时按"结构崩坏"处理。 */
export function diagnoseProgram(text: string): OpenUiDiagnostic {
  if (!text.trim()) {
    return emptyDiagnostic();
  }
  try {
    const result = parser.parse(text);
    const meta = result.meta;
    const parserErrors: OpenUiDiagnosticError[] = (meta.errors ?? []).map((error) => {
      const raw = error as unknown as Record<string, unknown>;
      return {
        code: typeof raw.code === 'string' ? raw.code : undefined,
        message: typeof raw.message === 'string' ? raw.message : undefined,
        statementId: typeof raw.statementId === 'string' ? raw.statementId : undefined,
        path: typeof raw.path === 'string' ? raw.path : undefined,
      };
    });
    const errors = [
      ...parserErrors,
      ...(result.root === null ? [] : collectChartDiagnostics(result.root))
    ];
    const renderable =
      !meta.incomplete &&
      errors.length === 0 &&
      meta.unresolved.length === 0 &&
      meta.orphaned.length === 0 &&
      result.root !== null;
    return {
      renderable,
      incomplete: Boolean(meta.incomplete),
      hasRoot: result.root !== null,
      errors,
      orphaned: [...meta.orphaned],
      unresolved: [...meta.unresolved],
    };
  } catch (error) {
    // parser 本身崩溃（而非截断）时也要把原因带给意图修复链路，否则与"输入不完整"无法区分。
    const message = error instanceof Error ? error.message : String(error);
    console.error('[openui] parser crashed', error);
    return {
      renderable: false,
      incomplete: true,
      hasRoot: false,
      errors: [
        {
          code: 'parser-crash',
          message
        }
      ],
      orphaned: [],
      unresolved: []
    };
  }
}
