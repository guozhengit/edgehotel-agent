# EdgeHotel-Agent 回归报告

回归集（eval_cases）: 3 条
  - [empty_result] hotel_room_status x7 | {"date":"2026-11-08"}
  - [empty_result] restaurant_table_status x1 | {"date":"2026-10-31","slot":"19:00",
  - [empty_result] restaurant_table_status x1 | {"date":"2024-10-28","slot":"19:00",

== 回归执行（场景集 12 次）==
[1] 房态正常 calls=1 warn=0 ap=0
[2] 房态正常 calls=1 warn=0 ap=0
[3] 价格正常 calls=1 warn=0 ap=0
[4] 价格正常 calls=1 warn=0 ap=0
[5] 未知日期-回归 calls=1 warn=1 ap=0
[6] 未知日期-回归 calls=1 warn=1 ap=0
[7] 投诉升级-回归 calls=1 warn=0 ap=1
[8] 投诉升级-回归 calls=1 warn=0 ap=1
[9] 话术生成-回归 calls=1 warn=0 ap=1
[10] 话术生成-回归 calls=1 warn=0 ap=1
[11] 订单查询 calls=1 warn=0 ap=0
[12] 订单查询 calls=1 warn=0 ap=0
[13] 订单不存在-回归 calls=1 warn=0 ap=0
[14] 订单不存在-回归 calls=1 warn=0 ap=0
[15] 多工具-组合 calls=1 warn=0 ap=0
[16] 多工具-组合 calls=1 warn=0 ap=0

===== 回归报告（16 次请求，124s）=====
工具调用: 16 | 静默失败触发(设计内): 2 | 审批留痕: 4
未检测静默失败: 0 -> 静默失败率 0.0% PASS
