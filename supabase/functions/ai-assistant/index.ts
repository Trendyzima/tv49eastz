// TV 49 East AI assistant. Deploy as a Supabase Edge Function.
// Secrets: AI_GATEWAY_API_KEY. Never ship this key in Android.
const cors = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: cors });
  if (req.method !== "POST") return new Response(JSON.stringify({ error: "POST required" }), { status: 405, headers: { ...cors, "Content-Type": "application/json" } });

  const auth = req.headers.get("Authorization");
  if (!auth?.startsWith("Bearer ")) return json({ error: "Authentication required" }, 401);

  const jwt = auth.slice(7);
  const supabaseUrl = Deno.env.get("SUPABASE_URL");
  const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  const gatewayKey = Deno.env.get("AI_GATEWAY_API_KEY");
  const model = Deno.env.get("AI_MODEL") || "openai/gpt-5.5-fast";
  if (!supabaseUrl || !serviceKey || !gatewayKey) return json({ error: "AI service is not configured" }, 503);

  const userResponse = await fetch(`${supabaseUrl}/auth/v1/user`, {
    headers: { apikey: serviceKey, Authorization: `Bearer ${jwt}` },
  });
  if (!userResponse.ok) return json({ error: "Invalid session" }, 401);
  const user = await userResponse.json();

  const payload = await req.json().catch(() => null);
  const feature = String(payload?.feature || "assistant").slice(0, 40);
  const prompt = String(payload?.prompt || "").trim().slice(0, 12000);
  if (!prompt) return json({ error: "prompt is required" }, 400);

  const system = "You are TV 49 East AI, a helpful assistant inside a native social and live-TV app. Be concise, factual, safe, and useful. Never claim to have performed an action you cannot perform.";
  const gateway = await fetch("https://ai-gateway.vercel.sh/v1/chat/completions", {
    method: "POST",
    headers: { Authorization: `Bearer ${gatewayKey}`, "Content-Type": "application/json" },
    body: JSON.stringify({ model, messages: [{ role: "system", content: system }, { role: "user", content: prompt }], temperature: 0.4, max_tokens: 800 }),
  });
  const result = await gateway.json().catch(() => ({}));
  if (!gateway.ok) return json({ error: "AI provider error", detail: result?.error?.message || "request failed" }, 502);

  const text = result?.choices?.[0]?.message?.content || "";
  const usage = result?.usage || {};
  await fetch(`${supabaseUrl}/rest/v1/ai_usage`, {
    method: "POST",
    headers: { apikey: serviceKey, Authorization: `Bearer ${serviceKey}`, "Content-Type": "application/json", Prefer: "return=minimal" },
    body: JSON.stringify({ user_id: user.id, feature, model, input_tokens: usage.prompt_tokens || 0, output_tokens: usage.completion_tokens || 0 }),
  });
  return json({ text, model, usage });
});

function json(value: unknown, status = 200) {
  return new Response(JSON.stringify(value), { status, headers: { ...cors, "Content-Type": "application/json" } });
}
