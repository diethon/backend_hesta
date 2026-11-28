package com.hesta.backend.contract;

import java.math.BigDecimal;
import java.util.List;

/**
 * Bộ hợp đồng mẫu chuẩn pháp lý Việt Nam, gói giải pháp gợi ý và thông tin doanh nghiệp / ngân hàng mẫu của HESTA.
 */
public final class ContractTemplates {

    public static final String DEFAULT_PROVIDER_COMPANY = "CÔNG TY TNHH CÔNG NGHỆ NHÀ THÔNG MINH HESTA";
    public static final String DEFAULT_PROVIDER_TAX_CODE = "0318202688";
    public static final String DEFAULT_PROVIDER_ADDRESS = "Lô E2a-7, Đường D1, Khu Công nghệ cao, TP. Thủ Đức, TP. Hồ Chí Minh";
    public static final String DEFAULT_PROVIDER_REPRESENTATIVE = "Bộ phận Triển khai & Quản trị Hệ thống HESTA";
    public static final String DEFAULT_PROVIDER_HOTLINE = "1900 2026";
    public static final String DEFAULT_PROVIDER_EMAIL = "contracts@hesta.homes";

    public static final String DEFAULT_BANK_NAME = "TMCP Ngoại thương Việt Nam (Vietcombank) - Chi nhánh Thủ Đức";
    public static final String DEFAULT_BANK_ACCOUNT_NUMBER = "102688882026";
    public static final String DEFAULT_BANK_ACCOUNT_HOLDER = "CONG TY TNHH CONG NGHE HESTA";

    public record TemplateSpec(
            String code,
            String name,
            String subtitle,
            String termsTitle,
            int defaultDepositPercent,
            BigDecimal defaultInstallationFee,
            String termsContent
    ) {}

    public record PackageBomItem(
            String modelCode,
            String deviceType,
            String roomLabel,
            int quantity
    ) {}

    public record PackageSpec(
            String code,
            String name,
            String nameEn,
            String subtitle,
            String subtitleEn,
            String badge,
            String badgeEn,
            String templateCode,
            BigDecimal estimatedPrice,
            BigDecimal installationFee,
            List<String> highlights,
            List<String> highlightsEn,
            List<PackageBomItem> bomItems
    ) {}

