package com.hesta.backend.service.impl;

import com.hesta.backend.dto.command.SensorReadingInput;
import com.hesta.backend.dto.command.SensorReadingUpdatedEvent;
import com.hesta.backend.dto.response.SensorReadingAcceptedResponse;
import com.hesta.backend.entity.Device;
import com.hesta.backend.entity.SensorReading;
import com.hesta.backend.exception.AppException;
import com.hesta.backend.exception.ErrorCode;
import com.hesta.backend.mapper.TwinSnapshotMapper;
import com.hesta.backend.repository.DeviceRepository;
import com.hesta.backend.repository.SensorReadingRepository;
import com.hesta.backend.service.HomeAuthorizationService;
import com.hesta.backend.service.SensorReadingIngestionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class SensorReadingIngestionServiceImpl implements SensorReadingIngestionService {
    private final DeviceRepository deviceRepository;
    private final SensorReadingRepository sensorReadingRepository;
    private final HomeAuthorizationService homeAuthorizationService;
    private final TwinSnapshotMapper mapper;
    private final ApplicationEventPublisher applicationEventPublisher;

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public SensorReadingAcceptedResponse ingest(UUID currentUserId, SensorReadingInput input) {
        try {
            validate(input);
            if (currentUserId == null) {
                throw new AppException(ErrorCode.UNAUTHENTICATED);
            }
            Device device = deviceRepository.findForSensorReading(input.deviceId())
                    .orElseThrow(() -> new AppException(ErrorCode.DEVICE_NOT_FOUND));
            if (device.isDeleted()) {
                throw new AppException(ErrorCode.DEVICE_NOT_FOUND);
            }
            if (device.getHome() == null) {
                throw new AppException(ErrorCode.HOME_NOT_FOUND);
            }
            UUID homeId = device.getHome().getId();
            homeAuthorizationService.requireAccess(currentUserId, homeId);
            SensorReading saved = sensorReadingRepository.save(SensorReading.builder()
                    .device(device).metricType(input.metricType()).value(input.value())
                    .unit(input.unit()).recordedAt(input.observedAt()).build());
            boolean latest = sensorReadingRepository
                    .findFirstByDeviceIdAndMetricTypeOrderByRecordedAtDescIdDesc(device.getId(), input.metricType())
                    .map(reading -> reading.getId().equals(saved.getId())).orElse(false);
            var payload = mapper.sensor(saved);
            if (latest) {
                applicationEventPublisher.publishEvent(new SensorReadingUpdatedEvent(homeId, payload));
            }
            return new SensorReadingAcceptedResponse(saved.getId(), latest, payload);
        } catch (AppException exception) {
            log.warn("Rejected sensor input: deviceId={}, metricType={}, observedAt={}, reason={}",
                    input == null ? null : input.deviceId(), input == null ? null : safeMetric(input.metricType()),
                    input == null ? null : input.observedAt(), exception.getErrorCode().name());
            throw exception;
        }
    }

    private void validate(SensorReadingInput input) {
        if (input == null) {
            throw new AppException(ErrorCode.INVALID_REQUEST);
        }
        if (input.deviceId() == null) {
            throw new AppException(ErrorCode.SENSOR_DEVICE_REQUIRED);
        }
        if (input.metricType() == null || input.metricType().isBlank()
                || input.metricType().length() > 50 || input.metricType().indexOf('\0') >= 0) {
            throw new AppException(ErrorCode.SENSOR_METRIC_INVALID);
        }
        if (input.value() == null || input.value().abs().compareTo(new BigDecimal("10000000")) >= 0) {
            throw new AppException(ErrorCode.SENSOR_VALUE_INVALID);
        }
        try {
            input.value().setScale(3, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw new AppException(ErrorCode.SENSOR_VALUE_INVALID);
        }
        if (input.unit() != null && (input.unit().length() > 20 || input.unit().indexOf('\0') >= 0)) {
            throw new AppException(ErrorCode.SENSOR_UNIT_INVALID);
        }
        if (input.observedAt() == null || input.observedAt().getYear() < 1 || input.observedAt().getYear() > 9999
                || input.observedAt().getNano() % 1000 != 0) {
            throw new AppException(ErrorCode.SENSOR_TIME_INVALID);
        }
    }

    private String safeMetric(String metric) {
        return metric == null ? null : metric.substring(0, Math.min(metric.length(), 50)).replaceAll("\\p{Cntrl}", "?");
    }
}
