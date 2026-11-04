import { describe, expect, it, vi } from "vitest";

import {
  nextSpeechRecognitionCommand,
  trySpeechRecognitionCommand,
} from "./useSpeechRecognition";

describe("speech recognition toggle state", () => {
  it("blocks a second toggle while start/stop is pending", () => {
    expect(nextSpeechRecognitionCommand(false, false)).toBe("start");
    expect(nextSpeechRecognitionCommand(true, false)).toBe("stop");
    expect(nextSpeechRecognitionCommand(false, true)).toBeNull();
    expect(nextSpeechRecognitionCommand(true, true)).toBeNull();
  });

  it("returns start/stop errors instead of leaking them to the click handler", () => {
    const failure = new Error("NotAllowedError");
    const recognition = {
      start: vi.fn(() => {
        throw failure;
      }),
      stop: vi.fn(),
    };

    expect(trySpeechRecognitionCommand(recognition, "start")).toBe(failure);
    expect(trySpeechRecognitionCommand(recognition, "stop")).toBeNull();
  });
});
