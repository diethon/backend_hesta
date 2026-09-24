package com.hesta.backend.dto.request;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LedCommandRequest {

    private String action;
    private Integer r;
    private Integer g;
    private Integer b;
    private Integer brightness;
}
