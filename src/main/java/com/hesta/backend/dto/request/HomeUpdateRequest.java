package com.hesta.backend.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class HomeUpdateRequest {

    @NotBlank(message = "Tên nhà không được để trống")
    @Size(min = 1, max = 100, message = "Tên nhà từ 1 đến 100 ký tự")
    private String name;
}
