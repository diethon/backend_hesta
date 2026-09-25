# HESTA Backend - Change Log

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

- Maven biên dịch thành công 90 source files và 7 test source files.
- Chạy riêng 24 test liên quan Scene: **24 passed, 0 failed, 0 errors**.
- Hibernate/H2 sinh cột `order_index SMALLINT` và các repository test đọc/ghi SceneAction thành công.
- Khi test context kết nối trực tiếp PostgreSQL local/cloud, lỗi sai kiểu `order_index` không còn xuất hiện. Hibernate đã kiểm tra tiếp và báo lỗi schema kế tiếp: thiếu cột `action` trong bảng `scene_actions`.

### Việc cần làm với database đang kết nối

Database hiện tại chưa được áp dụng migration Scene có sẵn:

- `supabase/migrations/20260911160000_scene_management_foundation.sql`
- `supabase/migrations/20260911170000_scene_crud_management.sql`

Với Supabase local, có thể dựng lại database từ toàn bộ migration:

```powershell
npx supabase db reset
```

Với Supabase Cloud dùng chung, release owner cần kiểm tra trước rồi mới đẩy migration:

```powershell
npx supabase db push --dry-run
npx supabase db push
```

Không tự động chạy hai lệnh Cloud ở trên trong lần sửa này vì chúng thay đổi schema database dùng chung.

### Trạng thái full test suite

Lệnh `mvn test` chạy 38 test: 35 test hoàn tất, 3 errors không phát sinh từ thay đổi JDBC type:

- `BackendApplicationTests.contextLoads`: database đang thiếu migration Scene như mô tả ở trên.
- Hai test trong `AuthServiceTest`: mock `HomeRepository` chưa được khởi tạo trong test (`NullPointerException`).

Maven Wrapper (`mvnw.cmd`) cũng đang có lỗi script PowerShell `Cannot index into a null array`, nên việc kiểm chứng được chạy bằng Maven hệ thống (`mvn`).
