import {
  type RefObject,
  useCallback,
  useEffect,
  useRef,
  useState,
} from "react";

interface PromptInputSpeechRecognition extends EventTarget {
  continuous: boolean;
  interimResults: boolean;
  lang: string;
  start(): void;
  stop(): void;
  onstart: ((this: PromptInputSpeechRecognition, ev: Event) => void) | null;
  onend: ((this: PromptInputSpeechRecognition, ev: Event) => void) | null;
  onresult:
    | ((this: PromptInputSpeechRecognition, ev: PromptInputSpeechRecognitionEvent) => void)
    | null;
  onerror:
    | ((this: PromptInputSpeechRecognition, ev: PromptInputSpeechRecognitionErrorEvent) => void)
    | null;
}

interface PromptInputSpeechRecognitionEvent extends Event {
  results: PromptInputSpeechRecognitionResultList;
  resultIndex: number;
}

type PromptInputSpeechRecognitionResultList = {
  readonly length: number;
  item(index: number): PromptInputSpeechRecognitionResult;
  [index: number]: PromptInputSpeechRecognitionResult;
};

type PromptInputSpeechRecognitionResult = {
  readonly length: number;
  item(index: number): PromptInputSpeechRecognitionAlternative;
  [index: number]: PromptInputSpeechRecognitionAlternative;
  isFinal: boolean;
};

type PromptInputSpeechRecognitionAlternative = {
  transcript: string;
  confidence: number;
};

interface PromptInputSpeechRecognitionErrorEvent extends Event {
  error: string;
}

type PromptInputSpeechRecognitionConstructor =
  new () => PromptInputSpeechRecognition;

declare global {
  interface Window {
    // Web Speech API 只在部分浏览器提供，必须按可选能力声明。
    SpeechRecognition?: PromptInputSpeechRecognitionConstructor;
    webkitSpeechRecognition?: PromptInputSpeechRecognitionConstructor;
  }
}

type UseSpeechRecognitionOptions = {
  textareaRef?: RefObject<HTMLTextAreaElement | null>;
  onTranscriptionChange?: (text: string) => void;
  /** BCP 47 语言标签；缺省跟随浏览器语言。 */
  lang?: string;
};

/**
 * 语音识别能力探测、事件绑定和启停控制统一放到 Hook 中。
 */
export function useSpeechRecognition(options: UseSpeechRecognitionOptions) {
  const { textareaRef, onTranscriptionChange, lang } = options;
  const [isListening, setIsListening] = useState(false);
  const [recognition, setRecognition] = useState<PromptInputSpeechRecognition | null>(null);
  const recognitionRef = useRef<PromptInputSpeechRecognition | null>(null);

  useEffect(() => {
    if (typeof window === "undefined") {
      return;
    }
    const SpeechRecognitionConstructor =
      window.SpeechRecognition ?? window.webkitSpeechRecognition;
    if (!SpeechRecognitionConstructor) {
      return;
    }

    const speechRecognition = new SpeechRecognitionConstructor();

    speechRecognition.continuous = true;
    // 只在 onresult 里消费 isFinal 结果，中间态没有用处。
    speechRecognition.interimResults = false;
    speechRecognition.lang = lang ?? navigator.language;

    speechRecognition.onstart = () => {
      setIsListening(true);
    };

    speechRecognition.onend = () => {
      setIsListening(false);
    };

    speechRecognition.onresult = (event) => {
      let finalTranscript = "";

      for (let index = event.resultIndex; index < event.results.length; index += 1) {
        const result = event.results[index];
        if (result.isFinal) {
          finalTranscript += result[0]?.transcript ?? "";
        }
      }

      if (finalTranscript && textareaRef?.current) {
        const textarea = textareaRef.current;
        const currentValue = textarea.value;
        const nextValue =
          currentValue + (currentValue ? " " : "") + finalTranscript;

        textarea.value = nextValue;
        textarea.dispatchEvent(new Event("input", { bubbles: true }));
        onTranscriptionChange?.(nextValue);
      }
    };

    speechRecognition.onerror = (event) => {
      console.error("Speech recognition error:", event.error);
      setIsListening(false);
    };

    recognitionRef.current = speechRecognition;
    setRecognition(speechRecognition);

    return () => {
      if (recognitionRef.current) {
        recognitionRef.current.stop();
      }
    };
  }, [lang, onTranscriptionChange, textareaRef]);

  const toggleListening = useCallback(() => {
    if (!recognition) {
      return;
    }

    if (isListening) {
      recognition.stop();
    } else {
      recognition.start();
    }
  }, [isListening, recognition]);

  return {
    isListening,
    recognition,
    toggleListening,
  };
}
