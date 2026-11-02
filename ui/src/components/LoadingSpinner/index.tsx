import { cn } from "@/lib/utils";

interface LoadingSpinnerProps {
  color?: string;
  className?: string;
  children?: React.ReactNode;
  size?: "sm" | "md" | "lg";
}

const sizeMap = {
  sm: "w-4 h-4",
  md: "w-5 h-5",
  lg: "w-6 h-6",
};

const LoadingSpinner: React.FC<LoadingSpinnerProps> = ({
  className,
  children,
  color = "var(--color-accent)",
  size = "md",
}) => {
  return (
    <div className="flex items-center gap-2">
      {/* className 作用于 spinner 本体：调用方传 h-4 w-4 之类的尺寸类时，
          环形随容器一起缩放，而不是外框缩了、内环仍按 sizeMap 溢出。 */}
      <div className={cn("relative shrink-0", sizeMap[size], className)}>
        <div
          className="cute-spinner absolute inset-0 rounded-full"
          style={{ color }}
        />
      </div>
      {children}
    </div>
  );
};

export default LoadingSpinner;
