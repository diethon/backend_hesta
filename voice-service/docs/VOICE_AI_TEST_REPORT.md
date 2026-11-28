# BÁO CÁO KIỂM THỬ TOÀN DIỆN HỆ THỐNG HESTA VOICE AI

- **Thời gian thực hiện:** `2026-11-27 00:40:49`
- **Môi trường kiểm thử:** Local Windows Laptop — Spring Boot Backend (`http://localhost:8080`) + FastAPI Voice Service (`http://localhost:8001`)
- **Dữ liệu thực tế từ Database:** **20 thiết bị** (14 loại thiết bị/cảm biến) và **8 kịch bản tự động hóa**
- **Unit & Integration Tests (`pytest`):** **330 / 330 PASS (100%)**

---

## 1. Tổng quan kết quả kiểm thử (Executive Summary)

| Chỉ số kiểm thử | Kết quả | Đánh giá |
| :--- | :---: | :--- |
| Tổng số kịch bản kiểm thử Pipeline (NLU + Resolve + Context + Compound + Safety) | **140 / 140** (100.0%) | Đạt tuyệt đối 100% |
| Kiểm thử Live API + Phản hồi giọng nói TTS (`/api/voice/process`) | **10 / 10** (100.0%) | 100% có âm thanh tiếng Việt, không im lặng |
| Độ trễ xử lý NLU + Device/Scene Resolve trung bình | **2.02 ms** | Rất nhanh (< 5 ms) |
| Độ trễ NLU phân vị 95 (P95) | **5.51 ms** | Ổn định, phản hồi tức thì |
| Bộ Unit & Integration Tests tự động (`pytest`) | **330 / 330 (100%)** | Không có regression |

---

## 2. Thống kê kết quả theo từng nhóm chức năng / thiết bị

| Nhóm kiểm thử | Tổng số | Đạt (PASS) | Lỗi (FAIL) | Tỷ lệ đạt |
| :--- | :---: | :---: | :---: | :---: |
| 1. Đèn chiếu sáng (LIGHT) | 23 | 23 | 0 | 100.0% |
| 2. Đèn LED RGB (LED_RGB) | 11 | 11 | 0 | 100.0% |
| 3. Điều hòa (AIR_CONDITIONER) | 10 | 10 | 0 | 100.0% |
| 4. Quạt (FAN) | 4 | 4 | 0 | 100.0% |
| 5. Tivi (TV) | 4 | 4 | 0 | 100.0% |
| 6. Rèm cửa (CURTAIN) | 6 | 6 | 0 | 100.0% |
| 7. Cửa cuốn / Cổng (ROLLING_DOOR) | 8 | 8 | 0 | 100.0% |
| 8. Cửa chính (DOOR) | 6 | 6 | 0 | 100.0% |
| 9. Máy bơm nước (WATER_PUMP) | 4 | 4 | 0 | 100.0% |
| 10. Điều khiển hàng loạt (ALL) | 4 | 4 | 0 | 100.0% |
| 11. Kịch bản (SCENE) | 16 | 16 | 0 | 100.0% |
| 12. Lệnh kép & Xung đột (COMPOUND) | 4 | 4 | 0 | 100.0% |
| 13. Truy vấn hệ thống (SYSTEM) | 6 | 6 | 0 | 100.0% |
| 14. Cảm giác môi trường (COMFORT) | 4 | 4 | 0 | 100.0% |
| 15. An toàn & Chống đoán bừa (SAFETY) | 14 | 14 | 0 | 100.0% |
| 16. Ngữ cảnh hội thoại 45s (CONTEXT) | 16 | 16 | 0 | 100.0% |
| **17. Live API + Giọng đọc TTS (End-to-End)** | **10** | **10** | **0** | **100.0%** |

---

## 3. Danh mục lỗi tiềm ẩn đã phát hiện và khắc phục trong đợt rà soát diện rộng

| Mã lỗi | Mức độ | Câu lệnh tái hiện | Nguyên nhân gốc rễ | Giải pháp đã áp dụng |
| :--- | :---: | :--- | :--- | :--- |
| `BUG-01` | Nghiêm trọng | `bật đèn led màu đỏ phòng con` | `find_light_mode` dùng substring `q_norm in alias_norm` khiến `do` ('đỏ') khớp nhầm vào `doc sach` ('Đọc sách') | Kiểm tra `BUILTIN_COLOR_NAMES` exact-match trước mood presets và áp dụng ranh giới từ `\b` cho aliases |
| `BUG-02` | Nghiêm trọng | `tăng nhiệt độ điều hòa lên 2 độ` | `has_absolute_keyword` bắt chữ `'lên 2'` và ép thành `SET_TEMPERATURE(2°C)` thay vì tăng thêm 2 độ | Khóa dải nhiệt độ điều hòa `16–32°C` cho lệnh tuyệt đối; số `1–10` đi kèm `'tăng/giảm'` luôn giữ `amount` tương đối |
| `BUG-03` | Nghiêm trọng | `bật tất cả thiết bị phòng bếp`, `mở nhạc lên đi` | Kịch bản tên ngắn 3 ký tự (`bep`, `nhà`) trong DB khớp substring vào câu thiết bị và chữ `'nhạc'` (`nha` in `nhac`) | Chặn bước 3 nhận diện kịch bản khi có từ khóa thiết bị/phạm vi/domain ngoài hỗ trợ; yêu cầu độ dài `>= 4` và ranh giới từ `\b` |
| `BUG-04` | Nghiêm trọng | `đừng có bật đèn`, `không được tắt điều hòa` | Fuzzy matching tự cắt bỏ `'đừng có'` và khớp `'bật đèn'` với độ tin cậy 90%, gây thực thi ngược ý muốn | Thêm lớp chặn phủ định `_NEGATION_PROHIBITION_RE` ngay đầu `IntentMapper.map()` |
| `BUG-05` | Cao | `tắt đèn bếp rồi mở rèm phòng ngủ master` | `'bếp'` không được nhận là alias của `'Phòng bếp'`, khiến mệnh đề 1 kế thừa nhầm phòng từ mệnh đề 2 (`Phòng ngủ master`) | Bổ sung alias song ngữ & viết gọn trong `generate_room_aliases`: `bếp -> Phòng bếp`, `sân vườn -> Garden`, `gara/nhà xe -> Garage` |
| `BUG-06` | Cao | `mở màn phòng ngủ`, `bật máy lạnh phòng ngủ`, `bật đèn ngủ` | DB có phòng con (`Phòng ngủ master`, `Phòng ngủ của con`) nhưng không có phòng đúng tên `'Phòng ngủ'` | Hỗ trợ fallback phòng cha → phòng con trong `DeviceDirectory.resolve()`; nếu nhiều phòng ngủ cùng có đèn thì hỏi rõ tên phòng |
| `BUG-07` | Cao | `đặt 24 độ`, `tăng thêm 2 độ`, `đổi sang màu xanh dương`, `giảm độ sáng xuống 40%` (ngữ cảnh 45s) | Thiếu pattern tiếp nối tham số nhiệt độ/màu trong ngữ cảnh 45s; chỉnh độ sáng rút gọn bị nhảy từ `led_rgb` sang `light` | Bổ sung follow-up nhiệt độ/màu trong `intent.py` và bảo toàn loại `led_rgb` đang active trong 45s ở `pipeline.py` |
| `BUG-08` | Cao | `nhà hiện tại có mấy kịch bản`, `bật chế độ cho buổi sáng`, `Trời sáng rồi bật cho tôi kịch bản buổi sáng đi` | Regex hỏi danh sách thiếu từ `'mấy/bao nhiêu'`; lời dẫn đầu câu phá regex neo `^`; `'bật chế độ ...'` rớt xuống `DEVICE_TYPE_UNKNOWN` | Chuyển sang kiểm tra ngữ nghĩa truy vấn (`LIST_SCENES/ROOMS/DEVICES`), thêm `_strip_conversational_lead_in()`, chặn rớt `CONTEXT_ACTION_PATTERNS` |
| `BUG-09` | Trung bình | `bật kịch bản 1`, `chúc ngủ ngon` | Số `'1'` khớp nhầm tên `'Chào buổi sáng 1'` thay vì kịch bản vị trí #1; `'chúc ngủ ngon'` khớp bản `disabled` thay vì `'Đi ngủ'` đang `enabled` | Chỉ khớp tên chứa số khi tên đích danh là `'Kịch bản 1'`/`'1'`; nhường câu tự nhiên cho `_NATURAL_SCENE_TRIGGERS` ưu tiên `enabled=True` |
| `BUG-10` | Trung bình | `bật kịch bản chào buổi sáng` → `kịch bảnnn`, `khóa cửa chính` → `Đã khóa khóa cửa` | JS Regex `\b` chỉ hỗ trợ ASCII nên cắt sai chữ có dấu tiếng Việt; `dispatcher.py` lặp chữ `'khóa'` cho `door_lock` | Dùng Unicode lookaround `(?<![\p{L}\p{N}_]).../giu` ở Frontend và chuẩn hóa câu phản hồi tự nhiên cho `door_lock` (`Đã mở/đóng/khóa cửa`) |
| `BUG-11` | Cao | Thiết bị ngoại tuyến / Voice Service offline bị im lặng hoặc hiện raw `'Failed to fetch'` | Edge-TTS timeout với câu mới, Chrome chặn autoplay khi chưa unlock, thiếu audio dự phòng offline phía Frontend | Xây dựng cơ chế TTS 3 tầng (RAM cache → Edge-TTS → Google TTS HTTP zero-dep → 5 file MP3 tĩnh tại `public/audio/`) + unlock audio bằng silent WAV |
| `BUG-12` | Cao | `mở điều hòa hai mươi sáu độ` | `normalize_spoken_numbers` đổi `'hai mươi'` thành `'20'` trước nên `'hai mươi sáu'` biến thành `'20 sáu'` → đặt nhầm 20°C thay vì 26°C | Bổ sung bộ chuyển số ghép tiếng Việt 11–99 (`hai mươi sáu` → `26`, `hai lăm độ` → `25 độ`) trước khi thay thế hàng chục đơn lẻ |
| `BUG-13` | Trung bình | `tôi về nhà rồi` | `SceneDirectory.resolve_scene` dùng `s_norm in sq` khiến kịch bản tên ngắn `'nhà'` (disabled, 3 ký tự) cướp lệnh `'Về nhà'` | Yêu cầu `len(s_norm) >= 4`, ranh giới từ `\b` và tỷ lệ độ dài `>= 0.55` khi so khớp chuỗi con tên kịch bản |

