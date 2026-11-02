import { cn } from "@/lib/utils";

type AnimatedOrbProps = {
  className?: string;
  variant?: "default" | "red";
  size?: number;
};

export function AnimatedOrb({
  className,
  variant = "default",
  size = 32,
}: AnimatedOrbProps) {
  const colors =
    variant === "red"
      ? {
        bg: "#fef2f2",
        circle1: "#ef4444",
        circle2: "#f87171",
        circle3: "#dc2626",
        circle4: "#fca5a5",
        circle5: "#fb7185",
      }
      : {
        bg: "#cff1f4",
        circle1: "#9e9fef",
        circle2: "#c471ec",
        circle3: "#9bc761",
        circle4: "#ccd4f2",
        circle5: "#f472b6",
      };

  const blurAmount = Math.max(6, size * 0.15);
  // 每个圆球的尺寸、透明度、颜色集中在一处，增删或调整顺序不会漏改某个节点。
  const circles = [
    {
      key: "1",
      size: size * 0.45,
      opacity: 0.9,
      color: colors.circle1,
    },
    {
      key: "2",
      size: size * 0.35,
      opacity: 0.85,
      color: colors.circle2,
    },
    {
      key: "3",
      size: size * 0.5,
      opacity: 0.9,
      color: colors.circle3,
    },
    {
      key: "4",
      size: size * 0.25,
      opacity: 0.8,
      color: colors.circle4,
    },
    {
      key: "5",
      size: size * 0.3,
      opacity: 0.85,
      color: colors.circle5,
    },
  ];

  return (
    <div
      className={cn("cute-orb-hue relative overflow-hidden rounded-full", className)}
      style={{
        width: size,
        height: size,
        backgroundColor: colors.bg,
        boxShadow: "rgba(17, 12, 46, 0.15) 0px 48px 100px 0px",
      }}
      aria-hidden="true"
    >
      <div className="cute-orb-hue-inner absolute inset-0 flex items-center justify-center">
        {/* 模糊值恒定，放在静态层里；上面两层只做 hue 旋转，避免每帧重算滤镜参数。 */}
        <div
          className="absolute inset-0 flex items-center justify-center"
          style={{ filter: `blur(${blurAmount}px)` }}
        >
          {circles.map((circle) => (
            <div
              className={`orb-circle-${circle.key} absolute rounded-full`}
              key={circle.key}
              style={{
                width: circle.size,
                height: circle.size,
                opacity: circle.opacity,
                backgroundColor: circle.color,
              }}
            />
          ))}
        </div>
      </div>

      <div className="cute-orb-highlight pointer-events-none absolute inset-0 rounded-full" />
    </div>
  );
}
