import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  // 机器人与 Web 同进程：启动时在 instrumentation.ts 里拉起 QQ / SD / 队列等后台服务。
  serverExternalPackages: ["ws"],
  /**
   * 机器人目录里有 9 GB 生成图、还有正在被 Java 版占用的 `data/bot.lock`。
   * 这些目录一律排除在文件追踪之外：它们在运行时由进程自己读写，不参与打包；
   * 不排除的话追踪器会去读被锁定的 `data/bot.lock`，直接 panic（os error 33）。
   *
   * 另注：追踪器还会**静态求值**模块里可折叠的表达式，碰到 `lib/core/{chains,commands-sd}.ts` 里的
   * `BigInt(...)` 与 `0n` 比较会崩（`Cannot mix BigInt and other types`）。所以 `/api/**` 里对
   * 组装根（`lib/core/bot.ts`）用的是**动态 import**，把它排除在追踪图之外 —— 见那个路由文件的注释。
   */
  outputFileTracingExcludes: {
    "*": ["data/**", "logs/**", "backup/**", "work/**", "maps/**", "public/**"],
  },
  experimental: {},
};

export default nextConfig;
