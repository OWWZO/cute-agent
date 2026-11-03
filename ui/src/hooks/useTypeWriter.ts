import {useEffect, useState, useMemo} from 'react';
import TypeWriterCore from './TypeWriterCore';

interface UseWriterOptions {
    maxStepSeconds?: number; // 将 maxStepSeconds 定义为可选的
}

export const useTypeWriter = (
  {text, options}:{ text: string, options?: UseWriterOptions }
): readonly [string] => {
  const [typedText, setTypedText] = useState('');

  // 只依赖原始值：调用方传内联 options 对象时不会每次渲染都重建 core。
  const maxStepSeconds = options?.maxStepSeconds;
  const typingCore = useMemo(
    () => new TypeWriterCore(
      {
        onConsume: (str: string) => setTypedText(prev => prev + str),
        maxStepSeconds,
      }
    ),
    [maxStepSeconds]
  );

  useEffect(
    () => {
      typingCore.onRendered(); // 渲染完成 => 清空定时器
      typingCore.add(text);
      typingCore.start();

      return () => typingCore.onRendered(); // 渲染完成 => 清空定时器
    },
    [text, typingCore]
  );

  return [typedText];
};
