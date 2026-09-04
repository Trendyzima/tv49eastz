// Cloudflare's generated Worker Response.json() currently resolves to an object-like JSON type.
// Federation validates remote ActivityPub documents dynamically, so those documents are intentionally
// represented as `any` at this boundary and validated before use.
export {};

declare global {
  interface Response {
    json<T = any>(): Promise<T>;
  }
}
