package com.hesta.backend.dto.request;

import jakarta.validation.constraints.Size;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ResolveAnomalyRequest {

    private boolean falsePositive;

    @Size(max = 1000, message = "Resolution note must not exceed 1000 characters")
    private String note;
}
