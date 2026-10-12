import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "Mnote · 视频转写",
  description: "把有权处理的视频留成文字。Mnote 私有视频转写工作区。",
  other: {
    "codex-preview": "development",
  },
  icons: {
    icon: "/favicon.svg",
    shortcut: "/favicon.svg",
  },
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="zh-CN">
      <body className="antialiased">{children}</body>
    </html>
  );
}