    private static final String STANDARD_TERMS = """
            ĐIỀU 1. PHẠM VI CUNG CẤP VÀ LẮP ĐẶT
            1.1. Bên A (HESTA) cam kết cung cấp đúng chủng loại, mã model, số lượng thiết bị nhà thông minh và triển khai lắp đặt, cấu hình đồng bộ lên hệ thống quản trị HESTA theo đúng Bảng kê thiết bị đính kèm tại Hợp đồng này.
            1.2. Toàn bộ thiết bị bàn giao đều là hàng chính hãng HESTA mới 100%, đã qua kiểm định an toàn điện và tương thích giao thức MQTT/Edge Gateway của hệ thống.

            ĐIỀU 2. GIÁ TRỊ HỢP ĐỒNG, ĐẶT CỌC VÀ THANH TOÁN
            2.1. Tổng giá trị hợp đồng bao gồm chi phí thiết bị, chi phí lắp đặt – cấu hình (sau khi áp dụng ưu đãi/chiết khấu nếu có) được nêu chi tiết trong Bảng dự toán của Hợp đồng.
            2.2. Đợt 1 – Đặt cọc: Ngay sau khi hai bên ký xác nhận Hợp đồng điện tử, Bên B thực hiện chuyển khoản đặt cọc theo đúng tỷ lệ, số tiền, thông tin tài khoản ngân hàng và cú pháp chuyển khoản ghi trong Hợp đồng. Sau khi Bên A đối soát và xác nhận đã nhận tiền cọc trên tài khoản doanh nghiệp, Bên A sẽ chốt lịch và triển khai lắp đặt.
            2.3. Đợt 2 – Thanh toán quyết toán: Bên B thanh toán phần giá trị còn lại ngay sau khi hai bên hoàn tất nghiệm thu vận hành thực tế và Bên A bàn giao quyền quản trị Chủ nhà (OWNER) trên hệ thống HESTA.

            ĐIỀU 3. THỜI GIAN, ĐỊA ĐIỂM VÀ ĐIỀU KIỆN THI CÔNG
            3.1. Bên A triển khai lắp đặt theo đúng khung thời gian đã thống nhất tại công trình của Bên B. Trường hợp cần thay đổi lịch hẹn, mỗi bên thông báo cho bên còn lại trước ít nhất 12 giờ trên Cổng Hợp đồng HESTA.
            3.2. Bên B tạo điều kiện mặt bằng, nguồn điện ổn định và kết nối Wi-Fi nội bộ để kỹ thuật viên Bên A tiến hành đấu nối, cấu hình thiết bị và kiểm thử an toàn.

            ĐIỀU 4. NGHIỆM THU VÀ BÀN GIAO CHÌA KHÓA TRAO TAY (TURNKEY HANDOVER)
            4.1. Sau khi hoàn tất đấu nối phần cứng, kỹ thuật viên Bên A trực tiếp khởi tạo cấu trúc Ngôi nhà, các Phòng chức năng và gán toàn bộ thiết bị theo Hợp đồng vào tài khoản Chủ nhà (OWNER) của Bên B ở trạng thái sẵn sàng điều khiển.
            4.2. Mã QR mặc định của các thiết bị đã bàn giao trực tiếp sẽ được hệ thống tự động vô hiệu hóa để bảo đảm an toàn sở hữu. Về sau, khi Bên B mua lẻ thêm thiết bị mở rộng, Bên B vẫn có thể tự thêm nhanh bằng tính năng Quét mã QR trên ứng dụng HESTA.

            ĐIỀU 5. CHÍNH SÁCH BẢO HÀNH VÀ HỖ TRỢ BẢO TRÌ
            5.1. Mỗi thiết bị được bảo hành chính hãng từ 12 đến 24 tháng kể từ ngày bàn giao (chi tiết theo từng dòng thiết bị trong Bảng kê).
            5.2. Trong thời gian sử dụng, Bên B có thể gửi yêu cầu kiểm tra, sửa chữa hoặc bảo dưỡng trực tiếp qua phân hệ Bảo trì trên HESTA Dashboard để được chẩn đoán và đặt lịch kỹ thuật viên.

            ĐIỀU 6. BẢO MẬT DỮ LIỆU VÀ QUYỀN RIÊNG TƯ
            6.1. Dữ liệu điều khiển, phân quyền thành viên theo phòng/thiết bị thuộc toàn quyền kiểm soát của Chủ nhà (Bên B). Bên A cam kết không chia sẻ dữ liệu sử dụng của Bên B cho bên thứ ba nếu không có sự chấp thuận bằng văn bản.
            6.2. Tài khoản quản trị và phiên điều khiển được bảo vệ bằng cơ chế xác thực JWT và kiểm tra quyền tại máy chủ.

            ĐIỀU 7. HIỆU LỰC HỢP ĐỒNG VÀ GIẢI QUYẾT TRANH CHẤP
            7.1. Hợp đồng điện tử này có giá trị xác nhận cam kết giữa hai bên kể từ thời điểm Bên B xác nhận đồng ý điều khoản và ký điện tử trên Cổng HESTA. Toàn bộ nội dung giá trị và điều khoản được khóa bất biến sau khi ký.
            7.2. Mọi phát sinh ngoài phạm vi hợp đồng sẽ được hai bên trao đổi minh bạch trên Nhật ký thương lượng và thống nhất bằng phụ lục hoặc phiên bản hợp đồng mới trước khi ký.
            """;

