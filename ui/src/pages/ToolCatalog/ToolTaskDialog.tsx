import { useState, type ChangeEvent, type FormEvent, type ReactNode } from "react";
import { ArrowLeft, LoaderCircle } from "lucide-react";

import { Button } from "@/components/ui/button";

import type {
  ToolCatalogItem,
  ToolField,
  ToolFunction,
  ToolTaskDraft,
} from "./toolCatalog";
import {
  initializeToolFieldValues,
  type ToolFieldErrors,
} from "./toolCatalogModel";
import { isToolTaskFile, submitToolTask } from "./toolTaskSubmission";

export type ToolTaskDialogProps = {
  tool: ToolCatalogItem;
  toolFunction: ToolFunction;
  onClose: () => void;
  onStartToolTask: (draft: ToolTaskDraft) => void;
  onSubmitted: () => void;
};

function fileNames(value: unknown): string[] {
  const values = Array.isArray(value) ? value : [value];
  return values.filter(isToolTaskFile).map((file) => file.name);
}

function inputClassName(hasError = false): string {
  const base = "mt-1 h-9 w-full min-w-0 rounded-md border bg-[var(--color-surface-raised)] px-3 text-ui-caption text-[var(--color-text)] outline-none focus-visible:ring-3";
  // 错误态：边框与聚焦环都转危险色，让用户直接定位到出错的字段，
  // 而不是只看到下方一行红字却不知道对应哪个输入框（DESIGN.md §9）。
  return hasError
    ? `${base} border-[var(--color-danger)] focus-visible:border-[var(--color-danger)] focus-visible:ring-[var(--color-danger-soft)]`
    : `${base} border-[var(--color-line)] focus-visible:border-[var(--color-accent)] focus-visible:ring-[var(--color-accent-soft)]`;
}

function renderFieldControl(
  field: ToolField,
  value: unknown,
  onChange: (value: unknown) => void,
  hasError = false,
): ReactNode {
  const common = {
    id: `tool-field-${field.name}`,
    name: field.name,
    placeholder: field.placeholder,
    className: inputClassName(hasError),
    "aria-invalid": hasError || undefined,
    "aria-describedby": field.description ? `tool-field-description-${field.name}` : undefined,
  };

  switch (field.type) {
    case "textarea":
      return (
        <textarea
          {...common}
          value={typeof value === "string" ? value : ""}
          maxLength={field.maxLength}
          rows={4}
          onChange={(event) => onChange(event.currentTarget.value)}
          className={`${common.className} min-h-24 resize-y py-2`}
        />
      );
    case "number":
      return (
        <input
          {...common}
          type="number"
          min={field.min}
          max={field.max}
          value={typeof value === "number" || typeof value === "string" ? value : ""}
          onChange={(event) =>
            onChange(event.currentTarget.value === "" ? "" : Number(event.currentTarget.value))
          }
        />
      );
    case "select":
      return (
        <select
          {...common}
          value={typeof value === "string" ? value : ""}
          onChange={(event) => onChange(event.currentTarget.value)}
        >
          <option value="">请选择</option>
          {field.options?.map((option) => (
            <option key={option.value} value={option.value}>{option.label}</option>
          ))}
        </select>
      );
    case "multi-select":
      return (
        <select
          {...common}
          multiple
          value={Array.isArray(value) ? value.filter((entry): entry is string => typeof entry === "string") : []}
          onChange={(event) =>
            onChange(Array.from(event.currentTarget.selectedOptions, (option) => option.value))
          }
          className={`${common.className} min-h-24 py-2`}
        >
          {field.options?.map((option) => (
            <option key={option.value} value={option.value}>{option.label}</option>
          ))}
        </select>
      );
    case "boolean":
      return (
        <input
          id={common.id}
          name={common.name}
          type="checkbox"
          checked={value === true}
          onChange={(event) => onChange(event.currentTarget.checked)}
          className="h-4 w-4 accent-[var(--color-accent)]"
        />
      );
    case "file":
    case "files": {
      const names = fileNames(value);
      return (
        <div className="mt-1 min-w-0 space-y-1.5">
          <input
            id={common.id}
            name={common.name}
            type="file"
            accept={field.accept}
            multiple={field.type === "files"}
            onChange={(event: ChangeEvent<HTMLInputElement>) => {
              const selected = Array.from(event.currentTarget.files ?? []);
              onChange(field.type === "file" ? selected[0] ?? [] : selected);
              event.currentTarget.value = "";
            }}
            className="block w-full min-w-0 cursor-pointer text-ui-sm text-[var(--color-text-muted)] file:mr-3 file:rounded-sm file:border file:border-[var(--color-line)] file:bg-[var(--color-surface-raised)] file:px-2.5 file:py-1.5 file:text-ui-sm file:text-[var(--color-text)]"
          />
          {names.length ? (
            <span className="block break-all text-ui-sm text-[var(--color-text-muted)]">
              {names.join("、")}
            </span>
          ) : null}
        </div>
      );
    }
    case "datetime":
      return (
        <input
          {...common}
          type="datetime-local"
          value={typeof value === "string" ? value : ""}
          onChange={(event) => onChange(event.currentTarget.value)}
        />
      );
    case "url":
      return (
        <input
          {...common}
          type="url"
          value={typeof value === "string" ? value : ""}
          onChange={(event) => onChange(event.currentTarget.value)}
        />
      );
    case "local-path":
      return (
        <input
          {...common}
          type="text"
          autoComplete="off"
          value={typeof value === "string" ? value : ""}
          onChange={(event) => onChange(event.currentTarget.value)}
        />
      );
    case "text":
      return (
        <input
          {...common}
          type="text"
          maxLength={field.maxLength}
          value={typeof value === "string" ? value : ""}
          onChange={(event) => onChange(event.currentTarget.value)}
        />
      );
  }
}

