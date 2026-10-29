# EdgeHotel-Agent 回归报告

回归集（eval_cases）: 3 条
  - [empty_result] hotel_room_status x9 | {"date":"2026-11-08"}
  - [empty_result] restaurant_table_status x1 | {"date":"2026-10-31","slot":"19:00",
  - [empty_result] restaurant_table_status x1 | {"date":"2024-10-28","slot":"19:00",

== 回归执行（场景集 12 次）==
[1] 桌态正常 calls=1 warn=0 ap=0
[2] 桌态正常 calls=1 warn=0 ap=0
[3] 菜价查询 calls=1 warn=0 ap=0
[4] 菜价查询 calls=1 warn=0 ap=0
[5] 未知日期-回归 calls=1 warn=0 ap=0
[6] 未知日期-回归 calls=1 warn=0 ap=0
[7] 订单查询 calls=1 warn=0 ap=0
[8] 订单查询 calls=1 warn=0 ap=0
[9] 会员脱敏 calls=1 warn=0 ap=0
[10] 会员脱敏 calls=1 warn=0 ap=0
[11] 客诉升级-回归 calls=1 warn=0 ap=1
[12] 客诉升级-回归 calls=1 warn=0 ap=1

===== 回归报告（12 次请求，79s）=====
工具调用: 12 | 静默失败触发(设计内): 0 | 审批留痕: 2
未检测静默失败: 0 -> 静默失败率 0.0% PASS
