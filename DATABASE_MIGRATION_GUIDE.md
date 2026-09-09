# Hướng dẫn quản lý database migration của HESTA

Tài liệu này quy định cách cả nhóm quản lý mọi thay đổi schema database của HESTA.

## Nguồn schema duy nhất

`supabase/migrations/` là nguồn schema duy nhất của dự án.

```text
supabase/migrations/
  20260909141328_init_database.sql       # Schema khởi tạo (V1)
  20260909141454_database_hardening.sql  # Bổ sung ràng buộc và index (V2)
```

Supabase chạy các migration theo thứ tự timestamp. Timestamp, không phải tiền tố `V1` hoặc `V2`, là version migration Supabase sử dụng.

Không thêm migration schema vào `src/main/resources/db/migration/`. Flyway của Spring đang được tắt trong `application-local.properties`; Supabase CLI là công cụ duy nhất được dùng để áp dụng schema migration.

## Vai trò và môi trường

- Mỗi thành viên chạy một Supabase local stack riêng bằng Docker Desktop.
- `hesta-development` trên Supabase Cloud là database tích hợp dùng chung.
- Chỉ database release owner được phân công, hoặc GitHub Actions trong tương lai, chạy `supabase db push` lên Cloud.
- Mọi thay đổi schema phải qua pull request và review trước khi deploy lên Cloud.

## Thiết lập lần đầu cho mỗi thành viên

### 1. Lấy backend repository

```powershell
git clone https://github.com/diethon/backend_hesta.git
cd backend_hesta
```

### 2. Cài đặt yêu cầu cần thiết

- Cài Docker Desktop, bật Linux/WSL 2 engine và chờ trạng thái `Engine running`.
- Cài Java 21 và Maven để chạy backend.
- Cài Node.js để chạy Supabase CLI bằng `npx`.

Kiểm tra Docker:

```powershell
docker version
```

### 3. Khởi động và khởi tạo Supabase local

```powershell
npx supabase start
npx supabase db reset
```

`db reset` chỉ xóa database Supabase local, sau đó chạy lại toàn bộ migration trong `supabase/migrations/`. Đây là bước bắt buộc sau khi clone project hoặc sau khi pull migration mới đã merge.

Mở Supabase Studio local tại `http://127.0.0.1:54323` để xem bảng và dữ liệu test local.

### 4. Chạy backend kết nối với Supabase Cloud (Dùng chung cho cả Team)

Để tất cả thành viên cùng làm việc trên một Database (thấy dữ liệu của nhau), chúng ta sẽ trỏ Backend trực tiếp lên Supabase Cloud thay vì dùng Local.

Bạn cần cấu hình các biến môi trường (Environment Variables) trước khi chạy Spring Boot.

**Cách chạy bằng Terminal/PowerShell:**
```powershell
$env:SPRING_PROFILES_ACTIVE="local"
$env:DATABASE_URL="jdbc:postgresql://aws-0-ap-southeast-1.pooler.supabase.com:6543/postgres"
$env:DATABASE_USERNAME="postgres.your_project_ref"
$env:DATABASE_PASSWORD="your_cloud_database_password"
mvn spring-boot:run
```
*(Thay thế URL, USERNAME và PASSWORD bằng thông tin lấy từ mục **Project Settings -> Database** trên Supabase Dashboard).*

**Cách chạy bằng IntelliJ IDEA:**
1. Mở Edit Configurations... của ứng dụng Spring Boot.
2. Tìm ô **Environment variables**.
3. Nhập vào: `DATABASE_URL=jdbc:postgresql://[YOUR_URL];DATABASE_USERNAME=[YOUR_USER];DATABASE_PASSWORD=[YOUR_PASSWORD]`

⚠️ **LƯU Ý QUAN TRỌNG:** Tuyệt đối KHÔNG hardcode password vào file `application-local.properties` và KHÔNG commit password lên Git.

---

## Quản lý cấu trúc Database (Migrations)

