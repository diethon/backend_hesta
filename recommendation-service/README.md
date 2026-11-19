# HESTA ML Service – Gợi ý thói quen

Dịch vụ FastAPI tìm thói quen bật/tắt thiết bị của người dùng và đề xuất quy tắc hẹn giờ. Chỉ backend HESTA gọi dịch vụ này (server to server).

## 1. Cách hoạt động

1. **Dữ liệu**: bảng `behavior_events`, các dòng `DEVICE_ACTION` với `TURN_ON`/`TURN_OFF` do người làm (nguồn `MANUAL`, `VOICE`, `GESTURE`, `PHYSICAL`). Mọi nguồn khác (kịch bản, tự động hoá, lịch, cảm biến, `SIMULATED` của bộ sinh dữ liệu mẫu…) bị loại; bật `ML_INCLUDE_SIMULATED=true` chỉ khi demo bằng dữ liệu mẫu. Backend ghi các dòng này cho mọi lệnh tay (web, app, điều hoà, cổng) và cho công tắc vật lý.
2. **Tìm thói quen**: gom giờ thao tác của từng thiết bị theo vòng tròn 24h (tách được thói quen sáng/tối, đúng qua nửa đêm). Một cụm chỉ là thói quen khi xảy ra ở ≥ 40% số ngày người dùng có thao tác (cùng loại ngày thường/cuối tuần) và có ≥ 5 ngày dữ liệu.
3. **Độ tin cậy**:
   - Khi nhà **chưa có mô hình**: cận dưới Wilson của tỉ lệ ngày (vd 15/15 ngày thường → 94%, 3/3 ngày → 50%). Loại gợi ý `RULE_BASED`.
   - Khi nhà **đã có mô hình**: xác suất (đã hiệu chỉnh) mô hình Random Forest dự đoán thói quen sẽ xảy ra (trong ±30 phút quanh giờ thói quen) vào ngày kế tiếp cùng loại. Loại gợi ý `ML`.
4. **Mô hình (mỗi nhà một mô hình)**: mỗi dòng dữ liệu là *(thói quen, ngày)*, nhãn 1 nếu hôm đó thói quen xảy ra trong cửa sổ giờ của nó (±2 độ lệch chuẩn của chính thói quen, từ 30 đến 60 phút), 0 nếu không (chỉ tính ngày người dùng có thao tác tay, đúng loại ngày của thói quen). Đặc trưng chỉ nhìn tối đa 14 ngày **trước đó**: tỉ lệ xảy ra, tỉ lệ 3 ngày gần nhất, chuỗi ngày liên tiếp, số ngày từ lần cuối, độ lệch giờ, bật/tắt, cuối tuần. Cố ý **không** dùng giờ trong ngày hay loại thiết bị: mô hình sẽ học thuộc từng thói quen và kém hơn đếm tần suất. Cùng một hàm tạo đặc trưng cho huấn luyện và suy luận.
5. **Đánh giá**: tách theo ngày (20% ngày mới nhất làm kiểm thử; thói quen chỉ được tìm trên các ngày huấn luyện). Chỉ số chính là **Brier score** (độ đúng của xác suất, vì xác suất này hiển thị cho người dùng là "độ tin cậy"), so với đếm tần suất có làm trơn `(số ngày xảy ra + 1) / (số ngày + 2)`. Mô hình được train trên mọi thói quen ứng viên (kể cả thói quen mới hình thành/đang phai) nhưng chỉ được **chấm trên các thói quen sẽ hiển thị** (≥ 5 ngày lịch sử, tỉ lệ ≥ 40%), và cần tối thiểu 60 dòng huấn luyện / 20 dòng kiểm thử. Mô hình chỉ được dùng khi Brier không kém đếm tần suất, và chỉ thay mô hình cũ khi không kém hơn trên **cùng các ngày kiểm thử**. Giá trị chính so với đếm tần suất: nhận ra thói quen **mới bắt đầu** hoặc **đã bỏ** nhanh hơn.

Cần ít nhất 20 lần bật/tắt thật trải trên ≥ 7 ngày để huấn luyện; trước đó dịch vụ vẫn gợi ý theo tần suất.

## 2. Cấu hình

Sao chép `.env.example` thành `.env` (không commit file này):

| Biến | Ý nghĩa |
|---|---|
| `DATABASE_URL` | Chuỗi kết nối Postgres (bắt buộc) |
| `ML_API_KEY` | Khoá dùng chung với backend (`HESTA_ML_SERVICE_API_KEY`), gửi qua header `X-API-Key`. Để trống chỉ khi chạy local |
| `CONFIDENCE_THRESHOLD` | Ngưỡng hiển thị gợi ý, mặc định 0.80 |
| `MIN_SAMPLES_REQUIRED`, `MIN_TRAIN_DAYS` | Điều kiện huấn luyện, mặc định 20 thao tác / 7 ngày |
| `TIMEZONE` | Mặc định `Asia/Ho_Chi_Minh` |
| `ML_INCLUDE_SIMULATED` | `true` để học cả dữ liệu mẫu (`SIMULATED`), chỉ dùng khi demo. Mặc định `false` |
| `KEEP_MODEL_VERSIONS` | Số file mô hình ứng viên giữ lại mỗi nhà, mặc định 10 |

Mô hình lưu tại `artifacts/homes/<home_id>/` (`model_latest.joblib`, `model_metadata.json`, các phiên bản cũ để đối chiếu).

## 3. Chạy

```bash
pip install -r requirements.txt
python -m uvicorn app.main:app --port 8000
```

Huấn luyện từ dòng lệnh (cùng logic với `POST /train`):

```bash
python train.py                      # mọi nhà có dữ liệu
python train.py --home-id <uuid> --plot
```

`--plot` cần `pip install matplotlib`, tạo ma trận nhầm lẫn, độ quan trọng đặc trưng và biểu đồ độ tin cậy.

Docker: `docker compose up --build` (đọc `.env`). Chạy **một worker**: mô hình được cache trong bộ nhớ tiến trình. `requirements.txt` ghim phiên bản vì file `.joblib` chỉ load được với đúng phiên bản scikit-learn đã train; khi nâng phiên bản phải train lại.

Backend chờ tối đa `HESTA_ML_SERVICE_TIMEOUT_MS` (mặc định 30000) cho mỗi lần gọi gợi ý, vì đọc dữ liệu từ Postgres từ xa có lúc mất >10 giây.

## 4. API

Mọi endpoint trừ `/health` yêu cầu header `X-API-Key` khi `ML_API_KEY` được đặt.

| Method | Endpoint | Mô tả |
|---|---|---|
| `GET` | `/health` | Kiểm tra dịch vụ |
| `GET` | `/model/status?home_id=` | Trạng thái mô hình của một nhà (`READY` / `COLD_START`) và chỉ số |
| `POST` | `/recommendations/generate` | `{home_id, from_time, to_time, threshold}` → danh sách gợi ý |
| `POST` | `/train` | `{home_id?}`: huấn luyện một nhà, hoặc mọi nhà khi bỏ trống |

## 5. Kiểm thử

```bash
python -m unittest discover -s . -p "test_*.py" -t .
```

Test dùng dữ liệu tổng hợp, không cần cơ sở dữ liệu.
