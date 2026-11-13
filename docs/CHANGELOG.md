# HESTA Backend - Nhật ký thay đổi

File này ghi lại các thay đổi do Codex thực hiện để dễ theo dõi. Các lần sửa hoặc thay thế tiếp theo trong backend sẽ được bổ sung vào đây.

## 2026-09-14 - Sửa lỗi Hibernate không khởi tạo được EntityManagerFactory

### Hiện tượng

Ứng dụng không thể khởi động và báo lỗi schema validation tại `scene_actions.order_index`:

```text
found [int2 (Types#SMALLINT)], but expecting [smallint (Types#INTEGER)]
```

Các lỗi liên quan đến `jwtAuthenticationFilter`, `customUserDetailsService`, `userRepository` và Tomcat chỉ là lỗi dây chuyền do `entityManagerFactory` không được tạo.

### Nguyên nhân

- PostgreSQL lưu `scene_actions.order_index` dưới dạng `SMALLINT` (`int2`).
- Thuộc tính `SceneAction.order` dùng kiểu Java `int`, nên Hibernate mặc định suy ra JDBC type là `INTEGER`.
- `columnDefinition = "SMALLINT"` chỉ mô tả DDL, chưa buộc Hibernate dùng JDBC type `SMALLINT` khi kiểm tra schema.

### Thay đổi

- File: `src/main/java/com/hesta/backend/entity/SceneAction.java`
- Thêm `@JdbcTypeCode(SqlTypes.SMALLINT)` vào thuộc tính `order`.
- Giữ nguyên kiểu Java `int`, validation từ `0` đến `32767`, và schema PostgreSQL hiện có.
- Không chạy lệnh thay đổi database và không làm thay đổi dữ liệu.

### Kiểm chứng

- Maven biên dịch thành công 90 tệp mã nguồn và 7 tệp mã kiểm thử.
- Chạy riêng 24 kiểm thử liên quan Scene: **24 đạt, 0 thất bại, 0 lỗi**.
- Hibernate/H2 sinh cột `order_index SMALLINT`; các kiểm thử repository đọc/ghi SceneAction thành công.
- Khi context kiểm thử kết nối trực tiếp PostgreSQL cục bộ/Cloud, lỗi sai kiểu
  `order_index` không còn. Hibernate tiếp tục kiểm tra và báo lỗi lược đồ kế tiếp:
  thiếu cột `action` trong bảng `scene_actions`.

### Việc cần làm với database đang kết nối

Cơ sở dữ liệu hiện tại chưa được áp dụng các migration Scene có sẵn:

- `supabase/migrations/20260911160000_scene_management_foundation.sql`
- `supabase/migrations/20260911170000_scene_crud_management.sql`

Với Supabase cục bộ, có thể dựng lại cơ sở dữ liệu từ toàn bộ migration:

```powershell
npx supabase db reset
```

Với Supabase Cloud dùng chung, người phụ trách phát hành cần kiểm tra trước rồi mới đẩy migration:

```powershell
npx supabase db push --dry-run
npx supabase db push
```

Không tự động chạy hai lệnh Cloud ở trên trong lần sửa này vì chúng thay đổi lược đồ cơ sở dữ liệu dùng chung.

### Trạng thái toàn bộ bộ kiểm thử

Lệnh `mvn test` chạy 38 kiểm thử: 35 hoàn tất, 3 lỗi không phát sinh từ thay đổi kiểu JDBC:

- `BackendApplicationTests.contextLoads`: cơ sở dữ liệu đang thiếu migration Scene như mô tả ở trên.
- Hai kiểm thử trong `AuthServiceTest`: mock `HomeRepository` chưa được khởi tạo (`NullPointerException`).

Maven Wrapper (`mvnw.cmd`) cũng đang có lỗi script PowerShell `Cannot index into a null array`, nên việc kiểm chứng được chạy bằng Maven hệ thống (`mvn`).
