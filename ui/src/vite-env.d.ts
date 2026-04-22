/// <reference types="vite/client" />

/** Vite 注入的前端环境变量。 */
interface ImportMetaEnv {
  /** 后端 HTTP 基础地址。 */
  readonly VITE_AGENT_HTTP_BASE?: string;
  /** 后端 WebSocket 基础地址。 */
  readonly VITE_AGENT_WS_BASE?: string;
}

/** Vite ImportMeta 扩展。 */
interface ImportMeta {
  /** 前端构建时环境变量集合。 */
  readonly env: ImportMetaEnv;
}
