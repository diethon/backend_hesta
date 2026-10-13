# Hướng dẫn quản lý migration cơ sở dữ liệu HESTA

Tài liệu này quy định cách nhóm quản lý mọi thay đổi lược đồ cơ sở dữ liệu HESTA.

## Nguồn lược đồ duy nhất

`supabase/migrations/` là nguồn lược đồ duy nhất của dự án.

```text
supabase/migrations/
  20260909141328_init_database.sql       # Lược đồ khởi tạo (V1)
  20260909141454_database_hardening.sql  # Bổ sung ràng buộc và chỉ mục (V2)
```

Supabase chạy migration theo thứ tự timestamp. Timestamp, không phải tiền tố
`V1` hoặc `V2`, là phiên bản mà Supabase sử dụng.

Không thêm migration lược đồ vào `src/main/resources/db/migration/`. Flyway của
Spring đang tắt trong `application-local.properties`; Supabase CLI là công cụ
duy nhất dùng để áp dụng migration lược đồ.

## Vai trò và môi trường

- Mỗi thành viên chạy một Supabase local stack riêng bằng Docker Desktop.
- `hesta-development` trên Supabase Cloud là cơ sở dữ liệu tích hợp dùng chung.
- Chỉ người phụ trách phát hành cơ sở dữ liệu được phân công, hoặc GitHub Actions
  trong tương lai, mới chạy `supabase db push` lên Cloud.
- Mọi thay đổi lược đồ phải qua pull request và review trước khi triển khai Cloud.

## Thiết lập lần đầu

### 1. Lấy repository backend

```powershell
git clone https://github.com/diethon/backend_hesta.git
cd backend_hesta
```

### 2. Cài đặt yêu cầu

- Cài Docker Desktop, bật Linux/WSL 2 engine và chờ trạng thái `Engine running`.
- Cài Java 21 và Maven để chạy backend.
- Cài Node.js để chạy Supabase CLI bằng `npx`.

Kiểm tra Docker:

```powershell
docker version
```

### 3. Khởi động Supabase cục bộ

```powershell
npx supabase start
npx supabase db reset
```

`db reset` chỉ xóa cơ sở dữ liệu Supabase cục bộ rồi chạy lại toàn bộ migration
trong `supabase/migrations/`. Đây là bước bắt buộc sau khi clone dự án hoặc
sau khi pull migration mới đã merge.

Mở Supabase Studio cục bộ tại `http://127.0.0.1:54323` để xem bảng và dữ liệu test.

### 4. Chạy backend kết nối Supabase Cloud dùng chung

Để các thành viên cùng thấy dữ liệu, có thể trỏ backend trực tiếp đến Supabase
Cloud thay vì cơ sở dữ liệu cục bộ. Cấu hình biến môi trường trước khi chạy Spring Boot.

**Chạy bằng Terminal/PowerShell:**

```powershell
$env:SPRING_PROFILES_ACTIVE="local"
$env:DATABASE_URL="jdbc:postgresql://aws-0-ap-southeast-1.pooler.supabase.com:6543/postgres"
$env:DATABASE_USERNAME="postgres.your_project_ref"
$env:DATABASE_PASSWORD="your_cloud_database_password"
mvn spring-boot:run
```

Thay URL, USERNAME và PASSWORD bằng thông tin trong **Project Settings ->
Database** của Supabase Dashboard.

**Chạy bằng IntelliJ IDEA:**

1. Mở Edit Configurations... của ứng dụng Spring Boot.
2. Tìm ô **Environment variables**.
3. Nhập `DATABASE_URL=jdbc:postgresql://[YOUR_URL];DATABASE_USERNAME=[YOUR_USER];DATABASE_PASSWORD=[YOUR_PASSWORD]`.

Không hardcode mật khẩu trong `application-local.properties` và không commit
mật khẩu lên Git.

## Quản lý thay đổi lược đồ

Dù dữ liệu dùng chung trên Cloud, mọi thay đổi cấu trúc bảng (thêm cột, tạo bảng
mới) vẫn phải đi qua migration để cả nhóm đồng bộ.

### 1. Làm việc trên nhánh tính năng

