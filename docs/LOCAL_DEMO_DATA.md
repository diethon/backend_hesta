# Local demo data

## Mục đích

`supabase/seed.sql` tạo một dataset local có quan hệ đầy đủ để phát triển và demo HESTA mà không
dùng dữ liệu cá nhân hoặc database Cloud.

Dataset gồm:

- 5 tài khoản local, gồm platform `USER` và `ADMIN`.
- 2 căn nhà tách biệt.
- Quan hệ `OWNER`/`MEMBER` và quyền truy cập theo phòng.
- 5 phòng, 3 edge node và 8 thiết bị.
- Device state history và sensor readings.
- Scene, scene actions, schedule và automation rule.
- Security event, device anomaly và notification theo từng recipient.
- Notification preference cho từng user.

## Khởi tạo

Đảm bảo Docker Desktop và Supabase local đang chạy:

```powershell
npx supabase start
```

Nạp thêm dataset vào database local hiện tại và chọn password cho các account demo:

```powershell
.\scripts\Initialize-LocalDemo.ps1
```

Script hỏi password một lần bằng input ẩn rồi chỉ lưu BCrypt hash vào database. Password không được
ghi vào Git hoặc log.

Script ép dữ liệu gửi vào PostgreSQL theo UTF-8, kể cả khi chạy bằng Windows PowerShell 5.1. Nếu
dataset từng được nạp bằng phiên bản cũ và chữ tiếng Việt đã thành dấu `?`, chỉ cần chạy lại script;
các trường văn bản demo sẽ được cập nhật lại mà không tạo bản ghi trùng.

Nếu muốn xóa và dựng lại toàn bộ database local từ migrations trước khi seed:

```powershell
.\scripts\Initialize-LocalDemo.ps1 -ResetDatabase
```

Bạn phải nhập chính xác `RESET` trước khi script xóa database local. Lệnh này không push hoặc thay đổi
Supabase Cloud.

## Tài khoản demo

Tất cả tài khoản sử dụng password bạn nhập khi chạy script.

| Email | Platform role | Home role | Phạm vi |
|---|---|---|---|
| `owner@hesta.local` | `USER` | `OWNER` | Toàn bộ Nhà HESTA Demo. |
| `member@hesta.local` | `USER` | `MEMBER` | Phòng khách và Nhà bếp. |
| `guest@hesta.local` | `USER` | `MEMBER` | Phòng khách và Phòng ngủ. |
| `second.owner@hesta.local` | `USER` | `OWNER` | Căn hộ Gia Huy, dùng để kiểm tra isolation. |
| `admin@hesta.local` | `ADMIN` | Không có | Kiểm thử API platform admin. |

## Topology chính

### Nhà HESTA Demo

```text
Nhà HESTA Demo
├── Phòng khách
│   ├── Đèn trần phòng khách
│   └── Máy lạnh phòng khách
├── Phòng ngủ
│   ├── Cảm biến nhiệt độ phòng ngủ
│   └── Đèn ngủ
├── Nhà bếp
│   └── Quạt thông gió nhà bếp (OFFLINE)
└── Cửa chính
    ├── Cảm biến chuyển động cửa chính
    └── Khóa cửa chính
```

Notification mẫu cho từng user có cả trạng thái read/unread và các type `SECURITY`, `AUTOMATION`,
`DEVICE`, `SYSTEM`.

### Căn hộ Gia Huy

Có một camera trạng thái `ERROR`, một `DeviceAnomaly` và notification `ANOMALY`. Dataset này giúp
kiểm tra user của nhà thứ nhất không thể đọc dữ liệu nhà thứ hai.

## Xem dữ liệu

Supabase Studio:

```text
http://127.0.0.1:54323
```

Các bảng nên xem trước:

```text
users
homes
home_members
home_member_room_access
rooms
edge_nodes
devices
device_state_history
sensor_readings
scenes
automation_rules
security_events
device_anomalies
notifications
user_preferences
```

## Lưu ý bảo mật

- Dataset chỉ dành cho local development.
- Không dùng email/password demo trên môi trường thật.
- Không thêm plaintext password vào `supabase/seed.sql`, `.env.example`, tài liệu hoặc commit.
- `npx supabase db reset` sẽ tạo lại password hash ngẫu nhiên; chạy script để đặt password đăng nhập
  mà bạn chọn sau mỗi lần reset.
