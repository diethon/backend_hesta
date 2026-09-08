package com.hesta.backend.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {
    
    /**
     * Mã trạng thái của API. 
     * Ví dụ: 1000 là thành công, các mã khác là lỗi.
     */
    @Builder.Default
    int code = 1000;
    
    /**
     * Thông báo cho người dùng hoặc thông báo lỗi.
     */
    String message;
    
    /**
     * Dữ liệu chính trả về cho client.
     */
    T result;
}
