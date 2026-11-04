import type { ToolAccess, ToolCategory } from "./toolCatalog";

export type OpenCliArgumentType =
  | "str"
  | "string"
  | "bool"
  | "boolean"
  | "int"
  | "number";

export type OpenCliArgumentDefinition = {
  name: string;
  type: OpenCliArgumentType;
  required: boolean;
  help: string;
  default?: string | number | boolean;
  positional?: boolean;
  valueRequired?: boolean;
  choices?: string[];
};

export type OpenCliCommandDefinition = {
  site: string;
  name: string;
  description: string;
  access: ToolAccess;
  browser: boolean;
  args: OpenCliArgumentDefinition[];
};

export type OpenCliSiteDefinition = {
  site: string;
  title: string;
  description: string;
  category: ToolCategory;
  iconKey: string;
  requiresLogin?: boolean;
  commands: OpenCliCommandDefinition[];
};
