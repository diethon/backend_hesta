# MQTT Contract & Payload Definition

## 1. Topic Convention
- **Command (Backend -> Device):** `hesta/nodes/+/devices/+/command`
- **ACK (Device -> Backend):** `hesta/nodes/+/devices/+/ack`
- **State (Device -> Backend):** `hesta/nodes/+/devices/+/state`

## 2. Command Payload
```json
{
  "commandId": "uuid",
  "action": "TURN_ON | TURN_OFF",
  "parameters": {},
  "timestamp": 1700000000000
}
```

## 3. ACK Payload
```json
{
  "commandId": "uuid",
  "deviceId": "uuid",
  "status": "SUCCESS | FAILED",
  "errorCode": "null | ERROR_CODE",
  "timestamp": 1700000000000
}
```

## 4. State Payload
```json
{
  "deviceId": "uuid",
  "state": { "power": "ON" },
  "source": "APP | MANUAL",
  "timestamp": 1700000000000
}
```
