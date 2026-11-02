import React from 'react';
import { cn } from "@/lib/utils";
import LoadingSpinner from '../LoadingSpinner';
import { AnimatedOrb } from '../chat/AnimatedOrb';

interface LoadingProps {
  loading?: boolean;
  className?: string;
  children?: React.ReactNode;
  /** 用品牌球体替代通用 spinner；页面级空等场景更统一 */
  branded?: boolean;
}

const LOADING_CONTAINER_CLASS = 'flex flex-col items-center justify-center w-full';
const TEXT_CLASS = 'mt-6 text-lg text-[var(--chat-text-soft)]';

/**
 * Loading 组件
 *
 * @param loading - 是否显示加载动画
 * @param className - 自定义类名
 * @param children - 自定义加载文本
 * @param branded - 使用 AnimatedOrb 品牌球体而非通用 spinner
 */
const Loading: CuteType.FC<LoadingProps> = React.memo(({ loading, className, children, branded = false }) => {
  if (!loading) return null;

  const label = children || '加载中';

  return (
    <div
      className={cn(LOADING_CONTAINER_CLASS, className)}
      role="status"
      aria-live="polite"
    >
      {branded ? (
        // 球体自带呼吸感，不再叠加 spinner：两个动画同时转会互相干扰。
        <>
          <AnimatedOrb size={40} />
          <p className={TEXT_CLASS}>{label}</p>
        </>
      ) : (
        <LoadingSpinner className='text-[32px]'>
          <p className={TEXT_CLASS}>{label}</p>
        </LoadingSpinner>
      )}
    </div>
  );
});

Loading.displayName = 'Loading';

export default Loading;