    private static final String SAFETY_EDGE_AI_TERMS = """
            ĐIỀU 1. PHẠM VI CUNG CẤP GIẢI PHÁP AN TOÀN & EDGE AI HESTA
            1.1. Bên A (HESTA) cung cấp trọn gói thiết bị nhà thông minh, bộ cảm biến an toàn (Gas, Khói, Chuyển động, Nhiệt độ), thiết bị Edge AI (Camera nhận diện té ngã & Trợ lý giọng nói tiếng Việt cục bộ) và triển khai lắp đặt theo đúng Bảng kê thiết bị của Hợp đồng này.
            1.2. Bên A cam kết hiệu chỉnh góc camera, kiểm thử ngưỡng cảnh báo an toàn và kịch bản còi hú/thông báo khẩn cấp trước khi bàn giao.

            ĐIỀU 2. GIÁ TRỊ HỢP ĐỒNG, ĐẶT CỌC VÀ THANH TOÁN
            2.1. Giá trị hợp đồng được tính minh bạch theo đơn giá niêm yết của từng mẫu thiết bị, phí thi công và ưu đãi chiết khấu (nếu có).
            2.2. Đợt 1 – Đặt cọc: Bên B chuyển khoản đặt cọc theo đúng tỷ lệ và thông tin tài khoản doanh nghiệp ghi tại Hợp đồng với đúng cú pháp chuyển khoản. Bên A xác nhận thực nhận trên tài khoản doanh nghiệp trước khi xuất kho thiết bị và lắp đặt.
            2.3. Đợt 2 – Quyết toán: Thanh toán phần còn lại sau khi kiểm thử thành công toàn bộ thiết bị, cảnh báo an toàn và hoàn tất bàn giao quyền Chủ nhà (OWNER).

            ĐIỀU 3. THỜI GIAN THI CÔNG VÀ KIỂM THỬ AN TOÀN
            3.1. Bên A thực hiện lắp đặt, hiệu chuẩn cảm biến và thiết lập quy trình cảnh báo té ngã (đếm ngược 30 giây xác nhận "Tôi ổn", kích hoạt còi & thông báo người thân) theo đúng lịch hẹn.
            3.2. Bên B phối hợp kiểm tra các kịch bản khẩn cấp và vùng giám sát an toàn cùng kỹ thuật viên trong buổi nghiệm thu.

            ĐIỀU 4. BÀN GIAO CHÌA KHÓA TRAO TAY (TURNKEY HANDOVER)
            4.1. Bên A cấu hình hoàn chỉnh Ngôi nhà, sơ đồ phòng, Digital Twin và gán trực tiếp toàn bộ thiết bị hợp đồng vào tài khoản Chủ nhà (OWNER) của Bên B mà không yêu cầu Bên B tự quét từng mã QR.
            4.2. Tính năng quét QR vẫn được duy trì độc lập để Bên B thuận tiện tự mua thêm và gắn mở rộng các thiết bị lẻ trong tương lai.

            ĐIỀU 5. CAM KẾT QUYỀN RIÊNG TƯ EDGE AI (ĐIỀU KHOẢN TRỌNG TÂM)
            5.1. Hệ thống nhận diện té ngã (YOLOv8 Pose) và xử lý giọng nói Edge AI được vận hành cục bộ tại thiết bị biên trong nhà của Bên B; luồng hình ảnh sinh hoạt riêng tư không bị gửi ra máy chủ đám mây công cộng.
            5.2. Bên A chỉ truyền sự kiện cảnh báo khi phát hiện tình huống khẩn cấp tới đúng các thành viên gia đình được Chủ nhà phân quyền.

            ĐIỀU 6. CHÍNH SÁCH BẢO HÀNH VÀ BẢO TRÌ ƯU TIÊN
            6.1. Thiết bị Camera AI, khóa/cửa thông minh được bảo hành 24 tháng; cảm biến và công tắc bảo hành 12 tháng kể từ ngày bàn giao.
            6.2. Các sự cố liên quan tới cảm biến an toàn và camera té ngã được ưu tiên tiếp nhận xử lý qua hệ thống Bảo trì HESTA.

            ĐIỀU 7. HIỆU LỰC VÀ TRÁCH NHIỆM PHÁP LÝ
            7.1. Hệ thống cảnh báo an toàn và té ngã là giải pháp hỗ trợ giám sát sớm nhằm giảm thiểu rủi ro, không thay thế hoàn toàn dịch vụ y tế khẩn cấp chuyên nghiệp.
            7.2. Hợp đồng điện tử có hiệu lực ngay khi Bên B ký xác nhận; toàn bộ đơn giá, điều khoản và thông tin ngân hàng được lưu bất biến trên hệ thống HESTA.
            """;

    private static final String EXPANSION_TERMS = """
            ĐIỀU 1. PHẠM VI NÂNG CẤP VÀ MỞ RỘNG THIẾT BỊ
            1.1. Bên A cung cấp và lắp đặt bổ sung các thiết bị mới theo Bảng kê thiết bị đính kèm, tích hợp trực tiếp vào Ngôi nhà HESTA hiện hữu của Bên B mà không làm gián đoạn các thiết bị và kịch bản tự động đang hoạt động.

            ĐIỀU 2. GIÁ TRỊ HỢP ĐỒNG VÀ ĐẶT CỌC
            2.1. Tổng chi phí bao gồm đơn giá thiết bị bổ sung và phí kỹ thuật tích hợp (nếu có).
            2.2. Bên B chuyển khoản đặt cọc theo thông tin tài khoản doanh nghiệp và cú pháp ghi trên Hợp đồng; Bên A đối soát xác nhận trước khi kỹ thuật viên tới lắp đặt.

            ĐIỀU 3. THỜI GIAN LẮP ĐẶT VÀ TÍCH HỢP
            3.1. Kỹ thuật viên Bên A thực hiện đấu nối, gán thiết bị mới vào đúng phòng và cập nhật lên giao diện điều khiển cũng như bản sao số (Digital Twin) của ngôi nhà.

            ĐIỀU 4. NGHIỆM THU VÀ BÀN GIAO
            4.1. Thiết bị sau khi lắp đặt được gán trực tiếp vào tài khoản ngôi nhà hiện có ở trạng thái ONLINE và kích hoạt thời hạn bảo hành điện tử riêng theo hợp đồng này.

            ĐIỀU 5. BẢO HÀNH VÀ BẢO TRÌ
            5.1. Thời hạn bảo hành áp dụng theo từng thiết bị trong Bảng kê, tính từ ngày bàn giao nghiệm thu.

            ĐIỀU 6. HIỆU LỰC HỢP ĐỒNG
            6.1. Hợp đồng có hiệu lực từ thời điểm Bên B ký điện tử xác nhận trên Cổng HESTA.
            """;

