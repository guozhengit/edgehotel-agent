# -*- coding: utf-8 -*-
"""aurora 模式离线降级冒烟：真实 PMS 未启动时应返回 PMS_UNAVAILABLE（防静默失败）。"""
import json
import urllib.request

BASE = "http://localhost:8601/mcp"
H = {"Content-Type": "application/json", "Accept": "application/json, text/event-stream"}

def call(o, sid=None):
    req = urllib.request.Request(BASE, data=json.dumps(o, ensure_ascii=False).encode("utf-8"), headers=H, method="POST")
    if sid:
        req.add_header("Mcp-Session-Id", sid)
    with urllib.request.urlopen(req, timeout=10) as r:
        b = r.read().decode("utf-8")
        sid = r.headers.get("Mcp-Session-Id")
        if not b.strip():
            return None, sid
        # SSE 响应（text/event-stream）：取 data: 行
        if "data:" in b[:120] or b.lstrip().startswith("event:"):
            for line in b.splitlines():
                if line.startswith("data:"):
                    return json.loads(line[5:].strip()), sid
            raise RuntimeError("SSE 无 data 行: " + b[:200])
        return json.loads(b), sid

r, sid = call({"jsonrpc": "2.0", "id": 1, "method": "initialize",
               "params": {"protocolVersion": "2025-06-18", "capabilities": {},
                          "clientInfo": {"name": "aurora-offline", "version": "1"}}})
print("[OK] initialize:", r.get("result", {}).get("serverInfo"))
call({"jsonrpc": "2.0", "method": "notifications/initialized", "params": {}}, sid)

ok = 0
for i, (n, args) in enumerate([
        ("hotel_room_status", {"skuId": "1001"}),
        ("hotel_price_quote", {"skuId": "2001"}),
        ("hotel_booking_lookup", {"order_no": "NO202610010001"}),
        ("member_profile_query", {"member_no": "M20260001"}),
]):
    r, _ = call({"jsonrpc": "2.0", "id": 10 + i, "method": "tools/call",
                 "params": {"name": n, "arguments": args}}, sid)
    res = r.get("result", {})
    text = res.get("content", [{}])[0].get("text", "").replace("\n", " | ") if res.get("content") else ""
    is_deg = "PMS_UNAVAILABLE" in text
    ok += 1 if is_deg else 0
    print(f"[{'PASS' if is_deg else 'FAIL'}] {n}: {text}")

print(f"\n== 离线降级结果: {ok}/4 PASS（均须返回 PMS_UNAVAILABLE，不编造数据） ==")
