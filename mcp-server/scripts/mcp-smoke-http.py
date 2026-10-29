# -*- coding: utf-8 -*-
"""EdgeHotel MCP Server Streamable HTTP 冒烟：POST /mcp JSON-RPC。"""
import json
import sys
import urllib.request

BASE = "http://localhost:8601/mcp"
HDRS = {"Content-Type": "application/json", "Accept": "application/json, text/event-stream"}

def call(obj, session=None):
    data = json.dumps(obj, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(BASE, data=data, headers=HDRS, method="POST")
    if session:
        req.add_header("Mcp-Session-Id", session)
    with urllib.request.urlopen(req, timeout=10) as r:
        body = r.read().decode("utf-8")
        sid = r.headers.get("Mcp-Session-Id")
        if not body.strip():
            return None, sid  # notification 无响应体
        # 兼容 SSE 响应（data: {...}）与纯 JSON
        if body.lstrip().startswith("event:") or "data:" in body[:80]:
            for line in body.splitlines():
                if line.startswith("data:"):
                    return json.loads(line[5:].strip()), sid
            raise RuntimeError("SSE 无 data 行: " + body[:200])
        return json.loads(body), sid

r, sid = call({"jsonrpc": "2.0", "id": 1, "method": "initialize",
               "params": {"protocolVersion": "2025-06-18", "capabilities": {},
                          "clientInfo": {"name": "http-smoke", "version": "0.1"}}})
print(f"[OK] initialize: {r.get('result', {}).get('serverInfo')} session={sid}")

call({"jsonrpc": "2.0", "method": "notifications/initialized", "params": {}}, sid)

r, _ = call({"jsonrpc": "2.0", "id": 2, "method": "tools/list", "params": {}}, sid)
names = [t["name"] for t in r.get("result", {}).get("tools", [])]
print(f"[OK] tools/list: {names}")

cases = [
    ("hotel_room_status", {"date": "2026-10-31", "room_type": "suite"}),
    ("hotel_price_quote", {"room_type": "deluxe"}),
    ("hotel_booking_lookup", {"order_id": "B20261002"}),
    ("member_profile_query", {"member_id": "M-1002"}),
    ("complaint_escalate", {"guest_name": "王先生", "room_no": "805", "issue": "Wi-Fi 不稳"}),
    ("marketing_copy_generate", {"scenario": "promotion", "guest_name": "张女士"}),
]
for i, (name, args) in enumerate(cases):
    r, _ = call({"jsonrpc": "2.0", "id": 10 + i, "method": "tools/call",
                 "params": {"name": name, "arguments": args}}, sid)
    res = r.get("result", {})
    text = res.get("content", [{}])[0].get("text", "") if res.get("content") else ""
    flag = "OK" if not res.get("isError") else "ERR"
    print(f"[{flag}] {name}: {text.replace(chr(10), ' | ')}")

print("\n== HTTP 冒烟完成 ==")
