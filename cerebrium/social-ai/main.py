import json
import os
from typing import Any
from urllib.request import Request, urlopen


def _upstream(task: str, payload: dict[str, Any]) -> dict[str, Any]:
    url = os.getenv("AI_UPSTREAM_URL", "").strip()
    if not url:
        return {"ok": False, "configured": False, "task": task, "message": "AI_UPSTREAM_URL is not configured"}
    body = json.dumps({"task": task, "input": payload}).encode("utf-8")
    request = Request(url, data=body, method="POST", headers={
        "Content-Type": "application/json",
        "Authorization": f"Bearer {os.getenv('AI_UPSTREAM_KEY', '')}",
    })
    with urlopen(request, timeout=45) as response:
        return json.loads(response.read().decode("utf-8"))


def health() -> dict[str, Any]:
    return {"ok": True, "service": "tv49-social-ai", "provider_configured": bool(os.getenv("AI_UPSTREAM_URL"))}


def run(task: str = "health", payload: dict[str, Any] | None = None) -> dict[str, Any]:
    payload = payload or {}
    if task == "health":
        return health()
    if task not in {"moderate_post", "embed_text", "rank_feed", "analyze_media", "translate_post"}:
        return {"ok": False, "error": "unsupported_task", "allowed": ["moderate_post", "embed_text", "rank_feed", "analyze_media", "translate_post"]}
    return _upstream(task, payload)
