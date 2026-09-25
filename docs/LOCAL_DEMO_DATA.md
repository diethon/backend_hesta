# Dữ liệu demo cục bộ

## Mục đích

`supabase/seed.sql` tạo một bộ dữ liệu cục bộ có quan hệ đầy đủ để phát triển
và minh họa HESTA mà không dùng dữ liệu cá nhân hoặc cơ sở dữ liệu Cloud.

Bộ dữ liệu gồm:

- 7 tài khoản cục bộ, gồm vai trò nền tảng `USER` và `ADMIN`.
- 3 căn nhà tách biệt, gồm một nhà ba tầng dành cho Digital Twin 3D.
- Quan hệ `OWNER`/`MEMBER` và quyền truy cập theo phòng.
- 13 phòng, 3 edge node và 16 thiết bị.
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
| `multifloor.owner@hesta.local` | `USER` | `OWNER` | Nhà thông minh 3 tầng, 8 phòng và 14 marker Twin. |
| `fresh@hesta.local` | `USER` | Chưa có | Tài khoản trống để tự tạo nhà, phòng và sơ đồ 2D từ đầu. |
| `admin@hesta.local` | `ADMIN` | Không có | Kiểm thử API quản trị nền tảng. |

Để thử quy trình từ đầu, đăng nhập bằng `fresh@hesta.local` với mật khẩu demo
đã chọn khi chạy script khởi tạo. Tài khoản này không thuộc nhà nào và không
có phòng, thiết bị hay sơ đồ Twin. Chọn **Tạo nhà mới** ở trang Tổng quan, mở
**Digital Twin**, chọn **Chỉnh sửa sơ đồ**, tạo phòng trong bảng **Đối tượng chưa
đặt**, đặt phòng lên sơ đồ 2D rồi chọn **Lưu bố cục**. Nhà vừa tạo có sơ đồ rỗng
với phiên bản 0; hệ thống không tự tạo sẵn hình nhà 2D.

### Thêm phòng và thiết bị mẫu cho tài khoản fresh

Sau khi tạo nhà bằng `fresh@hesta.local`, nạp 9 thiết bị mẫu vào nhà đó bằng
lệnh sau từ thư mục `backend_hesta`:

```powershell
$OutputEncoding = [System.Text.UTF8Encoding]::new($false)
Get-Content -LiteralPath scripts/Add-FreshTwinDemoDevices.sql -Encoding utf8 -Raw |
    docker exec -i -e PGCLIENTENCODING=UTF8 supabase_db_backend_hesta psql -v ON_ERROR_STOP=1 -U postgres -d postgres
```

Lệnh có thể chạy lại mà không tạo thiết bị trùng. Nó yêu cầu tài khoản fresh là
chủ của đúng một nhà; không tạo phòng, ghép cặp phần cứng, số đo cảm biến hoặc
bố cục Twin. Để thêm 5 phòng mẫu vào cùng nhà:

```powershell
Get-Content -LiteralPath scripts/Add-FreshTwinDemoRooms.sql -Encoding utf8 -Raw |
    docker exec -i -e PGCLIENTENCODING=UTF8 supabase_db_backend_hesta psql -v ON_ERROR_STOP=1 -U postgres -d postgres
```

Script phòng cũng có thể chạy lại mà không tạo bản ghi trùng. Cả phòng và thiết
bị nằm trong các tab tương ứng của bảng **Đối tượng chưa đặt**. Thiết bị có trạng
thái **Chưa xác định** cho đến khi có luồng ghép cặp thật. Đặt phòng và thiết bị
lên sơ đồ 2D, lưu bố cục rồi mở chế độ 3D để xem mô hình.

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

### Nhà thông minh 3 tầng

Tài khoản `multifloor.owner@hesta.local` sở hữu riêng một nhà gồm 8 phòng:
ba phòng tầng 1, ba phòng tầng 2 và hai phòng tầng 3. Bố cục Twin chứa 8 thiết
bị cùng 6 luồng cảm biến, tổng cộng 14 marker. Đây là dữ liệu để kiểm tra chế
độ **Toàn nhà / T1 / T2 / T3** và **Tách tầng / Xếp chồng** bằng API thật.

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
twin_layouts
twin_room_layouts
twin_node_layouts
```

## Lưu ý bảo mật

- Bộ dữ liệu chỉ dành cho phát triển cục bộ.
- Không dùng email/mật khẩu demo trên môi trường thật.
- Không thêm mật khẩu dạng rõ vào `supabase/seed.sql`, `.env.example`, tài liệu hoặc commit.
- `npx supabase db reset` sẽ tạo lại mã băm mật khẩu ngẫu nhiên; chạy script để
  đặt mật khẩu đăng nhập bạn chọn sau mỗi lần reset.