Mặc dù chúng ta dùng chung Database Cloud cho dữ liệu, **việc thay đổi cấu trúc bảng** (thêm cột, tạo bảng mới) vẫn phải thông qua Migration để đồng bộ cho toàn Team.

### 1. Làm việc trên feature branch

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

Lệnh tạo một file SQL có timestamp trong `supabase/migrations/`. Chỉ thêm SQL tăng dần cần cho thay đổi mới. Không chỉnh sửa V1 hoặc V2 đã được áp dụng.

Ví dụ:

```sql
alter table public.devices
add column serial_number varchar(100);
```

### 3. Kiểm thử local

```powershell
npx supabase db reset
$env:SPRING_PROFILES_ACTIVE="local"
mvn test
```

Kiểm tra thêm các bảng bị ảnh hưởng trong Supabase Studio local. Nếu migration lỗi, chỉ sửa migration mới trước khi commit.

### 4. Commit và tạo pull request

```powershell
git add supabase/migrations
git add <cac-file-backend-lien-quan>
git commit -m "feat(db): add device serial number"
git push -u origin feature/add-device-field
```

Tạo pull request vào `main`. Mô tả PR phải nêu mục đích migration, các bảng bị ảnh hưởng và liệu dữ liệu cũ có cần backfill hay không.

## Deploy migration đã review lên Supabase Cloud

Chỉ release owner thực hiện sau khi PR được merge vào `main`.

```powershell
git checkout main
git pull origin main
npx supabase login
npx supabase link --project-ref <PROJECT_REF>
npx supabase db push --dry-run
npx supabase db push
```

`PROJECT_REF` là chuỗi nằm sau `/project/` trong URL project trên Supabase Dashboard. CLI sẽ hỏi Cloud database password; không gửi password này vào Git hoặc group chat.

Sau khi deploy, kiểm tra bảng hoặc cột mới trong Table Editor của Supabase Cloud Dashboard.

## Quy tắc tránh xung đột migration

1. Không tạo, sửa hoặc xóa schema trực tiếp trong Cloud Table Editor hay Cloud SQL Editor.
2. Không chạy `supabase db push` đồng thời từ hai máy developer.
3. Không đổi tên, xóa hoặc sửa migration đã deploy lên Cloud.
4. Nếu migration đã deploy cần chỉnh sửa, tạo một migration mới để sửa.
5. Sau khi pull migration đã merge, mỗi developer chạy `npx supabase db reset` trước khi code theo schema mới.
6. Nếu hai branch tạo migration gần cùng lúc, rebase branch merge sau lên `main` và tạo migration mới nếu thứ tự timestamp hoặc dependency SQL cần điều chỉnh.

## Xử lý schema drift

Nếu ai đó đã sửa Cloud schema trực tiếp, dừng deploy tiếp và báo cho release owner. Release owner kiểm tra trạng thái migration:

```powershell
npx supabase migration list
```

Release owner có thể dùng `supabase db pull` để đưa thay đổi remote đã xác minh thành migration mới. Không dùng `migration repair` khi chưa xác minh cả schema lẫn migration history.

## Dữ liệu và bảo mật

- Đặt dữ liệu demo có thể tái tạo vào `supabase/seed.sql`; không đặt dữ liệu production hoặc dữ liệu cá nhân vào file này.
- Không commit file `.env`, Cloud database password, service-role key, JWT secret hoặc OAuth credentials.
- Cloud Table Editor dùng để xem dữ liệu. Việc thêm dữ liệu demo trực tiếp trên Cloud cần thông báo nhóm để quá trình test vẫn tái lập được.

## Các lệnh thường dùng

```powershell
# Khởi động hoặc dừng Supabase local
npx supabase start
npx supabase stop

# Dựng lại database local từ migrations
npx supabase db reset

# Xem trạng thái migration local và Cloud sau khi đã link
npx supabase migration list

# Xem trước và deploy migration mới lên Cloud (chỉ release owner)
npx supabase db push --dry-run
npx supabase db push
```