    public static final List<TemplateSpec> TEMPLATES = List.of(
            new TemplateSpec(
                    "STANDARD_HOME",
                    "Hợp đồng Tiêu chuẩn (Căn hộ / Nhà phố)",
                    "Đầy đủ 7 điều khoản tiêu chuẩn: bảng kê thiết bị, đặt cọc 30%, bàn giao chìa khóa trao tay, bảo hành & bảo mật.",
                    "HỢP ĐỒNG CUNG CẤP VÀ LẮP ĐẶT HỆ THỐNG NHÀ THÔNG MINH HESTA",
                    30,
                    new BigDecimal("1500000"),
                    STANDARD_TERMS
            ),
            new TemplateSpec(
                    "SAFETY_EDGE_AI",
                    "Hợp đồng Gói An toàn & Edge AI",
                    "Bổ sung điều khoản cam kết quyền riêng tư Camera Edge cục bộ, quy trình cảnh báo té ngã 30s và bảo hành 24 tháng.",
                    "HỢP ĐỒNG CUNG CẤP GIẢI PHÁP NHÀ THÔNG MINH AN TOÀN & EDGE AI HESTA",
                    30,
                    new BigDecimal("2000000"),
                    SAFETY_EDGE_AI_TERMS
            ),
            new TemplateSpec(
                    "EXPANSION_UPGRADE",
                    "Hợp đồng Mở rộng / Nâng cấp Thiết bị",
                    "Gọn nhẹ cho khách hàng đã có nhà HESTA muốn lắp đặt bổ sung thêm thiết bị, cảm biến hoặc camera.",
                    "HỢP ĐỒNG LẮP ĐẶT BỔ SUNG VÀ NÂNG CẤP THIẾT BỊ HESTA",
                    30,
                    new BigDecimal("500000"),
                    EXPANSION_TERMS
            )
    );

