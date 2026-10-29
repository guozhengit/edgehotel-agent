# -*- coding: utf-8 -*-
"""--auth-token Bearer 门禁冒烟：无 token/错 token → 401，对 token → 全链路 200。
用法: 先以 `--http <port> --auth-token <token>` 启动 server，再
  python mcp-smoke-auth.py [port] [token]
"""
import json
import sys
import urllib.error
import urllib.request

PORT = sys.argv[1] if len(sys.argv) > 1 else "8602"
TOKEN = sys.argv[2] if len(sys.argv) > 2 else "test-token-123"
BASE = f"http://localhost:{PORT}/mcp"


def raw(o, token=None, sid=None):
    headers = {"Content-Type": "application/json", "Accept": "application/json, text/event-stream"}
    if token:
        headers["Authorization"] = token
    if sid:
        headers["Mcp-Session-Id"] = sid
    req = urllib.request.Request(BASE, data=json.dumps(o, ensure_ascii=False).encode("utf-8"),
                                 headers=headers, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            return r.status, r.read().decode("utf-8"), r.headers.get("Mcp-Session-Id")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8"), None


def expect(name, cond, detail=""):
    print(("  [PASS] " if cond else "  [FAIL] ") + name + (" | " + detail if detail else ""))
    return cond


def main():
    ok = 0
    init = {"jsonrpc": "2.0", "id": 1, "method": "initialize",
            "params": {"protocolVersion": "2025-06-18", "capabilities": {},
                       "clientInfo": {"name": "auth-smoke", "version": "1"}}}

    s, _, _ = raw(init)
    ok += expect("无 token → 401", s == 401, f"got {s}")
    s, _, _ = raw(init, token="Bearer wrong-token")
    ok += expect("错 token → 401", s == 401, f"got {s}")
    s, body, sid = raw(init, token="Bearer " + TOKEN)
    ok += expect("对 token → 200", s == 200, f"got {s}")
    ok += expect("initialize 握手", s == 200 and '"serverInfo"' in body)
    if s == 200:
        raw({"jsonrpc": "2.0", "method": "notifications/initialized", "params": {}},
            token="Bearer " + TOKEN, sid=sid)
        s, body, _ = raw({"jsonrpc": "2.0", "id": 2, "method": "tools/list", "params": {}},
                         token="Bearer " + TOKEN, sid=sid)
        ok += expect("tools/list 携带会话 200", s == 200 and "hotel_room_status" in body)
        s, body, _ = raw({"jsonrpc": "2.0", "id": 3, "method": "tools/call",
                          "params": {"name": "hotel_price_quote", "arguments": {"room_type": "standard"}}},
                         token="Bearer " + TOKEN, sid=sid)
        ok += expect("tools/call 200", s == 200 and "599" in body)
        # structuredContent 与 annotations 暴露检查（新协议特性前向输出）
        s, body, _ = raw({"jsonrpc": "2.0", "id": 4, "method": "tools/list", "params": {}},
                         token="Bearer " + TOKEN, sid=sid)
        tl = json.loads(_extract_data(body) or "{}")
        tools = {t["name"]: t for t in tl.get("result", {}).get("tools", [])}
        ok += expect("inputSchema 含 required", "required" in json.dumps(tools.get("hotel_price_quote", {}).get("inputSchema", {})))
        ok += expect("annotations 已声明", "annotations" in json.dumps(tools.get("complaint_escalate", {})))
        s, body, _ = raw({"jsonrpc": "2.0", "id": 5, "method": "tools/call",
                          "params": {"name": "hotel_price_quote", "arguments": {"room_type": "standard"}}},
                         token="Bearer " + TOKEN, sid=sid)
        ok += expect("structuredContent 已输出", "structuredContent" in body)
    print(f"\n== 鉴权冒烟结果: {ok}/9 PASS ==")
    sys.exit(0 if ok == 9 else 1)


def _extract_data(body):
    if "data:" in body[:120]:
        for line in body.splitlines():
            if line.startswith("data:"):
                return line[5:].strip()
    return body


if __name__ == "__main__":
    main()