export default function ToolTaskDialog({
  tool,
  toolFunction,
  onClose,
  onStartToolTask,
  onSubmitted,
}: ToolTaskDialogProps) {
  const [values, setValues] = useState<Record<string, unknown>>(() =>
    initializeToolFieldValues(toolFunction.fields),
  );
  const [errors, setErrors] = useState<ToolFieldErrors>({});
  const [submitting, setSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState("");

  const setFieldValue = (name: string, value: unknown) => {
    setValues((current) => ({
      ...current,
      [name]: value,
    }));
    setErrors((current) => {
      if (!(name in current)) {
        return current;
      }
      const next = { ...current };
      delete next[name];
      return next;
    });
  };

  const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setSubmitting(true);
    setSubmitError("");
    try {
      const nextErrors = await submitToolTask(toolFunction, values, onStartToolTask);
      setErrors(nextErrors);
      if (Object.keys(nextErrors).length === 0) {
        onSubmitted();
      }
    } catch (error) {
      setSubmitError(error instanceof Error ? error.message : "任务填入失败，请重试。");
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <section className="mx-auto w-full max-w-3xl min-w-0" aria-labelledby="tool-task-title">
      <Button
        type="button"
        variant="ghost"
        className="-ml-2 mb-4 text-[var(--color-text-muted)]"
        onClick={onClose}
        disabled={submitting}
      >
        <ArrowLeft aria-hidden="true" />
        返回 {tool.title}
      </Button>

      <div className="border-b border-[var(--color-line)] pb-4">
        <p className="text-ui-sm text-[var(--color-text-faint)]">{tool.title}</p>
        <h2 id="tool-task-title" className="mt-1 text-base font-semibold text-[var(--color-text)]">
          {toolFunction.title}
        </h2>
        <p className="mt-1 text-ui-caption leading-5 text-[var(--color-text-muted)]">
          {toolFunction.description}
        </p>
      </div>

      {toolFunction.access === "write" ? (
        <div className="mt-4 border-l-4 border-[var(--color-danger)] bg-[var(--color-danger-soft)] px-3 py-2.5 text-ui-caption font-semibold text-[var(--color-danger)]" role="note">
          {tool.kind === "opencli-site" && toolFunction.browser === false
            ? "此任务会对目标站点执行写入操作。"
            : "此任务会操作用户本机浏览器。"}
        </div>
      ) : null}

      <form noValidate onSubmit={(event) => void handleSubmit(event)} className="mt-4 space-y-4">
        {toolFunction.fields.length > 0 ? (
          <div className="grid min-w-0 grid-cols-1 gap-x-4 gap-y-4 sm:grid-cols-2">
            {toolFunction.fields.map((field) => (
              <div
                key={field.name}
                className={`min-w-0 ${field.type === "textarea" || field.type === "multi-select" ? "sm:col-span-2" : ""}`}
              >
                {field.type === "boolean" ? (
                  <div className="flex min-h-9 items-center gap-2">
                    {renderFieldControl(field, values[field.name], (value) => setFieldValue(field.name, value), Boolean(errors[field.name]))}
                    <label htmlFor={`tool-field-${field.name}`} className="text-ui-caption font-medium text-[var(--color-text)]">
                      {field.label}
                    </label>
                  </div>
                ) : (
                  <label htmlFor={`tool-field-${field.name}`} className="block min-w-0 text-ui-caption font-medium text-[var(--color-text)]">
                    <span className="flex flex-wrap items-center gap-2">
                      {field.label}
                      {field.required ? <span className="text-ui-sm font-normal text-[var(--color-danger)]">必填</span> : null}
                    </span>
                    {renderFieldControl(field, values[field.name], (value) => setFieldValue(field.name, value), Boolean(errors[field.name]))}
                  </label>
                )}
                {field.description ? (
                  <p id={`tool-field-description-${field.name}`} className="mt-1 text-ui-sm leading-4 text-[var(--color-text-faint)]">
                    {field.description}
                  </p>
                ) : null}
                {errors[field.name] ? (
                  <p className="mt-1 text-ui-sm text-[var(--color-danger)]" role="alert">{errors[field.name]}</p>
                ) : null}
              </div>
            ))}
          </div>
        ) : (
          <p className="text-ui-caption text-[var(--color-text-muted)]">此功能无需额外参数。</p>
        )}

        {submitError ? (
          <p className="text-ui-caption text-[var(--color-danger)]" role="alert">{submitError}</p>
        ) : null}
        <div className="flex justify-end border-t border-[var(--color-line)] pt-4">
          <Button type="submit" className="workspace-admin-primary" disabled={submitting}>
            {submitting ? <LoaderCircle aria-hidden="true" className="h-4 w-4 animate-spin" /> : null}
            {submitting ? "正在填入…" : "开始任务"}
          </Button>
        </div>
      </form>
    </section>
  );
}