---

## 4. Danh mục thiết bị & kịch bản thực tế dùng để kiểm thử

### 4.1. Thiết bị trong Database (20 thiết bị)

| STT | Tên thiết bị | Loại (`deviceType`) | Phòng |
| :---: | :--- | :--- | :--- |
| 1 | test | `LED_RGB` | Phòng khách |
| 2 | Cảm biến ánh sáng | `LUX_SENSOR` | Phòng khách |
| 3 | Cảm biến chuyển động cửa chính | `MOTION_SENSOR` | Phòng khách |
| 4 | Cảm biến khói | `SMOKE_SENSOR` | Phòng khách |
| 5 | Quạt phòng khách | `FAN` | Phòng khách |
| 6 | Cửa cuốn | `ROLLING_DOOR` | Garage |
| 7 | Cửa chính phòng khách | `DOOR` | Phòng khách |
| 8 | Rèm phòng ngủ | `CURTAIN` | Phòng ngủ master |
| 9 | Cảm biến lửa | `FLAME_SENSOR` | Phòng khách |
| 10 | Cảm biến nhiệt ẩm phòng khách | `TEMP_HUMID_SENSOR` | Phòng khách |
| 11 | Đèn chiếu TV | `LIGHT` | Phòng khách |
| 12 | TV TCL | `TV` | Phòng khách |
| 13 | Đèn phòng ngủ của con | `LIGHT` | Phòng ngủ của con |
| 14 | Đèn phòng khách | `LIGHT` | Phòng khách |
| 15 | Đèn phòng bếp | `LIGHT` | Phòng bếp |
| 16 | Máy bơm nước | `WATER_PUMP` | Garden |
| 17 | Đèn ngủ master | `LIGHT` | Phòng ngủ master |
| 18 | Đèn led RGB phòng con | `LED_RGB` | Phòng ngủ của con |
| 19 | Điều hòa DAIKIN | `AIR_CONDITIONER` | Phòng ngủ của con |
| 20 | Đèn garage | `LIGHT` | Garage |

### 4.2. Kịch bản trong Database (8 kịch bản)

| STT | Tên kịch bản | Trạng thái (`enabled`) | Số hành động |
| :---: | :--- | :---: | :---: |
| 1 | Buổi tối xem phim | Đang bật | 0 |
| 2 | nhà | Đang tắt | 0 |
| 3 | bep | Đang tắt | 0 |
| 4 | Chúc ngủ ngon | Đang tắt | 0 |
| 5 | Chào buổi sáng | Đang bật | 1 |
| 6 | Chào buổi sáng 1 | Đang bật | 2 |
| 7 | Đi ngủ | Đang bật | 4 |
| 8 | Chào buổi sáng 4 | Đang tắt | 1 |

---

## 5. Chi tiết toàn bộ kịch bản kiểm thử (140 Test Cases — Cơ bản đến Nâng cao)

### 1. Đèn chiếu sáng (LIGHT)