    public static final List<PackageSpec> PACKAGES = List.of(
            new PackageSpec(
                    "STARTER_HOME",
                    "Gói Khởi Đầu",
                    "Starter Smart Home",
                    "Phù hợp căn hộ 1–2 phòng ngủ muốn tự động hóa chiếu sáng, điều hòa và theo dõi điện năng.",
                    "Ideal for 1–2 bedroom apartments automating lighting, AC, and real-time energy tracking.",
                    "Phổ biến cho căn hộ",
                    "Popular for apartments",
                    "STANDARD_HOME",
                    new BigDecimal("4860000"),
                    new BigDecimal("800000"),
                    List.of(
                            "3 Công tắc cảm ứng + 2 Đèn LED RGB + 2 Ổ cắm thông minh",
                            "Bộ điều khiển điều hòa IR + 2 Cảm biến nhiệt ẩm + 1 Cảm biến PIR",
                            "Bản sao số 2D/3D, kịch bản hẹn giờ & tính tiền điện EVN",
                            "Bàn giao sẵn Nhà & Phòng lên tài khoản, bảo hành 12 tháng"
                    ),
                    List.of(
                            "3 Touch switches + 2 RGB lights + 2 smart plugs",
                            "IR AC controller + 2 climate sensors + 1 radar PIR sensor",
                            "2D/3D Digital Twin, smart schedules & EVN bill tracking",
                            "Turnkey home setup handover, 12-month warranty"
                    ),
                    List.of(
                            new PackageBomItem("HESTA-SW-01", "LIGHT", "Phòng khách", 3),
                            new PackageBomItem("HESTA-RGB-01", "LED_RGB", "Phòng khách", 2),
                            new PackageBomItem("HESTA-AC-01", "AC_REMOTE", "Phòng ngủ", 1),
                            new PackageBomItem("HESTA-TH-01", "TEMP_HUMID_SENSOR", "Phòng khách", 2),
                            new PackageBomItem("HESTA-PIR-01", "MOTION_SENSOR", "Hành lang", 1),
                            new PackageBomItem("HESTA-PLUG-01", "SMART_PLUG", "Nhà bếp", 2)
                    )
            ),
            new PackageSpec(
                    "SAFETY_EDGE_AI",
                    "Gói An Toàn & Edge AI",
                    "Safety & Edge AI Suite",
                    "Giải pháp toàn diện cho gia đình có người lớn tuổi hoặc trẻ nhỏ với Camera AI phát hiện té ngã, Motor cổng & bảo vệ 24/7.",
                    "Complete protection for multi-generational families with local Edge AI fall detection, smart gate & 24/7 hazard alerts.",
                    "Khuyên dùng",
                    "Recommended",
                    "SAFETY_EDGE_AI",
                    new BigDecimal("9550000"),
                    new BigDecimal("1500000"),
                    List.of(
                            "Camera Edge AI HESTA Vision Pro (nhận diện té ngã cục bộ, BH 24th)",
                            "Motor cổng tự động HESTA GateMaster + Điều khiển điều hòa IR",
                            "3 Công tắc cảm ứng + 2 Đèn LED RGB + 2 Cảm biến nhiệt ẩm + 2 Cảm biến PIR",
                            "Trợ lý giọng nói tiếng Việt, gợi ý AI theo thói quen & bàn giao tận nhà"
                    ),
                    List.of(
                            "HESTA Vision Pro local Edge AI fall-detection camera (24m warranty)",
                            "HESTA GateMaster smart gate motor + IR AC controller",
                            "3 Touch switches + 2 RGB lights + 2 climate & 2 PIR sensors",
                            "Vietnamese voice assistant, habit AI & turnkey home handover"
                    ),
                    List.of(
                            new PackageBomItem("HESTA-CAM-01", "CAMERA_AI", "Phòng khách", 1),
                            new PackageBomItem("HESTA-GATE-01", "GATE_MOTOR", "Cửa chính", 1),
                            new PackageBomItem("HESTA-SW-01", "LIGHT", "Phòng khách", 3),
                            new PackageBomItem("HESTA-RGB-01", "LED_RGB", "Phòng ngủ", 2),
                            new PackageBomItem("HESTA-AC-01", "AC_REMOTE", "Phòng ngủ", 1),
                            new PackageBomItem("HESTA-TH-01", "TEMP_HUMID_SENSOR", "Phòng khách", 2),
                            new PackageBomItem("HESTA-PIR-01", "MOTION_SENSOR", "Nhà bếp & Hành lang", 2)
                    )
            ),
            new PackageSpec(
                    "CUSTOM_HOME",
                    "Gói Thiết Kế Riêng",
                    "Custom Architecture",
                    "Lựa chọn chính xác từng mẫu thiết bị theo mặt bằng nhà phố, biệt thự hoặc nâng cấp nhà sẵn có.",
                    "Tailor exact device models per room for townhouses, villas, or expanding an existing HESTA home.",
                    "Linh hoạt theo nhu cầu",
                    "Tailored per floorplan",
                    "STANDARD_HOME",
                    new BigDecimal("0"),
                    new BigDecimal("1500000"),
                    List.of(
                            "Kỹ thuật viên khảo sát & bóc tách thiết bị từng phòng",
                            "Thương lượng trực tiếp đơn giá, chiết khấu & điều khoản trên Portal",
                            "Lắp đặt trọn gói bàn giao ngay hoặc tự mở rộng qua mã QR",
                            "Hỗ trợ bảo trì định kỳ & cập nhật firmware OTA từ xa"
                    ),
                    List.of(
                            "Engineer survey & room-by-room hardware bill of materials",
                            "Live pricing, discount & clause negotiation on the Portal",
                            "Turnkey handover plus QR self-pairing for future add-ons",
                            "Scheduled maintenance & remote OTA firmware updates"
                    ),
                    List.of()
            )
    );

    private ContractTemplates() {}

    public static TemplateSpec findTemplateOrDefault(String code) {
        if (code == null || code.isBlank()) {
            return TEMPLATES.get(0);
        }
        return TEMPLATES.stream()
                .filter(t -> t.code().equalsIgnoreCase(code.trim()))
                .findFirst()
                .orElse(TEMPLATES.get(0));
    }

    public static PackageSpec findPackage(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        return PACKAGES.stream()
                .filter(p -> p.code().equalsIgnoreCase(code.trim()))
                .findFirst()
                .orElse(null);
    }
}
