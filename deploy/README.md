# HESTA Production Deployment

VPS: `root@103.77.209.19` (alias: `hesta`) | Domain: `https://hesta.homes` (Let's Encrypt qua `certbot --nginx`)

## 1. Kiến trúc
- **Nginx (Host):** Phục vụ frontend từ `/var/www/hesta`; reverse proxy `/api/`, `/internal/`, `/ws` tới backend (`127.0.0.1:8080`).
- **Docker Compose (`/opt/hesta`):**
  - `backend`: Chạy `app.jar` trên `eclipse-temurin:21-jre` (`127.0.0.1:8080`).
  - `recommendation`: Build từ source (`127.0.0.1:8000`).
  - `mosquitto`: Public port `1883`, xác thực mật khẩu user `hesta`.
  - `redis`: Image `redis:7-alpine`, bảo vệ bằng mật khẩu (`127.0.0.1:6379`).
- **CI/CD (GitHub Actions):** Push `main` tự build, test và deploy; `develop`/PR chỉ chạy CI. Cần cấu hình Secrets ở cả 2 repo: `VPS_HOST`, `VPS_USER`, `VPS_SSH_KEY`.

## 2. Secrets
Tuyệt đối không lưu vào Git. Secrets chỉ nằm trên VPS tại:
- `/opt/hesta/.env` (Backend, Redis, Mosquitto)
- `/opt/hesta/.env.recommendation` (Recommendation service)

## 3. Lệnh hay dùng
Truy cập VPS: `ssh hesta` (hoặc `ssh root@103.77.209.19`), thư mục làm việc: `cd /opt/hesta`.

- **Xem logs:**
  ```bash
  docker compose logs -f [backend|recommendation|mosquitto|redis]
  ```
- **Khởi động / Restart:**
  ```bash
  docker compose up -d                  # Chạy/cập nhật toàn bộ stack
  docker compose restart <service_name> # Khởi động lại 1 container
  ```
- **Thêm Mosquitto user:**
  ```bash
  docker run --rm -v /opt/hesta/mosquitto:/m eclipse-mosquitto:2.0.18 mosquitto_passwd -b /m/passwd <username> <password>
  docker compose restart mosquitto
  ```
- **SSL Certificate:** Certbot tự động gia hạn (kiểm tra thử với `certbot renew --dry-run`).

## 4. Rollback
Khi cần khôi phục phiên bản cũ, vào tab **Actions** trên GitHub:
1. Chọn workflow run thành công gần nhất trên nhánh `main`.
2. Bấm **Re-run all jobs** để kích hoạt deploy lại bản phát hành ổn định đó.