| Mã TC | Mức độ | Câu nói đầu vào | Kỳ vọng | Thiết bị / Kịch bản thực thi | Phản hồi hệ thống | Độ trễ (ms) | Kết quả |
| :--- | :--- | :--- | :--- | :--- | :--- | :---: | :---: |
| `TC-001` | Cơ bản | `bật đèn phòng khách` | Bật 2 đèn Phòng khách | Đèn chiếu TV [TURN_ON]; Đèn phòng khách [TURN_ON] | Đã bật 2 đèn Phòng khách | 13.54 | **PASS** |
| `TC-002` | Cơ bản | `tắt đèn phòng khách` | Tắt 2 đèn Phòng khách | Đèn chiếu TV [TURN_OFF]; Đèn phòng khách [TURN_OFF] | Đã tắt 2 đèn Phòng khách | 2.72 | **PASS** |
| `TC-003` | Cơ bản | `bật đèn phòng bếp` | Bật Đèn phòng bếp | Đèn phòng bếp [TURN_ON] | Đã bật đèn Phòng bếp | 1.69 | **PASS** |
| `TC-004` | Cơ bản | `tắt đèn bếp` | Tắt Đèn phòng bếp (alias 'bếp') | Đèn phòng bếp [TURN_OFF] | Đã tắt đèn Phòng bếp | 1.53 | **PASS** |
| `TC-005` | Cơ bản | `bật đèn ngủ master` | Bật Đèn ngủ master | Đèn ngủ master [TURN_ON] | Đã bật đèn ngủ Phòng ngủ master | 1.71 | **PASS** |
| `TC-006` | Trung bình | `tắt đèn phòng ngủ mắt tơ` | Sửa âm học 'mắt tơ' -> Đèn ngủ master | Đèn ngủ master [TURN_OFF] | Đã tắt đèn Phòng ngủ master | 1.96 | **PASS** |
| `TC-007` | Cơ bản | `bật đèn phòng ngủ của con` | Bật Đèn phòng ngủ của con | Đèn phòng ngủ của con [TURN_ON] | Đã bật đèn Phòng ngủ của con | 2.13 | **PASS** |
| `TC-008` | Trung bình | `tắt đèn phòng con` | Alias 'phòng con' -> Đèn phòng ngủ của con | Đèn phòng ngủ của con [TURN_OFF] | Đã tắt đèn Phòng ngủ của con | 1.68 | **PASS** |
| `TC-009` | Trung bình | `bật đèn chiếu TV` | Chỉ bật Đèn chiếu TV (không nhầm TV) | Đèn chiếu TV [TURN_ON] | Đã bật đèn chiếu TV | 1.8 | **PASS** |
| `TC-010` | Trung bình | `tắt đèn chiếu tivi` | Chỉ tắt Đèn chiếu TV | Đèn chiếu TV [TURN_OFF] | Đã tắt đèn chiếu TV | 1.83 | **PASS** |
| `TC-011` | Cơ bản | `bật đèn garage` | Bật Đèn garage | Đèn garage [TURN_ON] | Đã bật đèn Garage | 1.53 | **PASS** |
| `TC-012` | Trung bình | `tắt đèn gara` | Alias 'gara' -> Đèn garage | Đèn garage [TURN_OFF] | Đã tắt đèn Garage | 1.61 | **PASS** |
| `TC-013` | Trung bình | `bật đèn nhà xe` | Alias song ngữ 'nhà xe' -> Đèn garage | Đèn garage [TURN_ON] | Đã bật đèn Garage | 1.66 | **PASS** |
| `TC-014` | Trung bình | `tắc đèn phòng ngủ master` | Sửa lỗi phát âm 'tắc' -> Tắt Đèn ngủ master | Đèn ngủ master [TURN_OFF] | Đã tắt đèn Phòng ngủ master | 1.97 | **PASS** |
| `TC-015` | Nâng cao | `bật dùm tôi cái đèn phòng khách đi` | Lọc từ đệm lịch sự -> Bật đèn Phòng khách | Đèn chiếu TV [TURN_ON]; Đèn phòng khách [TURN_ON] | Đã bật 2 đèn Phòng khách | 3.5 | **PASS** |
| `TC-016` | Nâng cao | `Hesta ơi tắt đèn phòng bếp giúp mình` | Gọt lời dẫn 'Hesta ơi' -> Tắt Đèn phòng bếp | Đèn phòng bếp [TURN_OFF] | Đã tắt đèn Phòng bếp | 3.25 | **PASS** |
| `TC-017` | Nâng cao | `chỉnh độ sáng đèn phòng khách 70 phần trăm` | SET_BRIGHTNESS=70 Phòng khách | Đèn chiếu TV [SET_BRIGHTNESS] {'brightness': 70}; Đèn phòng khách [SET_BRIGHTNESS] {'brightness': 70} | Đã đặt độ sáng 2 đèn Phòng khách về mức 70 | 4.8 | **PASS** |
| `TC-018` | Nâng cao | `tăng độ sáng đèn phòng khách` | INCREASE_BRIGHTNESS Phòng khách | Đèn chiếu TV [INCREASE_BRIGHTNESS]; Đèn phòng khách [INCREASE_BRIGHTNESS] | Đã tăng sáng 2 đèn Phòng khách | 3.45 | **PASS** |
| `TC-019` | Nâng cao | `giảm độ sáng đèn phòng khách` | DECREASE_BRIGHTNESS Phòng khách | Đèn chiếu TV [DECREASE_BRIGHTNESS]; Đèn phòng khách [DECREASE_BRIGHTNESS] | Đã giảm sáng 2 đèn Phòng khách | 3.54 | **PASS** |
| `TC-020` | Nâng cao | `tắt tất cả đèn` | Tắt toàn bộ 8 đèn (LIGHT + LED_RGB) | test [TURN_OFF]; Đèn chiếu TV [TURN_OFF]; Đèn phòng khách [TURN_OFF]; Đèn phòng ngủ của con [TURN_OFF]; Đèn ngủ master [TURN_OFF]; Đèn phòng bếp [TURN_OFF]; Đèn garage [TURN_OFF]; Đèn led RGB phòng con [TURN_OFF] | Đã tắt tất cả 8 đèn trong nhà | 4.29 | **PASS** |
| `TC-021` | Nâng cao | `bật toàn bộ đèn trong nhà` | Bật toàn bộ 8 đèn (không chạm quạt/AC) | test [TURN_ON]; Đèn chiếu TV [TURN_ON]; Đèn phòng khách [TURN_ON]; Đèn phòng ngủ của con [TURN_ON]; Đèn ngủ master [TURN_ON]; Đèn phòng bếp [TURN_ON]; Đèn led RGB phòng con [TURN_ON]; Đèn garage [TURN_ON] | Đã bật tất cả 8 đèn trong nhà | 5.19 | **PASS** |
| `TC-022` | An toàn/Mơ hồ | `bật đèn` | Hỏi lại phòng vì có nhiều đèn ở nhiều phòng | *(Không gửi lệnh)* | Nhà có 6 đèn ở nhiều phòng khác nhau. Vui lòng nói rõ tên phòng. | 0.97 | **PASS** |
| `TC-023` | An toàn/Mơ hồ | `bật đèn ngủ` | Hỏi lại phòng ngủ (master vs của con) | *(Không gửi lệnh)* | Nhà có 2 đèn ngủ ở nhiều phòng khác nhau. Vui lòng nói rõ tên phòng. | 1.19 | **PASS** |

### 2. Đèn LED RGB (LED_RGB)

