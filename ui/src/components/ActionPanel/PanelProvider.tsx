import { createContext, useContext } from "react";

export type PanelContextValue = {
  scrollToBottom?: () => void;
};

const PanelContext = createContext<PanelContextValue | null>(null);

export const usePanelContext = () => {
  return useContext(PanelContext);
};

export default PanelContext.Provider;
