# Dữ liệu demo cục bộ

## Mục đích

`supabase/seed.sql` tạo một bộ dữ liệu cục bộ có quan hệ đầy đủ để phát triển
và minh họa HESTA mà không dùng dữ liệu cá nhân hoặc cơ sở dữ liệu Cloud.

Bộ dữ liệu gồm:

- 5 tài khoản cục bộ, gồm vai trò nền tảng `USER` và `ADMIN`.
- 2 căn nhà tách biệt.
- Quan hệ `OWNER`/`MEMBER` và quyền truy cập theo phòng.
- 5 phòng, 3 edge node và 8 thiết bị.
- Lịch sử trạng thái thiết bị và số đo cảm biến.
- Scene, hành động scene, lịch và quy tắc tự động hóa.
- Sự kiện bảo mật, bất thường thiết bị và thông báo theo từng người nhận.
- Tùy chọn thông báo cho từng người dùng.

## Khởi tạo

Đảm bảo Docker Desktop và Supabase local đang chạy:

```powershell
npx supabase start
```

Nạp thêm bộ dữ liệu vào cơ sở dữ liệu cục bộ hiện tại và chọn mật khẩu cho
các tài khoản demo:

```powershell
.\scripts\Initialize-LocalDemo.ps1
```

Script hỏi mật khẩu một lần bằng ô nhập ẩn rồi chỉ lưu mã băm BCrypt vào cơ
sở dữ liệu. Mật khẩu không được ghi vào Git hoặc nhật ký.

Script ép dữ liệu gửi vào PostgreSQL theo UTF-8, kể cả khi chạy bằng Windows
PowerShell 5.1. Nếu bộ dữ liệu từng được nạp bằng phiên bản cũ và chữ tiếng
Việt đã thành dấu `?`, chỉ cần chạy lại script; các trường văn bản demo sẽ
được cập nhật mà không tạo bản ghi trùng.

Nếu muốn xóa và dựng lại toàn bộ database local từ migrations trước khi seed:

```powershell
.\scripts\Initialize-LocalDemo.ps1 -ResetDatabase
```

Bạn phải nhập chính xác `RESET` trước khi script xóa cơ sở dữ liệu cục bộ.
Lệnh này không đẩy migration hoặc thay đổi Supabase Cloud.

## Tài khoản demo

Tất cả tài khoản sử dụng mật khẩu bạn nhập khi chạy script.

| Email | Vai trò nền tảng | Vai trò trong nhà | Phạm vi |
|---|---|---|---|
| `owner@hesta.local` | `USER` | `OWNER` | Toàn bộ Nhà HESTA Demo. |
| `member@hesta.local` | `USER` | `MEMBER` | Phòng khách và Nhà bếp. |
| `guest@hesta.local` | `USER` | `MEMBER` | Phòng khách và Phòng ngủ. |
| `second.owner@hesta.local` | `USER` | `OWNER` | Căn hộ Gia Huy, dùng để kiểm tra cách ly dữ liệu. |
| `admin@hesta.local` | `ADMIN` | Không có | Kiểm thử API quản trị nền tảng. |

## Cấu trúc chính

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

Thông báo mẫu cho từng người dùng có cả trạng thái đã đọc/chưa đọc và các loại
`SECURITY`, `AUTOMATION`, `DEVICE`, `SYSTEM`.

### Căn hộ Gia Huy

Có một camera trạng thái `ERROR`, một `DeviceAnomaly` và thông báo `ANOMALY`.
Bộ dữ liệu giúp kiểm tra người dùng của nhà thứ nhất không thể đọc dữ liệu nhà
thứ hai.

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

- Bộ dữ liệu chỉ dành cho phát triển cục bộ.
- Không dùng email/mật khẩu demo trên môi trường thật.
- Không thêm mật khẩu dạng rõ vào `supabase/seed.sql`, `.env.example`, tài liệu hoặc commit.
- `npx supabase db reset` sẽ tạo lại mã băm mật khẩu ngẫu nhiên; chạy script để
  đặt mật khẩu đăng nhập bạn chọn sau mỗi lần reset.
