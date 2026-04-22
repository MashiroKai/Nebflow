import type { ToolDefinition } from "../shared/protocol.js";

export const READ_FILE: ToolDefinition = {
  name: "read_file",
  description: "读取文件内容",
  input_schema: {
    type: "object",
    properties: {
      path: { type: "string", description: "文件路径（相对于项目根目录或绝对路径）" },
    },
    required: ["path"],
  },
};

export const WRITE_FILE: ToolDefinition = {
  name: "write_file",
  description: "写入文件（不存在则创建，存在则覆盖）",
  input_schema: {
    type: "object",
    properties: {
      path: { type: "string", description: "文件路径（相对于项目根目录或绝对路径）" },
      content: { type: "string", description: "要写入的内容" },
    },
    required: ["path", "content"],
  },
};

export const BASH: ToolDefinition = {
  name: "bash",
  description: "执行 shell 命令",
  input_schema: {
    type: "object",
    properties: {
      command: { type: "string", description: "要执行的命令" },
      timeout: { type: "number", description: "超时毫秒数（默认 30000）" },
    },
    required: ["command"],
  },
};

export const GLOB: ToolDefinition = {
  name: "glob",
  description: "按模式搜索文件名（如 **/*.ts）",
  input_schema: {
    type: "object",
    properties: {
      pattern: { type: "string", description: "文件名模式（如 *.ts, **/*.md）" },
    },
    required: ["pattern"],
  },
};

export const GREP: ToolDefinition = {
  name: "grep",
  description: "在文件内容中搜索匹配的文本（正则表达式）",
  input_schema: {
    type: "object",
    properties: {
      pattern: { type: "string", description: "搜索模式（正则表达式）" },
      path: { type: "string", description: "搜索路径（默认 .）" },
    },
    required: ["pattern"],
  },
};

export const ALL_TOOLS: ToolDefinition[] = [
  READ_FILE,
  WRITE_FILE,
  BASH,
  GLOB,
  GREP,
];
