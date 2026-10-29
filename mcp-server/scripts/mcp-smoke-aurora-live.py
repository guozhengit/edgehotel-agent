# -*- coding: utf-8 -*-
"""aurora 真实 PMS 联调冒烟（服务在线 + 租户头 X-Merchant-Id）。
实测数据（2026-10-29 实测）：库存 sku1=20；价格 sku1=Superior King Room/1299；
订单 ORD202610031157216C19C825（COMPLETED/S4冒烟）；会员直连需登录态 -> 如实降级。"""
import json
import sys
import urllib.request

BASE = "http://localhost:8601/mcp"
H = {"Content-Type": "application/json", "Accept": "application/json, text/event-stream"}


def call(o, sid=None):
    req = urllib.request.Request(BASE, data=json.dumps(o, ensure_ascii=False).encode("utf-8"), headers=H, method="POST")
    if sid:
        req.add_header("Mcp-Session-Id", sid)
    with urllib.request.urlopen(req, timeout=15) as r:
        b = r.read().decode("utf-8")
        sid2 = r.headers.get("Mcp-Session-Id")
        if not b.strip():
            return None, sid2
        if "data:" in b[:120]:
            for line in b.splitlines():
                if line.startswith("data:"):
                    return json.loads(line[5:].strip()), sid2
            raise RuntimeError("SSE 无 data 行: " + b[:200])
        return json.loads(b), sid2


def main():
    r, sid = call({"jsonrpc": "2.0", "id": 1, "method": "initialize",
                   "params": {"protocolVersion": "2025-06-18", "capabilities": {},
                              "clientInfo": {"name": "aurora-live", "version": "1"}}})
    print("[OK] initialize:", json.dumps(r.get("result", {}).get("serverInfo"), ensure_ascii=False))
    call({"jsonrpc": "2.0", "method": "notifications/initialized", "params": {}}, sid)

    cases = [
        ("hotel_room_status", {"skuId": "1"}, "available=20"),
        ("hotel_price_quote", {"skuId": "1"}, "Superior King Room"),
        ("hotel_price_quote", {"skuId": "1"}, "1299"),
        ("hotel_booking_lookup", {"order_no": "ORD202610031157216C19C825"}, "S4冒烟"),
        ("hotel_booking_lookup", {"order_no": "ORD202610031157216C19C825"}, "guestMobile"),  # 反向断言：不应出现手机号
        ("member_profile_query", {"member_no": "M100001"}, "Demo Guest"),
        ("member_profile_query", {"member_no": "M100001"}, "phoneMasked"),  # 反向断言：不应出现明文手机
        ("member_profile_query", {"member_no": "M-UNKNOWN"}, "NOT_FOUND"),
    ]
    ok = 0
    for i, (n, args, expect) in enumerate(cases):
        r, _ = call({"jsonrpc": "2.0", "id": 10 + i, "method": "tools/call",
                     "params": {"name": n, "arguments": args}}, sid)
        res = r.get("result", {})
        text = res.get("content", [{}])[0].get("text", "") if res.get("content") else ""
        if expect in ("guestMobile", "phoneMasked"):
            # 反向断言：明文手机不得出现（服务端掩码 phoneMasked=138****0001 是合法脱敏输出）
            hit = "13800001234" not in text and "13800004444" not in text
            tag = "脱敏PASS" if hit else "FAIL"
        else:
            hit = expect in text
            tag = "PASS" if hit else "FAIL"
        ok += 1 if hit else 0
        print(f"[{tag}] {n} expect[{expect}] -> {text.replace(chr(10), ' | ')}")

    print(f"\n== aurora 真实 PMS 联调结果: {ok}/{len(cases)} PASS ==")
    return 0 if ok == len(cases) else 1


if __name__ == "__main__":
    sys.exit(main())
