import { createContext, useContext } from "react";
import type { GenUiActionContext } from "./genUiActionBus";

export type GenUiRenderContextValue = GenUiActionContext;

const GenUiRenderContext = createContext<GenUiRenderContextValue>({});

export function useGenUiRenderContext(): GenUiRenderContextValue {
  return useContext(GenUiRenderContext);
}
