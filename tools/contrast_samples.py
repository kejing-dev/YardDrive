"""Screenshot samples for tools/contrast.py (Yard Drive, emulator-5556 shots in apps/yard-drive/build/shots, 2160 px,
zh). The vibrant glass and fill roles have no fixed sRGB (PALETTE logs #FF000000), so they are measured here.
(pair, where, shot, box x0,y0,x1,y1, threshold)."""
SAMPLES = [
    ("labelPrimary / glass", "P1 title 倒车入库 (headline 24)", "S02", (965, 998, 1040, 1017), 4.5),
    ("labelPrimary / glass", "P1 tagline 从你的眼睛看点位 (15 sp)", "S02", (965, 1023, 1056, 1034), 4.5),
    ("labelPrimary / glass", "home eye-off line (15 sp)", "S02", (965, 1054, 1234, 1067), 4.5),
    ("labelPrimary / fillTertiary", "home card title 课程 (headline)", "S02", (1027, 1132, 1065, 1154), 4.5),
    ("labelPrimary / fillTertiary", "home card sub line (15 sp)", "S02", (1027, 1164, 1209, 1176), 4.5),
    ("labelPrimary / fillTertiary", "key 静音 (16 sp)", "S02", (1437, 1007, 1462, 1023), 4.5),
    ("labelPrimary / glass", "disclaimer (15 sp)", "S02", (1002, 1398, 1190, 1411), 4.5),
    ("labelPrimary / fillTertiary", "key 成绩单 · 23", "S02", (1487, 1407, 1552, 1423), 4.5),
    ("labelPrimary / glass", "P2 segment name Ⅰ 右倒库 (title 18)", "S07", (870, 1278, 926, 1291), 4.5),
    ("labelPrimary / glass", "P2 task 直退，看左镜 (body 18)", "S07", (869, 1301, 951, 1314), 4.5),
    ("labelPrimary / glass", "P2 countdown 还有 0.4 m (16 sp)", "S07", (1027, 1322, 1087, 1334), 4.5),
    ("labelPrimary / glass", "P2 col 1 看了哪里：按头部记录 (15 sp)", "S07", (689, 1337, 801, 1350), 4.5),
    ("labelPrimary / glass", "P2 speed 0.0 m/s (16 sp)", "S07", (723, 1307, 764, 1319), 4.5),
    ("labelPrimary / fillTertiary", "P2 key 暂停", "S07", (707, 1411, 737, 1426), 4.5),
    ("labelPrimary / glass", "settings row label 语言 (16 sp)", "S17_system", (964, 1061, 987, 1073), 4.5),
    ("labelPrimary / glass", "settings row sub 仅手势模式 (13.5 sp)", "S17_system", (962, 1162, 1015, 1173), 4.5),
    ("labelPrimary / fillTertiary", "segment item English (unselected)", "S17_system", (1188, 1061, 1231, 1076), 4.5),
    ("ink / interaction fill (measured)", "segment item 中文 (selected)", "S17_system", (1126, 1060, 1152, 1075), 4.5),
    ("labelPrimary / fillTertiary", "key 返回", "S17_system", (1518, 1009, 1545, 1023), 4.5),
    ("labelPrimary / glass", "settings about sub v0.1.0 · 免责与出处", "S17_system", (1262, 1216, 1354, 1226), 4.5),
]

# text that WCAG 1.4.3 does not require (disabled / decorative); still measured and listed
EXEMPT = []