```powershell
git checkout main
git pull origin main
git checkout -b feature/add-device-field
```

### 2. Tạo migration mới

Đặt tên mô tả rõ ràng, viết thường và dùng dấu gạch dưới:

```powershell
npx supabase migration new add_device_serial_number
```

Lệnh tạo tệp SQL có timestamp trong `supabase/migrations/`. Chỉ thêm SQL tăng
dần cần cho thay đổi mới; không chỉnh sửa V1 hoặc V2 đã áp dụng.

Ví dụ:

```sql
alter table public.devices
add column serial_number varchar(100);
```

### 3. Kiểm thử cục bộ

```powershell
npx supabase db reset
$env:SPRING_PROFILES_ACTIVE="local"
mvn test
```

Kiểm tra thêm các bảng bị ảnh hưởng trong Supabase Studio cục bộ. Nếu migration
lỗi, chỉ sửa migration mới trước khi commit.

### 4. Commit và tạo pull request

```powershell
git add supabase/migrations
git add <cac-file-backend-lien-quan>
git commit -m "feat(db): add device serial number"
git push -u origin feature/add-device-field
```

Pull request vào `main` phải nêu mục đích migration, các bảng bị ảnh hưởng và
dữ liệu cũ có cần backfill hay không.

## Triển khai migration đã review lên Supabase Cloud

Chỉ người phụ trách phát hành thực hiện sau khi PR được merge vào `main`.

```powershell
git checkout main
git pull origin main
npx supabase login
npx supabase link --project-ref <PROJECT_REF>
npx supabase db push --dry-run
npx supabase db push
```

`PROJECT_REF` là chuỗi sau `/project/` trong URL project trên Supabase Dashboard.
CLI sẽ hỏi mật khẩu cơ sở dữ liệu Cloud; không gửi mật khẩu này vào Git hay nhóm chat.

Sau khi triển khai, kiểm tra bảng hoặc cột mới trong Table Editor của Supabase Cloud.

## Quy tắc tránh xung đột migration

1. Không tạo, sửa hoặc xóa lược đồ trực tiếp trong Cloud Table Editor hay Cloud SQL Editor.
2. Không chạy `supabase db push` đồng thời từ hai máy developer.
3. Không đổi tên, xóa hoặc sửa migration đã triển khai lên Cloud.
4. Nếu migration đã triển khai cần chỉnh sửa, tạo migration mới để sửa.
5. Sau khi pull migration đã merge, mỗi developer chạy `npx supabase db reset` trước khi code theo lược đồ mới.
6. Nếu hai nhánh tạo migration gần cùng lúc, rebase nhánh merge sau lên `main` và tạo migration mới nếu thứ tự timestamp hoặc phụ thuộc SQL cần điều chỉnh.

## Xử lý sai lệch lược đồ

Nếu có người sửa lược đồ Cloud trực tiếp, dừng triển khai và báo người phụ trách
phát hành. Kiểm tra trạng thái migration:

```powershell
npx supabase migration list
```

Có thể dùng `supabase db pull` để đưa thay đổi remote đã xác minh thành migration
mới. Không dùng `migration repair` khi chưa xác minh cả lược đồ lẫn lịch sử migration.

## Dữ liệu và bảo mật

- Đặt dữ liệu demo có thể tái tạo vào `supabase/seed.sql`; không đặt dữ liệu production hoặc dữ liệu cá nhân vào đó.
- Không commit `.env`, mật khẩu cơ sở dữ liệu Cloud, service-role key, JWT secret hoặc OAuth credentials.
- Cloud Table Editor dùng để xem dữ liệu. Việc thêm dữ liệu demo trực tiếp trên Cloud cần thông báo nhóm để việc kiểm thử vẫn tái lập được.

## Các lệnh thường dùng

```powershell
# Khởi động hoặc dừng Supabase cục bộ
npx supabase start
npx supabase stop

# Dựng lại cơ sở dữ liệu cục bộ từ migration
npx supabase db reset

# Xem trạng thái migration cục bộ và Cloud sau khi link
npx supabase migration list

# Xem trước và triển khai migration mới lên Cloud (chỉ người phụ trách phát hành)
npx supabase db push --dry-run
npx supabase db push
```