| Mã TC | Mức độ | Câu nói đầu vào | Kỳ vọng | Thiết bị / Kịch bản thực thi | Phản hồi hệ thống | Độ trễ (ms) | Kết quả |
| :--- | :--- | :--- | :--- | :--- | :--- | :---: | :---: |
| `TC-024` | Cơ bản | `bật đèn led phòng con` | Bật Đèn led RGB phòng con | Đèn led RGB phòng con [TURN_ON] | Đã bật đèn LED Phòng ngủ của con | 2.54 | **PASS** |
| `TC-025` | Cơ bản | `tắt đèn rgb phòng ngủ của con` | Tắt Đèn led RGB phòng con | Đèn led RGB phòng con [TURN_OFF] | Đã tắt đèn RGB Phòng ngủ của con | 2.83 | **PASS** |
| `TC-026` | Trung bình | `bật đèn lét phòng khách` | Sửa lỗi 'lét' -> LED RGB phòng khách (test) | test [TURN_ON] | Đã bật đèn LED Phòng khách | 2.07 | **PASS** |
| `TC-027` | Trung bình | `đổi màu đèn led phòng con sang màu đỏ` | SET_COLOR r=255,g=0,b=0 (đỏ) | Đèn led RGB phòng con [SET_COLOR] {'r': 255, 'g': 0, 'b': 0, 'hex': '', 'brightness': 100, 'color': 'đỏ'} | Đã đổi màu đèn LED Phòng ngủ của con sang màu đỏ | 3.49 | **PASS** |
| `TC-028` | Trung bình | `bật đèn led màu đỏ phòng con` | SET_COLOR màu đỏ (KHÔNG nhầm mood Đọc sách) | Đèn led RGB phòng con [SET_COLOR] {'r': 255, 'g': 0, 'b': 0, 'hex': '', 'brightness': 100, 'color': 'đỏ'} | Đã đổi màu đèn LED Phòng ngủ của con sang màu đỏ | 2.57 | **PASS** |
| `TC-029` | Trung bình | `chuyển đèn rgb phòng con màu xanh dương` | SET_COLOR xanh dương (b=255) | Đèn led RGB phòng con [SET_COLOR] {'r': 0, 'g': 100, 'b': 255, 'hex': '', 'brightness': 100, 'color': 'xanh dương'} | Đã đổi màu đèn RGB Phòng ngủ của con sang màu xanh dương | 3.25 | **PASS** |
| `TC-030` | Nâng cao | `đặt độ sáng đèn led phòng con 50 phần trăm` | SET_BRIGHTNESS=50 | Đèn led RGB phòng con [SET_BRIGHTNESS] {'brightness': 50} | Đã đặt độ sáng đèn LED Phòng ngủ của con về mức 50 | 3.82 | **PASS** |
| `TC-031` | Nâng cao | `tăng sáng đèn rgb phòng con` | INCREASE_BRIGHTNESS | Đèn led RGB phòng con [INCREASE_BRIGHTNESS] | Đã tăng sáng đèn RGB Phòng ngủ của con | 3.02 | **PASS** |
| `TC-032` | Nâng cao | `giảm sáng đèn led phòng con` | DECREASE_BRIGHTNESS | Đèn led RGB phòng con [DECREASE_BRIGHTNESS] | Đã giảm sáng đèn LED Phòng ngủ của con | 2.8 | **PASS** |
| `TC-033` | Nâng cao | `bật đèn led chế độ thư giãn phòng con` | Mood preset Thư giãn (#ff9d5c, 40%) | Đèn led RGB phòng con [SET_COLOR] {'r': 255, 'g': 157, 'b': 92, 'hex': '#ff9d5c', 'brightness': 40, 'mode': 'Thư giãn'} | Đã bật đèn LED Phòng ngủ của con chế độ Thư giãn | 3.26 | **PASS** |
| `TC-034` | Nâng cao | `đổi đèn rgb phòng con sang chế độ đọc sách` | Mood preset Đọc sách (#fff1d6, 80%) | Đèn led RGB phòng con [SET_COLOR] {'r': 255, 'g': 241, 'b': 214, 'hex': '#fff1d6', 'brightness': 80, 'mode': 'Đọc sách'} | Đã chuyển đèn RGB Phòng ngủ của con chế độ Đọc sách | 3.95 | **PASS** |

### 3. Điều hòa (AIR_CONDITIONER)

| Mã TC | Mức độ | Câu nói đầu vào | Kỳ vọng | Thiết bị / Kịch bản thực thi | Phản hồi hệ thống | Độ trễ (ms) | Kết quả |
| :--- | :--- | :--- | :--- | :--- | :--- | :---: | :---: |
| `TC-035` | Cơ bản | `bật điều hòa` | Bật Điều hòa DAIKIN | Điều hòa DAIKIN [TURN_ON] | Đã bật điều hòa | 1.38 | **PASS** |
| `TC-036` | Cơ bản | `tắt máy lạnh` | Tắt Điều hòa DAIKIN | Điều hòa DAIKIN [TURN_OFF] | Đã tắt điều hòa | 1.37 | **PASS** |
| `TC-037` | Trung bình | `bật máy lạnh phòng ngủ` | Parent-room fallback -> Điều hòa DAIKIN | Điều hòa DAIKIN [TURN_ON] | Đã bật điều hòa phòng ngủ | 1.96 | **PASS** |
| `TC-038` | Trung bình | `đặt nhiệt độ điều hòa 25 độ` | SET_TEMPERATURE=25 | Điều hòa DAIKIN [SET_TEMPERATURE] {'temperature': 25} | Đã đặt nhiệt độ điều hòa về 25 độ | 2.66 | **PASS** |
| `TC-039` | Trung bình | `chỉnh máy lạnh xuống 24 độ` | SET_TEMPERATURE=24 | Điều hòa DAIKIN [SET_TEMPERATURE] {'temperature': 24} | Đã đặt nhiệt độ điều hòa về 24 độ | 2.36 | **PASS** |
| `TC-040` | Nâng cao | `mở điều hòa hai mươi sáu độ` | Số đọc bằng chữ -> SET_TEMPERATURE=26 | Điều hòa DAIKIN [SET_TEMPERATURE] {'temperature': 26} | Đã đặt nhiệt độ điều hòa về 26 độ | 2.24 | **PASS** |
| `TC-041` | Nâng cao | `tăng nhiệt độ điều hòa lên 2 độ` | INCREASE_TEMPERATURE amount=2 (KHÔNG đặt 2°C) | Điều hòa DAIKIN [INCREASE_TEMPERATURE] {'amount': 2} | Đã tăng nhiệt độ điều hòa thêm 2 độ | 2.8 | **PASS** |
| `TC-042` | Trung bình | `giảm nhiệt độ điều hòa` | DECREASE_TEMPERATURE | Điều hòa DAIKIN [DECREASE_TEMPERATURE] | Đã giảm nhiệt độ điều hòa | 1.94 | **PASS** |
| `TC-043` | Nâng cao | `nóng quá` | Cảm giác nóng -> DECREASE_TEMPERATURE (-2°C) | Điều hòa DAIKIN [DECREASE_TEMPERATURE] {'amount': 2} | Đã giảm nhiệt độ điều hòa thêm 2 độ | 1.17 | **PASS** |
| `TC-044` | Nâng cao | `lạnh quá` | Cảm giác lạnh -> INCREASE_TEMPERATURE (+2°C) | Điều hòa DAIKIN [INCREASE_TEMPERATURE] {'amount': 2} | Đã tăng nhiệt độ điều hòa thêm 2 độ | 0.66 | **PASS** |

### 4. Quạt (FAN)

| Mã TC | Mức độ | Câu nói đầu vào | Kỳ vọng | Thiết bị / Kịch bản thực thi | Phản hồi hệ thống | Độ trễ (ms) | Kết quả |
| :--- | :--- | :--- | :--- | :--- | :--- | :---: | :---: |
| `TC-045` | Cơ bản | `bật quạt` | Bật Quạt phòng khách | Quạt phòng khách [TURN_ON] | Đã bật quạt | 1.13 | **PASS** |
| `TC-046` | Cơ bản | `tắt quạt phòng khách` | Tắt Quạt phòng khách | Quạt phòng khách [TURN_OFF] | Đã tắt quạt Phòng khách | 1.72 | **PASS** |
| `TC-047` | Trung bình | `mở quạt lên giùm tôi` | Lọc từ đệm -> Bật Quạt phòng khách | Quạt phòng khách [TURN_ON] | Đã bật quạt | 1.93 | **PASS** |
| `TC-048` | Nâng cao | `quạt mạnh quá` | Cảm giác gió mạnh -> Tắt Quạt | Quạt phòng khách [TURN_OFF] | Đã tắt quạt | 0.75 | **PASS** |

### 5. Tivi (TV)

| Mã TC | Mức độ | Câu nói đầu vào | Kỳ vọng | Thiết bị / Kịch bản thực thi | Phản hồi hệ thống | Độ trễ (ms) | Kết quả |
| :--- | :--- | :--- | :--- | :--- | :--- | :---: | :---: |
| `TC-049` | Cơ bản | `bật tivi` | Bật TV TCL | TV TCL [TURN_ON] | Đã bật tivi | 1.13 | **PASS** |
| `TC-050` | Cơ bản | `tắt TV phòng khách` | Tắt TV TCL | TV TCL [TURN_OFF] | Đã tắt tivi Phòng khách | 1.59 | **PASS** |
| `TC-051` | Trung bình | `mở ti vi lên đi` | Biến thể 'ti vi' -> Bật TV TCL | TV TCL [TURN_ON] | Đã bật tivi | 2.16 | **PASS** |
| `TC-052` | Trung bình | `tắt dùm cái tivi` | Lọc từ đệm -> Tắt TV TCL | TV TCL [TURN_OFF] | Đã tắt tivi | 1.37 | **PASS** |

### 6. Rèm cửa (CURTAIN)

| Mã TC | Mức độ | Câu nói đầu vào | Kỳ vọng | Thiết bị / Kịch bản thực thi | Phản hồi hệ thống | Độ trễ (ms) | Kết quả |
| :--- | :--- | :--- | :--- | :--- | :--- | :---: | :---: |
| `TC-053` | Cơ bản | `mở rèm` | OPEN Rèm phòng ngủ | Rèm phòng ngủ [OPEN] | Đã mở rèm | 1.12 | **PASS** |
| `TC-054` | Cơ bản | `đóng rèm phòng ngủ master` | CLOSE Rèm phòng ngủ | Rèm phòng ngủ [CLOSE] | Đã đóng rèm Phòng ngủ master | 1.94 | **PASS** |
| `TC-055` | Cơ bản | `dừng rèm lại` | STOP Rèm phòng ngủ | Rèm phòng ngủ [STOP] | Đã dừng rèm | 1.46 | **PASS** |
| `TC-056` | Trung bình | `kéo rèm ra` | OPEN Rèm phòng ngủ | Rèm phòng ngủ [OPEN] | Đã mở rèm | 1.46 | **PASS** |
| `TC-057` | Trung bình | `kéo rèm lại` | CLOSE Rèm phòng ngủ | Rèm phòng ngủ [CLOSE] | Đã đóng rèm | 1.66 | **PASS** |
| `TC-058` | Nâng cao | `mở màn phòng ngủ` | Alias 'màn' + parent room 'phòng ngủ' -> OPEN | Rèm phòng ngủ [OPEN] | Đã mở màn phòng ngủ | 2.84 | **PASS** |

### 7. Cửa cuốn / Cổng (ROLLING_DOOR)

| Mã TC | Mức độ | Câu nói đầu vào | Kỳ vọng | Thiết bị / Kịch bản thực thi | Phản hồi hệ thống | Độ trễ (ms) | Kết quả |
| :--- | :--- | :--- | :--- | :--- | :--- | :---: | :---: |
| `TC-059` | Cơ bản | `mở cửa cuốn` | OPEN Cửa cuốn | Cửa cuốn [OPEN] | Đã mở cửa cuốn | 1.11 | **PASS** |
| `TC-060` | Cơ bản | `đóng cửa cuốn` | CLOSE Cửa cuốn | Cửa cuốn [CLOSE] | Đã đóng cửa cuốn | 1.16 | **PASS** |
| `TC-061` | Cơ bản | `dừng cửa cuốn` | STOP Cửa cuốn | Cửa cuốn [STOP] | Đã dừng cửa cuốn | 1.17 | **PASS** |
| `TC-062` | Trung bình | `mở cửa cuống` | Sửa lỗi STT 'cửa cuống' -> OPEN Cửa cuốn | Cửa cuốn [OPEN] | Đã mở cửa cuốn | 1.06 | **PASS** |
| `TC-063` | Trung bình | `mở cửa gara` | Cửa gara -> OPEN Cửa cuốn (không nhầm door_lock) | Cửa cuốn [OPEN] | Đã mở cửa cuốn Garage | 1.24 | **PASS** |
| `TC-064` | Trung bình | `đóng cửa garage` | Cửa garage -> CLOSE Cửa cuốn | Cửa cuốn [CLOSE] | Đã đóng cửa cuốn Garage | 1.31 | **PASS** |
| `TC-065` | Cơ bản | `mở cổng` | OPEN Cửa cuốn | Cửa cuốn [OPEN] | Đã mở cổng | 1.1 | **PASS** |
| `TC-066` | Cơ bản | `đóng cổng lại` | CLOSE Cửa cuốn | Cửa cuốn [CLOSE] | Đã đóng cổng | 1.39 | **PASS** |

### 8. Cửa chính (DOOR)

| Mã TC | Mức độ | Câu nói đầu vào | Kỳ vọng | Thiết bị / Kịch bản thực thi | Phản hồi hệ thống | Độ trễ (ms) | Kết quả |
| :--- | :--- | :--- | :--- | :--- | :--- | :---: | :---: |
| `TC-067` | Cơ bản | `mở cửa chính` | TURN_ON Cửa chính phòng khách, msg 'Đã mở cửa' | Cửa chính phòng khách [TURN_ON] | Đã mở cửa | 1.28 | **PASS** |
| `TC-068` | Cơ bản | `đóng cửa chính` | TURN_OFF Cửa chính phòng khách, msg 'Đã đóng cửa' | Cửa chính phòng khách [TURN_OFF] | Đã đóng cửa | 1.38 | **PASS** |
| `TC-069` | Cơ bản | `mở khóa cửa` | TURN_ON Cửa chính phòng khách | Cửa chính phòng khách [TURN_ON] | Đã mở khóa cửa | 1.38 | **PASS** |
| `TC-070` | Cơ bản | `khóa cửa chính` | TURN_OFF Cửa chính phòng khách (không lặp 'khóa khóa') | Cửa chính phòng khách [TURN_OFF] | Đã khóa cửa | 1.3 | **PASS** |
| `TC-071` | Trung bình | `mở cửa phòng khách` | TURN_ON Cửa chính phòng khách | Cửa chính phòng khách [TURN_ON] | Đã mở cửa Phòng khách | 2.23 | **PASS** |
| `TC-072` | Trung bình | `đóng cửa phòng khách` | TURN_OFF Cửa chính phòng khách | Cửa chính phòng khách [TURN_OFF] | Đã đóng cửa Phòng khách | 2.29 | **PASS** |

### 9. Máy bơm nước (WATER_PUMP)

| Mã TC | Mức độ | Câu nói đầu vào | Kỳ vọng | Thiết bị / Kịch bản thực thi | Phản hồi hệ thống | Độ trễ (ms) | Kết quả |
| :--- | :--- | :--- | :--- | :--- | :--- | :---: | :---: |
| `TC-073` | Cơ bản | `bật máy bơm` | TURN_ON Máy bơm nước | Máy bơm nước [TURN_ON] | Đã bật máy bơm nước | 1.29 | **PASS** |
| `TC-074` | Cơ bản | `tắt máy bơm nước` | TURN_OFF Máy bơm nước | Máy bơm nước [TURN_OFF] | Đã tắt máy bơm nước | 1.52 | **PASS** |
| `TC-075` | Trung bình | `bật bơm nước sân vườn` | Alias song ngữ 'sân vườn' -> Garden / Máy bơm nước | Máy bơm nước [TURN_ON] | Đã bật máy bơm nước Garden | 1.89 | **PASS** |
| `TC-076` | Trung bình | `tắt máy bơm garden` | Phòng Garden -> TURN_OFF Máy bơm nước | Máy bơm nước [TURN_OFF] | Đã tắt máy bơm nước Garden | 1.66 | **PASS** |

### 10. Điều khiển hàng loạt (ALL)

| Mã TC | Mức độ | Câu nói đầu vào | Kỳ vọng | Thiết bị / Kịch bản thực thi | Phản hồi hệ thống | Độ trễ (ms) | Kết quả |
| :--- | :--- | :--- | :--- | :--- | :--- | :---: | :---: |
| `TC-077` | Cơ bản | `tắt tất cả thiết bị phòng khách` | Tắt 5 thiết bị điều khiển được ở Phòng khách (bỏ qua 5 cảm biến) | test [TURN_OFF]; Quạt phòng khách [TURN_OFF]; Đèn chiếu TV [TURN_OFF]; Đèn phòng khách [TURN_OFF]; TV TCL [TURN_OFF] | Đã tắt tất cả 5 thiết bị Phòng khách | 5.71 | **PASS** |
| `TC-078` | Trung bình | `bật tất cả thiết bị phòng bếp` | Bật Đèn phòng bếp (KHÔNG bị scene 'bep' cướp lệnh) | Đèn phòng bếp [TURN_ON] | Đã bật thiết bị Phòng bếp | 3.75 | **PASS** |
| `TC-079` | Nâng cao | `tắt hết thiết bị trong nhà` | Tắt 11 thiết bị điều khiển được cả nhà (bỏ qua cảm biến/cửa) | test [TURN_OFF]; Quạt phòng khách [TURN_OFF]; Đèn chiếu TV [TURN_OFF]; Đèn phòng khách [TURN_OFF]; Đèn phòng ngủ của con [TURN_OFF]; TV TCL [TURN_OFF]; Đèn phòng bếp [TURN_OFF]; Điều hòa DAIKIN [TURN_OFF]; Đèn ngủ master [TURN_OFF]; Đèn led RGB phòng con [TURN_OFF]; Đèn garage [TURN_OFF] | Đã tắt tất cả 11 thiết bị trong nhà | 7.51 | **PASS** |
| `TC-080` | Nâng cao | `tắt toàn bộ thiết bị` | Tắt toàn bộ thiết bị điều khiển được cả nhà | test [TURN_OFF]; Quạt phòng khách [TURN_OFF]; Đèn chiếu TV [TURN_OFF]; Đèn phòng khách [TURN_OFF]; Đèn phòng ngủ của con [TURN_OFF]; TV TCL [TURN_OFF]; Đèn phòng bếp [TURN_OFF]; Điều hòa DAIKIN [TURN_OFF]; Đèn led RGB phòng con [TURN_OFF]; Đèn ngủ master [TURN_OFF]; Đèn garage [TURN_OFF] | Đã tắt tất cả 11 thiết bị trong nhà | 6.94 | **PASS** |

### 11. Kịch bản (SCENE)

| Mã TC | Mức độ | Câu nói đầu vào | Kỳ vọng | Thiết bị / Kịch bản thực thi | Phản hồi hệ thống | Độ trễ (ms) | Kết quả |
| :--- | :--- | :--- | :--- | :--- | :--- | :---: | :---: |
| `TC-081` | Cơ bản | `bật kịch bản 1` | Kích hoạt kịch bản số 1 (Buổi tối xem phim) | Scene:Buổi tối xem phim(ACTIVATE) [EXEC] | Đã bật kịch bản Buổi tối xem phim. | 0.89 | **PASS** |
| `TC-082` | Cơ bản | `bật kịch bản chào buổi sáng` | Kích hoạt Chào buổi sáng | Scene:Chào buổi sáng(ACTIVATE) [EXEC] | Đã bật kịch bản Chào buổi sáng. | 0.54 | **PASS** |
| `TC-083` | Cơ bản | `bật kịch bản chào buổi sáng 1` | Kích hoạt Chào buổi sáng 1 | Scene:Chào buổi sáng 1(ACTIVATE) [EXEC] | Đã bật kịch bản Chào buổi sáng 1. | 0.34 | **PASS** |
| `TC-084` | Cơ bản | `tắt kịch bản đi ngủ` | Tắt (DEACTIVATE) kịch bản Đi ngủ | Scene:Đi ngủ(DEACTIVATE) [EXEC] | Đã tắt kịch bản Đi ngủ. | 0.33 | **PASS** |
| `TC-085` | Cơ bản | `dừng kịch bản chào buổi sáng` | Tắt (DEACTIVATE) Chào buổi sáng | Scene:Chào buổi sáng(DEACTIVATE) [EXEC] | Đã tắt kịch bản Chào buổi sáng. | 0.32 | **PASS** |
| `TC-086` | Lỗi STT | `bật kết bạn chào buổi sáng` | Sửa lỗi STT 'kết bạn' -> kịch bản Chào buổi sáng | Scene:Chào buổi sáng(ACTIVATE) [EXEC] | Đã bật kịch bản Chào buổi sáng. | 0.3 | **PASS** |
| `TC-087` | Lỗi STT | `bật kịch bảng 1` | Sửa lỗi STT 'kịch bảng' -> kịch bản số 1 | Scene:Buổi tối xem phim(ACTIVATE) [EXEC] | Đã bật kịch bản Buổi tối xem phim. | 0.32 | **PASS** |
| `TC-088` | Lỗi STT | `bật chế độ cho buổi sáng` | Lỗi STT 'cho' thay 'chào' -> Chào buổi sáng | Scene:Chào buổi sáng(ACTIVATE) [EXEC] | Đã bật kịch bản Chào buổi sáng. | 0.46 | **PASS** |
| `TC-089` | Nâng cao | `Trời sáng rồi bật cho tôi kịch bản buổi sáng đi` | Gọt lời dẫn + từ đệm -> Chào buổi sáng | Scene:Chào buổi sáng(ACTIVATE) [EXEC] | Đã bật kịch bản Chào buổi sáng. | 4.31 | **PASS** |
| `TC-090` | Nâng cao | `bật dùm cái kịch bản đi ngủ` | Từ đệm lịch sự -> Đi ngủ | Scene:Đi ngủ(ACTIVATE) [EXEC] | Đã bật kịch bản Đi ngủ. | 0.36 | **PASS** |
| `TC-091` | Nâng cao | `bật cho tôi cái đi ngủ đi` | Không nói chữ 'kịch bản' vẫn khớp Đi ngủ | Scene:Đi ngủ(ACTIVATE) [EXEC] | Đã bật kịch bản Đi ngủ. | 0.42 | **PASS** |
| `TC-092` | Đời thường | `tôi muốn đi ngủ` | Ngôn ngữ tự nhiên -> Đi ngủ (ưu tiên enabled=True) | Scene:Đi ngủ(ACTIVATE) [EXEC] | Đã bật kịch bản Đi ngủ. | 0.36 | **PASS** |
| `TC-093` | Đời thường | `chúc ngủ ngon` | Ưu tiên Đi ngủ (enabled) thay vì Chúc ngủ ngon (disabled) | Scene:Đi ngủ(ACTIVATE) [EXEC] | Đã bật kịch bản Đi ngủ. | 0.33 | **PASS** |
| `TC-094` | Đời thường | `chào buổi sáng` | Ngôn ngữ tự nhiên -> Chào buổi sáng | Scene:Chào buổi sáng(ACTIVATE) [EXEC] | Đã bật kịch bản Chào buổi sáng. | 0.32 | **PASS** |
| `TC-095` | Đời thường | `tôi muốn xem phim` | Ngôn ngữ tự nhiên -> Buổi tối xem phim | Scene:Buổi tối xem phim(ACTIVATE) [EXEC] | Đã bật kịch bản Buổi tối xem phim. | 0.49 | **PASS** |
| `TC-096` | An toàn/Mơ hồ | `tôi về nhà rồi` | Báo rõ nhà chưa có kịch bản Về nhà (không đoán bừa) | *(Không gửi lệnh)* | Nhà chưa có kịch bản Về nhà. Bạn có thể tạo kịch bản này trong mục Tự động hóa. | 0.57 | **PASS** |

### 12. Lệnh kép & Xung đột (COMPOUND)

| Mã TC | Mức độ | Câu nói đầu vào | Kỳ vọng | Thiết bị / Kịch bản thực thi | Phản hồi hệ thống | Độ trễ (ms) | Kết quả |
| :--- | :--- | :--- | :--- | :--- | :--- | :---: | :---: |
| `TC-097` | Nâng cao | `bật đèn phòng khách và bật quạt` | Thực thi song song 2 đèn Phòng khách + Quạt | Quạt phòng khách [TURN_ON]; Đèn chiếu TV [TURN_ON]; Đèn phòng khách [TURN_ON] | Đã bật 2 đèn Phòng khách và bật quạt. | 8.54 | **PASS** |
| `TC-098` | Nâng cao | `tắt đèn bếp rồi mở rèm phòng ngủ master` | Đèn phòng bếp (KHÔNG nhảy sang phòng ngủ) + Rèm | Đèn phòng bếp [TURN_OFF]; Rèm phòng ngủ [OPEN] | Đã tắt đèn Phòng bếp và mở rèm Phòng ngủ master. | 8.2 | **PASS** |
| `TC-099` | Nâng cao | `mở điều hòa 25 độ và tắt đèn chiếu TV` | AC 25°C + Tắt Đèn chiếu TV | Điều hòa DAIKIN [SET_TEMPERATURE] {'temperature': 25}; Đèn chiếu TV [TURN_OFF] | Đã đặt nhiệt độ điều hòa về 25 độ và tắt đèn chiếu TV. | 9.05 | **PASS** |
| `TC-100` | Xung đột | `bật đèn phòng khách và bật kịch bản đi ngủ` | Specific Overrides General: chạy Kịch bản Đi ngủ TRƯỚC, Đèn phòng khách SAU | Scene:Đi ngủ(ACTIVATE) [EXEC]; Đèn chiếu TV [TURN_ON]; Đèn phòng khách [TURN_ON] | Đã bật 2 đèn Phòng khách và bật kịch bản Đi ngủ. | 5.51 | **PASS** |

### 13. Truy vấn hệ thống (SYSTEM)

| Mã TC | Mức độ | Câu nói đầu vào | Kỳ vọng | Thiết bị / Kịch bản thực thi | Phản hồi hệ thống | Độ trễ (ms) | Kết quả |
| :--- | :--- | :--- | :--- | :--- | :--- | :---: | :---: |
| `TC-101` | Cơ bản | `nhà hiện tại có mấy kịch bản` | Đếm & liệt kê kịch bản đang bật/tắt | *(Không gửi lệnh)* | Nhà hiện có 4 kịch bản đang bật: Buổi tối xem phim, Chào buổi sáng, Chào buổi sáng 1, Đi ngủ và 4 kịch bản đang tắt. Bạn muốn bật kịch bản nào? | 0.19 | **PASS** |
| `TC-102` | Cơ bản | `ở nhà mình có những kịch bản nào nhỉ` | Liệt kê kịch bản nhà | *(Không gửi lệnh)* | Nhà hiện có 4 kịch bản đang bật: Buổi tối xem phim, Chào buổi sáng, Chào buổi sáng 1, Đi ngủ và 4 kịch bản đang tắt. Bạn muốn bật kịch bản nào? | 0.15 | **PASS** |
| `TC-103` | Cơ bản | `nhà có bao nhiêu phòng` | Liệt kê 6 phòng từ DB | *(Không gửi lệnh)* | Nhà hiện có 6 phòng: Garage, Garden, Phòng bếp, Phòng khách, Phòng ngủ của con, Phòng ngủ master. | 0.12 | **PASS** |
| `TC-104` | Trung bình | `phòng khách có mấy thiết bị` | Liệt kê 5 thiết bị điều khiển được ở Phòng khách | *(Không gửi lệnh)* | Hiện có 5 thiết bị ở Phòng khách: test, Quạt phòng khách, Đèn chiếu TV, Đèn phòng khách, TV TCL. | 0.97 | **PASS** |
| `TC-105` | Trung bình | `nhà có những thiết bị nào` | Liệt kê thiết bị trong nhà | *(Không gửi lệnh)* | Hiện có 11 thiết bị trong nhà: test, Quạt phòng khách, Đèn chiếu TV, Đèn phòng khách, TV TCL, Đèn phòng ngủ của con và 5 thiết bị khác. | 0.91 | **PASS** |
| `TC-106` | Cơ bản | `hủy lệnh` | Xóa ngữ cảnh hội thoại | *(Không gửi lệnh)* | Đã hủy bỏ yêu cầu. | 0.22 | **PASS** |

### 14. Cảm giác môi trường (COMFORT)

| Mã TC | Mức độ | Câu nói đầu vào | Kỳ vọng | Thiết bị / Kịch bản thực thi | Phản hồi hệ thống | Độ trễ (ms) | Kết quả |
| :--- | :--- | :--- | :--- | :--- | :--- | :---: | :---: |
| `TC-107` | Nâng cao | `phòng khách tối quá` | Bật đèn Phòng khách | Đèn chiếu TV [TURN_ON]; Đèn phòng khách [TURN_ON] | Đã bật 2 đèn Phòng khách | 1.68 | **PASS** |
| `TC-108` | Nâng cao | `tối quá` | Nhận diện TURN_ON light, hỏi rõ phòng do nhiều phòng có đèn | *(Không gửi lệnh)* | Nhà có 6 đèn ở nhiều phòng khác nhau. Vui lòng nói rõ tên phòng. | 0.58 | **PASS** |
| `TC-109` | Nâng cao | `đèn phòng khách sáng quá` | DECREASE_BRIGHTNESS Phòng khách (không bật đèn) | Đèn chiếu TV [DECREASE_BRIGHTNESS] {'amount': 20}; Đèn phòng khách [DECREASE_BRIGHTNESS] {'amount': 20} | Đã giảm sáng 2 đèn Phòng khách thêm 20 mức | 2.1 | **PASS** |
| `TC-110` | Nâng cao | `phòng ngủ lạnh quá` | INCREASE_TEMPERATURE (+2°C) Điều hòa DAIKIN | Điều hòa DAIKIN [INCREASE_TEMPERATURE] {'amount': 2} | Đã tăng nhiệt độ điều hòa phòng ngủ thêm 2 độ | 1.07 | **PASS** |

### 15. An toàn & Chống đoán bừa (SAFETY)

| Mã TC | Mức độ | Câu nói đầu vào | Kỳ vọng | Thiết bị / Kịch bản thực thi | Phản hồi hệ thống | Độ trễ (ms) | Kết quả |
| :--- | :--- | :--- | :--- | :--- | :--- | :---: | :---: |
| `TC-111` | Phủ định | `đừng bật đèn` | Chặn lệnh phủ định (INTENT_NOT_FOUND, 0 dispatch) | *(Không gửi lệnh)* | Tôi không hiểu yêu cầu này. Bạn vui lòng nói lại nhé. | 0.07 | **PASS** |
| `TC-112` | Phủ định | `đừng có mở cổng` | Chặn lệnh phủ định mở cổng | *(Không gửi lệnh)* | Tôi không hiểu yêu cầu này. Bạn vui lòng nói lại nhé. | 0.06 | **PASS** |
| `TC-113` | Phủ định | `không được tắt điều hòa` | Chặn lệnh cấm tắt điều hòa | *(Không gửi lệnh)* | Tôi không hiểu yêu cầu này. Bạn vui lòng nói lại nhé. | 0.06 | **PASS** |
| `TC-114` | Phủ định | `khoan hãy mở cửa` | Chặn lệnh trì hoãn mở cửa | *(Không gửi lệnh)* | Tôi không hiểu yêu cầu này. Bạn vui lòng nói lại nhé. | 0.05 | **PASS** |
| `TC-115` | Ngoài phạm vi | `bật tủ lạnh` | Từ chối thiết bị không hỗ trợ | *(Không gửi lệnh)* | Tôi không hiểu yêu cầu này. Bạn vui lòng nói lại nhé. | 0.5 | **PASS** |
| `TC-116` | Ngoài phạm vi | `tắt máy giặt` | Từ chối thiết bị không hỗ trợ | *(Không gửi lệnh)* | Tôi không hiểu yêu cầu này. Bạn vui lòng nói lại nhé. | 0.53 | **PASS** |
| `TC-117` | Ngoài phạm vi | `mở nhạc lên đi` | Từ chối mở nhạc (KHÔNG nhầm sang scene 'nhà') | *(Không gửi lệnh)* | Tôi không hiểu yêu cầu này. Bạn vui lòng nói lại nhé. | 0.61 | **PASS** |
| `TC-118` | Ngoài phạm vi | `đóng cuốn sách` | Từ chối (KHÔNG nhầm sang 'đóng cổng') | *(Không gửi lệnh)* | Tôi không hiểu yêu cầu này. Bạn vui lòng nói lại nhé. | 2.29 | **PASS** |
| `TC-119` | Chế độ lạ | `bật chế độ linh tinh không tồn tại` | Trả INTENT_NOT_FOUND (KHÔNG báo lỗi loại thiết bị) | *(Không gửi lệnh)* | Tôi không hiểu yêu cầu này. Bạn vui lòng nói lại nhé. | 2.6 | **PASS** |
| `TC-120` | Xã giao | `kết bạn với tôi đi` | Giữ nguyên xã giao, KHÔNG chuyển thành kịch bản | *(Không gửi lệnh)* | Tôi không hiểu yêu cầu này. Bạn vui lòng nói lại nhé. | 2.51 | **PASS** |
| `TC-121` | Xã giao | `tôi đi ăn cơm` | Chữ 'tôi' KHÔNG nhầm thành 'tối quá' | *(Không gửi lệnh)* | Tôi không hiểu yêu cầu này. Bạn vui lòng nói lại nhé. | 1.23 | **PASS** |
| `TC-122` | Xã giao | `hôm nay trời đẹp quá` | Từ chối câu cảm thán xã giao | *(Không gửi lệnh)* | Tôi không hiểu yêu cầu này. Bạn vui lòng nói lại nhé. | 1.53 | **PASS** |
| `TC-123` | Nhiễu | `alo alo 1 2 3` | Từ chối nhiễu mic | *(Không gửi lệnh)* | Tôi không hiểu yêu cầu này. Bạn vui lòng nói lại nhé. | 1.57 | **PASS** |
| `TC-124` | Ngoài phạm vi | `mấy giờ rồi` | Từ chối câu hỏi ngoài Smart Home | *(Không gửi lệnh)* | Tôi không hiểu yêu cầu này. Bạn vui lòng nói lại nhé. | 0.54 | **PASS** |

### 16. Ngữ cảnh hội thoại 45s (CONTEXT)

| Mã TC | Mức độ | Câu nói đầu vào | Kỳ vọng | Thiết bị / Kịch bản thực thi | Phản hồi hệ thống | Độ trễ (ms) | Kết quả |
| :--- | :--- | :--- | :--- | :--- | :--- | :---: | :---: |
| `TC-125` | Luồng Đèn Phòng khách #1 | `bật đèn phòng khách` | Bật 2 đèn Phòng khách | Đèn chiếu TV [TURN_ON]; Đèn phòng khách [TURN_ON] | Đã bật 2 đèn Phòng khách | 2.67 | **PASS** |
| `TC-126` | Luồng Đèn Phòng khách #2 | `tắt đi` | Kế thừa ngữ cảnh -> Tắt 2 đèn Phòng khách | Đèn chiếu TV [TURN_OFF]; Đèn phòng khách [TURN_OFF] | Đã tắt 2 đèn Phòng khách | 1.9 | **PASS** |
| `TC-127` | Luồng Điều hòa đa tham số #1 | `bật điều hòa` | Bật Điều hòa DAIKIN | Điều hòa DAIKIN [TURN_ON] | Đã bật điều hòa | 1.72 | **PASS** |
| `TC-128` | Luồng Điều hòa đa tham số #2 | `đặt 24 độ` | Kế thừa AC -> SET_TEMPERATURE=24 | Điều hòa DAIKIN [SET_TEMPERATURE] {'temperature': 24} | Đã đặt nhiệt độ điều hòa phòng ngủ của con về 24 độ | 1.24 | **PASS** |
| `TC-129` | Luồng Điều hòa đa tham số #3 | `tăng thêm 2 độ` | Kế thừa AC -> INCREASE_TEMPERATURE=2 | Điều hòa DAIKIN [INCREASE_TEMPERATURE] {'amount': 2} | Đã tăng nhiệt độ điều hòa phòng ngủ của con thêm 2 độ | 1.43 | **PASS** |
| `TC-130` | Luồng Điều hòa đa tham số #4 | `giảm đi 1 độ` | Kế thừa AC -> DECREASE_TEMPERATURE=1 | Điều hòa DAIKIN [DECREASE_TEMPERATURE] {'amount': 1} | Đã giảm nhiệt độ điều hòa phòng ngủ của con thêm 1 độ | 1.41 | **PASS** |
| `TC-131` | Luồng Điều hòa đa tham số #5 | `tắt đi` | Kế thừa AC -> TURN_OFF | Điều hòa DAIKIN [TURN_OFF] | Đã tắt điều hòa phòng ngủ của con | 1.03 | **PASS** |
| `TC-132` | Luồng LED RGB giữ loại thiết bị #1 | `bật đèn led phòng con` | Bật Đèn led RGB phòng con | Đèn led RGB phòng con [TURN_ON] | Đã bật đèn LED Phòng ngủ của con | 2.35 | **PASS** |
| `TC-133` | Luồng LED RGB giữ loại thiết bị #2 | `đổi sang màu xanh dương` | Kế thừa LED RGB -> SET_COLOR xanh dương | Đèn led RGB phòng con [SET_COLOR] {'r': 0, 'g': 100, 'b': 255, 'color': 'xanh dương'} | Đã đổi màu đèn LED RGB Phòng ngủ của con sang màu xanh dương | 1.95 | **PASS** |
| `TC-134` | Luồng LED RGB giữ loại thiết bị #3 | `giảm độ sáng xuống 40 phần trăm` | Giữ nguyên LED RGB (không nhảy sang đèn thường) -> SET_BRIGHTNESS=40 | Đèn led RGB phòng con [SET_BRIGHTNESS] {'brightness': 40} | Đã đặt độ sáng đèn LED RGB Phòng ngủ của con về mức 40 | 4.03 | **PASS** |
| `TC-135` | Luồng LED RGB giữ loại thiết bị #4 | `tắt đi` | Kế thừa LED RGB -> TURN_OFF | Đèn led RGB phòng con [TURN_OFF] | Đã tắt đèn LED RGB Phòng ngủ của con | 1.17 | **PASS** |
| `TC-136` | Luồng Kịch bản (Bật -> Tắt) #1 | `bật kịch bản đi ngủ` | ACTIVATE Đi ngủ | Scene:Đi ngủ(ACTIVATE) [EXEC] | Đã bật kịch bản Đi ngủ. | 0.68 | **PASS** |
| `TC-137` | Luồng Kịch bản (Bật -> Tắt) #2 | `tắt đi` | Kế thừa Scene -> DEACTIVATE Đi ngủ | Scene:Đi ngủ(DEACTIVATE) [EXEC] | Đã tắt kịch bản Đi ngủ. | 1.07 | **PASS** |
| `TC-138` | Luồng Rèm (Mở -> Dừng -> Đóng) #1 | `mở rèm phòng ngủ master` | OPEN Rèm phòng ngủ | Rèm phòng ngủ [OPEN] | Đã mở rèm Phòng ngủ master | 2.03 | **PASS** |
| `TC-139` | Luồng Rèm (Mở -> Dừng -> Đóng) #2 | `dừng lại` | Kế thừa Rèm -> STOP | Rèm phòng ngủ [STOP] | Đã dừng rèm Phòng ngủ master | 1.14 | **PASS** |
| `TC-140` | Luồng Rèm (Mở -> Dừng -> Đóng) #3 | `đóng lại` | Kế thừa Rèm -> CLOSE | Rèm phòng ngủ [CLOSE] | Đã đóng rèm Phòng ngủ master | 1.13 | **PASS** |

---

## 6. Kiểm thử Live End-to-End API & Giọng đọc TTS (`http://localhost:8001/api/voice/process`)

| Mã | Câu nói đầu vào | Mục tiêu kiểm thử | HTTP | Trạng thái / Mã lỗi | Phản hồi văn bản | Có Audio MP3 | Kích thước (base64) | Thời gian tổng (ms) |
| :--- | :--- | :--- | :---: | :--- | :--- | :---: | :---: | :---: |
| `LIVE-01` | `nhà hiện tại có mấy kịch bản` | Truy vấn kịch bản + giọng đọc | 200 | `SUCCESS` | Nhà hiện có 4 kịch bản đang bật: Buổi tối xem phim, Chào buổi sáng, Chào buổi sáng 1, Đi ngủ và 4 kịch bản đang tắt. Bạn muốn bật kịch bản nào? | **CÓ (PASS)** | 123,671 | 5159.7 |
| `LIVE-02` | `phòng khách có mấy thiết bị` | Truy vấn thiết bị + giọng đọc | 200 | `SUCCESS` | Hiện có 5 thiết bị ở Phòng khách: test, Quạt phòng khách, Đèn chiếu TV, Đèn phòng khách, TV TCL. | **CÓ (PASS)** | 93,975 | 2656.4 |
| `LIVE-03` | `bật chế độ cho buổi sáng` | Khớp kịch bản khi STT sai 'chào'->'cho' + báo ngoại tuyến có audio | 200 | `DEVICE_OFFLINE` | Không thể bật kịch bản Chào buổi sáng, các thiết bị hiện đang ngoại tuyến. | **CÓ (PASS)** | 60,183 | 3420.1 |
| `LIVE-04` | `Trời sáng rồi bật cho tôi kịch bản buổi sáng đi` | Gọt lời dẫn + khớp kịch bản + có audio | 200 | `DEVICE_OFFLINE` | Không thể bật kịch bản Chào buổi sáng, các thiết bị hiện đang ngoại tuyến. | **CÓ (PASS)** | 60,183 | 3005.5 |
| `LIVE-05` | `tăng nhiệt độ điều hòa lên 2 độ` | Điều hòa ngoại tuyến + phản hồi giọng đọc | 200 | `FAILED` | Không thể tăng nhiệt độ điều hòa — thiết bị không phản hồi. | **CÓ (PASS)** | 49,431 | 3098.9 |
| `LIVE-06` | `mở cửa chính` | Cửa chính ngoại tuyến + phản hồi giọng đọc | 200 | `FAILED` | Không thể mở cửa — thiết bị không phản hồi. | **CÓ (PASS)** | 36,119 | 2798.4 |
| `LIVE-07` | `bật đèn led màu đỏ phòng con` | LED RGB ngoại tuyến + phản hồi giọng đọc | 200 | `FAILED` | Không thể đổi màu đèn LED Phòng ngủ của con — thiết bị không phản hồi. | **CÓ (PASS)** | 57,879 | 2864.4 |
| `LIVE-08` | `bật chế độ linh tinh không tồn tại` | Từ chối INTENT_NOT_FOUND + giọng đọc Hoài My từ RAM | 200 | `INTENT_NOT_FOUND` | Tôi không hiểu yêu cầu này. Bạn vui lòng nói lại nhé. | **CÓ (PASS)** | 38,807 | 2239.0 |
| `LIVE-09` | `đừng có bật đèn` | Chặn phủ định + giọng đọc Hoài My từ RAM | 200 | `INTENT_NOT_FOUND` | Tôi không hiểu yêu cầu này. Bạn vui lòng nói lại nhé. | **CÓ (PASS)** | 38,807 | 2241.1 |
| `LIVE-10` | `mở nhạc lên đi` | Chặn ngoài phạm vi + giọng đọc Hoài My từ RAM | 200 | `INTENT_NOT_FOUND` | Tôi không hiểu yêu cầu này. Bạn vui lòng nói lại nhé. | **CÓ (PASS)** | 38,807 | 2240.9 |
