package com.hesta.backend.service;

import com.hesta.backend.dto.request.TwinArchitectureRequest;
import com.hesta.backend.dto.request.TwinRoomLayoutRequest;
import com.hesta.backend.exception.AppException;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class TwinArchitectureValidationTest {
    private final UUID room = UUID.randomUUID();
    private final List<TwinRoomLayoutRequest> layouts = List.of(new TwinRoomLayoutRequest(room,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ONE));
    private final TwinArchitectureRequest.RoomGeometry geometry = new TwinArchitectureRequest.RoomGeometry(
            TwinArchitectureRequest.Shape.RECTANGLE, List.of(new TwinArchitectureRequest.Point(0,0),
            new TwinArchitectureRequest.Point(1,0),new TwinArchitectureRequest.Point(1,1),new TwinArchitectureRequest.Point(0,1)),
            null,null,2.5,.15,List.of(),false);
    @Test void acceptsVersionedGeometryAndOptionalLegacyDocument() {
        assertThatCode(()->TwinArchitectureValidation.validate(new TwinArchitectureRequest(1,Map.of(room,geometry),Map.of(),Map.of()),layouts,List.of())).doesNotThrowAnyException();
        assertThatCode(()->TwinArchitectureValidation.validate(null,layouts,List.of())).doesNotThrowAnyException();
    }
    @Test void rejectsForeignRoomAndUnknownVersion() {
        assertThatThrownBy(()->TwinArchitectureValidation.validate(new TwinArchitectureRequest(1,Map.of(UUID.randomUUID(),geometry),null,null),layouts,List.of())).isInstanceOf(AppException.class);
        assertThatThrownBy(()->TwinArchitectureValidation.validate(new TwinArchitectureRequest(2,Map.of(room,geometry),null,null),layouts,List.of())).isInstanceOf(AppException.class);
    }
    @Test void rejectsUnplacedDeviceRotationAndDegeneratePolygon() {
        assertThatThrownBy(()->TwinArchitectureValidation.validate(new TwinArchitectureRequest(1,Map.of(room,geometry),Map.of("DEVICE:foreign",Double.NaN),null),layouts,List.of())).isInstanceOf(AppException.class);
        var flat=new TwinArchitectureRequest.RoomGeometry(TwinArchitectureRequest.Shape.CUSTOM,List.of(new TwinArchitectureRequest.Point(0,0),new TwinArchitectureRequest.Point(.5,0),new TwinArchitectureRequest.Point(1,0)),null,null,null,null,null,false);
        assertThatThrownBy(()->TwinArchitectureValidation.validate(new TwinArchitectureRequest(1,Map.of(room,flat),null,null),layouts,List.of())).isInstanceOf(AppException.class);
    }
}
