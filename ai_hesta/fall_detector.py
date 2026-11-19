#!/usr/bin/env python3
"""
HESTA Smart Home - Unified Edge AI Fall Detection Engine
========================================================
Single canonical fall detection engine combining YOLOv8-Pose kinematics,
per-subject isolated state machines, anti-phantom validation gates,
camera-angle-invariant floor-lying checks, and motion-blur trajectory bridging.
"""

import sys
import os
import time
import json
import math
import uuid
import argparse
from typing import Dict, List, Tuple, Optional, Any, Set
from collections import deque

import cv2
import numpy as np

try:
    from pose_detector import PoseDetector
except ImportError:
    from ai_hesta.pose_detector import PoseDetector

try:
    import config
    from rtsp_stream_manager import RTSPStreamManager
except ImportError:
    import ai_hesta.config as config
    from ai_hesta.rtsp_stream_manager import RTSPStreamManager

try:
    import paho.mqtt.client as mqtt
    MQTT_AVAILABLE = True
except ImportError:
    MQTT_AVAILABLE = False


# ==============================================================================
# HESTA EVENT CONTRACT DEFINITIONS
# ==============================================================================
STATE_IDLE = "IDLE"
STATE_STANDING = "STANDING"
STATE_SITTING = "SITTING"
STATE_BENDING = "BENDING"
STATE_CONTROLLED_REST = "CONTROLLED_REST"
STATE_LYING = "LYING"
STATE_FALLING = "FALLING"
STATE_IMPACT = "IMPACT"
STATE_UNRESPONSIVE = "UNRESPONSIVE"
STATE_CONFIRMED_FALL = "CONFIRMED_FALL"
STATE_RECOVERED = "RECOVERED"

MAX_KEYPOINT_HOLD_FRAMES = 5
FALLEN_OCCLUSION_RETENTION_SEC = 15.0
UNCONFIRMED_OCCLUSION_RETENTION_SEC = 6.0

STATE_SEVERITY = {
    STATE_IDLE: 0,
    STATE_STANDING: 1,
    STATE_SITTING: 2,
    STATE_CONTROLLED_REST: 3,
    STATE_LYING: 3,
    STATE_BENDING: 4,
    STATE_IMPACT: 5,
    STATE_UNRESPONSIVE: 5,
    STATE_CONFIRMED_FALL: 6,
}


def _iou(box_a: Optional[List[int]], box_b: Optional[List[int]]) -> float:
    if box_a is None or box_b is None:
        return 0.0
    x1 = max(box_a[0], box_b[0])
    y1 = max(box_a[1], box_b[1])
    x2 = min(box_a[2], box_b[2])
    y2 = min(box_a[3], box_b[3])
    if x2 <= x1 or y2 <= y1:
        return 0.0
    inter = float((x2 - x1) * (y2 - y1))
    area_a = float(max(1, (box_a[2] - box_a[0]) * (box_a[3] - box_a[1])))
    area_b = float(max(1, (box_b[2] - box_b[0]) * (box_b[3] - box_b[1])))
    return inter / (area_a + area_b - inter + 1e-6)


class CentroidTracker:
    """
    Spatial tracker matching subjects across frames by Center-of-Mass distance.
    Supports sticky reservation so walking bystanders do not hijack occluded fallen tracks.
    """
    def __init__(self, max_disappeared: int = 45, max_distance: float = 180.0, max_disappeared_fallen: int = 360):
        self.next_id = 0
        self.objects: Dict[int, Tuple[float, float]] = {}
        self.disappeared: Dict[int, int] = {}
        self.max_disappeared = max_disappeared
        self.max_disappeared_fallen = max_disappeared_fallen
        self.max_distance = max_distance

    def _alloc_id(self) -> int:
        tid = self.next_id
        self.next_id += 1
        return tid

    def _limit_for(self, tid: int, reserved_ids: Optional[Set[int]]) -> int:
        if reserved_ids and tid in reserved_ids:
            return self.max_disappeared_fallen
        return self.max_disappeared

    def deregister(self, tid: int):
        self.objects.pop(tid, None)
        self.disappeared.pop(tid, None)

    def update(
        self,
        centroids: List[Tuple[float, float]],
        baseline_map: Optional[Dict[int, float]] = None,
        reserved_ids: Optional[Set[int]] = None,
        candidate_upright_flags: Optional[List[bool]] = None,
        candidate_boxes: Optional[List[Optional[List[int]]]] = None,
        last_bboxes: Optional[Dict[int, List[int]]] = None,
    ) -> List[int]:
        for tid in list(self.disappeared.keys()):
            if self.disappeared[tid] > self._limit_for(tid, reserved_ids):
                self.deregister(tid)

        if not centroids:
            for tid in list(self.objects.keys()):
                self.disappeared[tid] = self.disappeared.get(tid, 0) + 1
                if self.disappeared[tid] > self._limit_for(tid, reserved_ids):
                    self.deregister(tid)
            return []

        if not self.objects:
            assigned = []
            for c in centroids:
                tid = self._alloc_id()
                self.objects[tid] = c
                self.disappeared[tid] = 0
                assigned.append(tid)
            return assigned

        tids = list(self.objects.keys())
        prev_coords = list(self.objects.values())

        D = np.zeros((len(tids), len(centroids)), dtype=float)
        for i, (px, py) in enumerate(prev_coords):
            for j, (cx, cy) in enumerate(centroids):
                dist = math.hypot(px - cx, py - cy)
                # Prefer re-attaching a horizontal detection that strongly overlaps an occluded fallen bbox
                if reserved_ids and candidate_boxes and last_bboxes and tids[i] in reserved_ids:
                    if not (candidate_upright_flags and candidate_upright_flags[j]):
                        if _iou(candidate_boxes[j], last_bboxes.get(tids[i])) >= 0.30:
                            dist = min(dist, 40.0)
                D[i, j] = dist

        # Prevent disappeared fallen tracks from being stolen by walking upright bystanders,
        # while still allowing the fallen person themselves to stand back up at their own footprint.
        if reserved_ids and candidate_upright_flags:
            for i, tid in enumerate(tids):
                miss_cnt = self.disappeared.get(tid, 0)
                if tid in reserved_ids and miss_cnt > 2:
                    for j, is_up in enumerate(candidate_upright_flags):
                        if not is_up:
                            continue
                        same_spot = (
                            miss_cnt <= 15
                            and candidate_boxes is not None
                            and last_bboxes is not None
                            and _iou(candidate_boxes[j], last_bboxes.get(tid)) >= 0.25
                        )
                        if not same_spot:
                            D[i, j] = 1e9

        rows = D.min(axis=1).argsort()
        cols = D.argmin(axis=1)[rows]

        used_rows = set()
        used_cols = set()
        assigned_ids: List[Optional[int]] = [None] * len(centroids)

        for row, col in zip(rows, cols):
            if row in used_rows or col in used_cols:
                continue
            tid = tids[row]
            h_base = baseline_map.get(tid, 0.0) if baseline_map else 0.0
            max_d = max(self.max_distance, 0.85 * h_base)
            if D[row, col] > max_d:
                continue
            self.objects[tid] = centroids[col]
            self.disappeared[tid] = 0
            assigned_ids[col] = tid
            used_rows.add(row)
            used_cols.add(col)

        for j in range(len(centroids)):
            if assigned_ids[j] is None:
                tid = self._alloc_id()
                self.objects[tid] = centroids[j]
                self.disappeared[tid] = 0
                assigned_ids[j] = tid

        for i, tid in enumerate(tids):
            if i not in used_rows:
                self.disappeared[tid] = self.disappeared.get(tid, 0) + 1
                if self.disappeared[tid] > self._limit_for(tid, reserved_ids):
                    self.deregister(tid)

        return assigned_ids  # type: ignore[return-value]


class KinematicTracker:
    """
    Per-subject Kinematic Trajectory Tracker and Isolated Fall State Machine.
    """
    def __init__(self, history_len: int = 45, now_ts: Optional[float] = None):
        ts = time.time() if now_ts is None else float(now_ts)
        self.history = deque(maxlen=history_len)  # (ts, com_x, com_y_filt, height_px, spine_angle_filt, is_periodic)
        self.last_standing_ts = ts
        self.posture_transition_start: Optional[float] = None
        self.standing_height_samples = deque(maxlen=30)
        self.standing_com_ys = deque(maxlen=30)
        self.accel_history = deque(maxlen=4)

        self.ema_alpha = 0.28
        self.com_y_filtered: Optional[float] = None
        self.spine_angle_filtered: Optional[float] = None
        self.last_update_ts = ts

        # Keypoint occlusion hold
        self.max_hold_frames = MAX_KEYPOINT_HOLD_FRAMES
        self.last_valid_com: Optional[Tuple[float, float]] = None
        self.last_valid_sh: Optional[Tuple[float, float]] = None
        self.missing_hip_frames = 0
        self.missing_sh_frames = 0
        self.frame_reliable = False

        # Morphology & trajectory buffers
        self.aspect_ratio = 0.5
        self.box_aspect_ratio = 0.5
        self.up_frames = 0
        self.ever_upright = False
        self.stable_upright_frames = 0
        self.upright_anchor_ts: Optional[float] = None
        self.com_hist: deque = deque(maxlen=60)  # (ts, com_y_filt, v_norm, height_px, spine_angle)

        # Anti-phantom human validation gate
        self.ever_valid_human = False
        self.good_pose_frames = 0
        self.init_center: Optional[Tuple[float, float]] = None
        self.max_displacement_px = 0.0

        # Isolated per-person state machine
        self.state = STATE_STANDING
        self.impact_ts: Optional[float] = None
        self.impact_com_y: Optional[float] = None
        self.impact_from_squat = False
        self.last_bending_ts: Optional[float] = None
        self.last_fall_trigger_ts: Optional[float] = None
        self.upright_recovery_frames = 0
        self.fall_confirmed = False
        self.incident_type = "NONE"
        self.last_alert_ts = 0.0
        self.bending_start_ts: Optional[float] = None
        self.last_seen_upright = False
        self.last_bbox: Optional[List[int]] = None
        self.last_metrics: Dict[str, Any] = {
            "v_norm": 0.0,
            "accel_g": 1.0,
            "jerk_g_per_sec": 0.0,
            "spine_angle": 90.0,
            "ptt_sec": 0.0,
            "has_freefall": False,
            "has_impact": False,
            "motion_energy": 0.0,
            "aspect_ratio": 0.5,
        }

    def update_validation_gate(self, cand: Dict[str, Any]):
        """Promotes genuine human tracks while rejecting static low-confidence background hallucinations."""
        cx = float(cand.get("match_x", 0.0))
        cy = float(cand.get("match_y", 0.0))
        if self.init_center is None:
            self.init_center = (cx, cy)
        else:
            disp = math.hypot(cx - self.init_center[0], cy - self.init_center[1])
            if disp > self.max_displacement_px:
                self.max_displacement_px = disp

        if self.ever_valid_human:
            return

        mean_kc = float(cand.get("mean_kconf", 1.0))
        valid_k = int(cand.get("valid_kpts", 17))
        hip_ok = bool(cand.get("hip_valid", False))
        sh_ok = bool(cand.get("shoulder_valid", False))

        if mean_kc >= 0.55 and valid_k >= 12 and hip_ok and sh_ok:
            self.ever_valid_human = True
            return

        if mean_kc >= 0.45 and valid_k >= 10 and hip_ok and sh_ok:
            self.good_pose_frames += 1
            if self.good_pose_frames >= 2:
                self.ever_valid_human = True

    def resolve_and_update(
        self,
        cand: Dict[str, Any],
        is_periodic: bool = False,
        now_ts: Optional[float] = None,
    ) -> Dict[str, Any]:
        ts = time.time() if now_ts is None else float(now_ts)
        hip_valid = cand.get("hip_valid", False)
        sh_valid = cand.get("shoulder_valid", False)

        if hip_valid and cand["com_x"] is not None and cand["com_y"] is not None:
            self.last_valid_com = (float(cand["com_x"]), float(cand["com_y"]))
            self.missing_hip_frames = 0
            com_x, com_y = self.last_valid_com
            hip_ok = True
        else:
            self.missing_hip_frames += 1
            if self.last_valid_com is not None and self.missing_hip_frames <= self.max_hold_frames:
                com_x, com_y = self.last_valid_com
                hip_ok = True
            else:
                com_x, com_y = self.last_valid_com if self.last_valid_com is not None else (cand["match_x"], cand["match_y"])
                hip_ok = False

        if sh_valid and cand["sh_x"] is not None and cand["sh_y"] is not None:
            self.last_valid_sh = (float(cand["sh_x"]), float(cand["sh_y"]))
            self.missing_sh_frames = 0
            sh_x, sh_y = self.last_valid_sh
            sh_ok = True
        else:
            self.missing_sh_frames += 1
            if self.last_valid_sh is not None and self.missing_sh_frames <= self.max_hold_frames:
                sh_x, sh_y = self.last_valid_sh
                sh_ok = True
            else:
                sh_x, sh_y = self.last_valid_sh if self.last_valid_sh is not None else (com_x, com_y)
                sh_ok = False

        self.frame_reliable = bool(hip_ok and sh_ok)
        if self.frame_reliable:
            dx = sh_x - com_x
            dy = com_y - sh_y
            spine_angle = float(math.degrees(math.atan2(dy, abs(dx) + 1e-5))) if dy > 0 else 0.0
            if hip_valid:
                self.update(com_x, com_y, cand["height_px"], spine_angle, is_periodic, now_ts=ts)
            else:
                self.last_update_ts = ts
                if self.spine_angle_filtered is None:
                    self.spine_angle_filtered = spine_angle
                else:
                    angle_diff = abs(spine_angle - self.spine_angle_filtered)
                    angle_alpha = 0.85 if angle_diff > 18.0 else 0.4
                    self.spine_angle_filtered = angle_alpha * spine_angle + (1.0 - angle_alpha) * self.spine_angle_filtered
        else:
            self.last_update_ts = ts
            spine_angle = float(self.spine_angle_filtered if self.spine_angle_filtered is not None else 90.0)

        resolved = dict(cand)
        resolved["com_x"] = float(com_x)
        resolved["com_y"] = float(com_y)
        resolved["sh_x"] = float(sh_x)
        resolved["sh_y"] = float(sh_y)
        resolved["spine_angle"] = float(self.spine_angle_filtered if self.spine_angle_filtered is not None else spine_angle)
        resolved["frame_reliable"] = self.frame_reliable
        return resolved

    def update(
        self,
        com_x: float,
        com_y: float,
        height_px: float,
        spine_angle: float,
        is_periodic: bool = False,
        now_ts: Optional[float] = None,
    ):
        now = time.time() if now_ts is None else float(now_ts)
        self.last_update_ts = now
        self.frame_reliable = True
        self.missing_hip_frames = 0

        if self.com_y_filtered is None:
            self.com_y_filtered = com_y
            self.spine_angle_filtered = spine_angle
        else:
            h_ref = self.get_baseline_height(height_px)
            plunge_threshold = max(15.0, 0.08 * h_ref)
            y_diff = abs(com_y - self.com_y_filtered)
            com_alpha = 0.75 if y_diff > plunge_threshold else self.ema_alpha
            self.com_y_filtered = com_alpha * com_y + (1.0 - com_alpha) * self.com_y_filtered

            angle_diff = abs(spine_angle - self.spine_angle_filtered)
            angle_alpha = 0.85 if angle_diff > 18.0 else 0.4
            self.spine_angle_filtered = angle_alpha * spine_angle + (1.0 - angle_alpha) * self.spine_angle_filtered

        if spine_angle > 70.0 and height_px > 60.0 and self.aspect_ratio < 0.85 and len(self.history) >= 5:
            recent_ys = [p[2] for p in list(self.history)[-5:]]
            if np.std(recent_ys) < 4.0:
                self.standing_height_samples.append(height_px)
                self.standing_com_ys.append(com_y)
        elif len(self.standing_height_samples) == 0 and spine_angle > 65.0 and height_px > 40.0 and self.aspect_ratio < 0.85:
            self.standing_height_samples.append(height_px)

        self.history.append((now, com_x, self.com_y_filtered, height_px, self.spine_angle_filtered, is_periodic))

    def get_baseline_height(self, current_h: float = 120.0) -> float:
        if len(self.standing_height_samples) >= 3:
            return float(np.median(self.standing_height_samples))
        return max(current_h, 50.0)

    def compute_metrics(self, now_ts: Optional[float] = None) -> Dict[str, Any]:
        now = time.time() if now_ts is None else float(now_ts)
        if len(self.history) < 3:
            return {
                "v_norm": 0.0,
                "accel_g": 1.0,
                "jerk_g_per_sec": 0.0,
                "spine_angle": round(float(self.spine_angle_filtered), 1) if self.spine_angle_filtered is not None else 90.0,
                "ptt_sec": 0.0,
                "has_freefall": False,
                "has_impact": False,
                "motion_energy": 1.0,
                "aspect_ratio": self.aspect_ratio,
            }

        t2, x2, y2, h2, a2, _ = self.history[-1]
        t1, x1, y1, h1, a1, _ = self.history[-2]
        t0, x0, y0, h0, a0, _ = self.history[-3]

        h_ref = self.get_baseline_height(h2)

        motion_energy = 0.0
        if len(self.history) >= 6:
            recent_xs = [pt[1] for pt in list(self.history)[-6:]]
            recent_ys = [pt[2] for pt in list(self.history)[-6:]]
            std_x = float(np.std(recent_xs))
            std_y = float(np.std(recent_ys))
            motion_energy = float(math.hypot(std_x, std_y) / h_ref)

        curr_angle = self.spine_angle_filtered if self.spine_angle_filtered is not None else a2
        if curr_angle > 65.0 and self.aspect_ratio < 1.1:
            self.last_standing_ts = now
            self.posture_transition_start = None
            ptt_sec = 0.0
        else:
            if self.posture_transition_start is None:
                self.posture_transition_start = self.last_standing_ts
            ptt_sec = min(max(0.0, now - self.posture_transition_start), 60.0)

        if not self.frame_reliable or self.missing_hip_frames > 0:
            return {
                "v_norm": 0.0,
                "accel_g": 0.0,
                "jerk_g_per_sec": 0.0,
                "spine_angle": round(float(curr_angle), 1),
                "ptt_sec": round(float(ptt_sec), 2),
                "has_freefall": False,
                "has_impact": False,
                "motion_energy": round(float(motion_energy), 3),
                "aspect_ratio": self.aspect_ratio,
            }

        raw_dt1 = max(t2 - t1, 0.001)
        raw_dt0 = max(t1 - t0, 0.001)
        if raw_dt1 > 0.25:
            return {
                "v_norm": 0.0,
                "accel_g": 0.0,
                "jerk_g_per_sec": 0.0,
                "spine_angle": round(float(curr_angle), 1),
                "ptt_sec": round(float(ptt_sec), 2),
                "has_freefall": False,
                "has_impact": False,
                "motion_energy": round(float(motion_energy), 3),
                "aspect_ratio": self.aspect_ratio,
            }
        dt1 = min(raw_dt1, 0.25)
        dt0 = min(raw_dt0, 0.25)

        dy1 = (y2 - y1) if abs(y2 - y1) >= 1.5 else 0.0
        dy0 = (y1 - y0) if abs(y1 - y0) >= 1.5 else 0.0

        v1 = (dy1 / h_ref) / dt1
        v0 = (dy0 / h_ref) / dt0

        scale_to_m = 1.7 / h_ref
        a1_m_s2 = ((dy1 / dt1) - (dy0 / dt0)) / ((dt1 + dt0) / 2.0) * scale_to_m
        accel_g = abs(a1_m_s2) / 9.81

        if len(self.history) >= 4:
            t_prev, _, y_prev, _, _, _ = self.history[-4]
            dt_prev = min(max(t0 - t_prev, 0.001), 0.25)
            dy_prev = (y0 - y_prev) if abs(y0 - y_prev) >= 1.5 else 0.0
            a0_m_s2 = ((dy0 / dt0) - (dy_prev / dt_prev)) / ((dt0 + dt_prev) / 2.0) * scale_to_m
            prev_accel_g = abs(a0_m_s2) / 9.81
            jerk_g = abs(accel_g - prev_accel_g) / dt1
        else:
            jerk_g = accel_g / dt1

        has_freefall = False
        recent_slice = list(self.history)[-8:]
        for i in range(1, len(recent_slice)):
            t_curr, _, y_curr, _, a_curr, _ = recent_slice[i]
            t_prev, _, y_prev, _, _, _ = recent_slice[i - 1]
            if (t_curr - t_prev) > 0.25:
                continue
            dt = max(min(t_curr - t_prev, 0.25), 0.001)
            v_curr = ((y_curr - y_prev) / h_ref) / dt
            if v_curr > 1.4 and a_curr < 60.0:
                has_freefall = True
                break

        has_impact = (accel_g >= 2.4 and jerk_g >= 25.0 and v0 > 1.1 and a2 <= 40.0) or (v0 > 1.4 and v1 < 0.25 and a2 <= 40.0)

        return {
            "v_norm": round(float(v1), 2),
            "accel_g": round(float(accel_g), 2),
            "jerk_g_per_sec": round(float(jerk_g), 1),
            "spine_angle": round(float(curr_angle), 1),
            "ptt_sec": round(float(ptt_sec), 2),
            "has_freefall": has_freefall,
            "has_impact": has_impact,
            "motion_energy": round(float(motion_energy), 3),
            "aspect_ratio": self.aspect_ratio,
        }


class FallDetectorEngine:
    """
    Unified HESTA Edge AI Fall Detection Engine.
    Maintains isolated per-person state machines inside each KinematicTracker
    while preserving engine-level aggregate state, MQTT alerts, and API compatibility.
    """
    def __init__(
        self,
        model_path: str = config.YOLO_MODEL_PATH,
        mqtt_host: str = config.MQTT_HOST,
        mqtt_port: int = config.MQTT_PORT,
        device_id: str = config.DEVICE_ID,
        enable_mqtt: bool = True,
    ):
        self.device_id = device_id
        self.centroid_tracker = CentroidTracker()
        self.trackers: Dict[int, KinematicTracker] = {}
        self.bending_start_map: Dict[int, float] = {}

        print(f"[HESTA Engine] Initializing PoseDetector from {model_path}...")
        self.model = PoseDetector(model_path=model_path, conf=config.CONFIDENCE_THRESHOLD)

        # Aggregate state (synced from per-person trackers)
        self.current_state = STATE_IDLE
        self.state_enter_ts = time.time()
        self.impact_ts: Optional[float] = None
        self.upright_recovery_frames = 0
        self.last_fall_trigger_ts: Optional[float] = None
        self.fall_confirmed = False
        self.last_alert_ts = 0.0
        self.last_health_ts = 0.0
        self._last_process_ts: Optional[float] = None

        self.wrist_y_history = deque(maxlen=30)
        self.is_workout_active = False

        self.enable_mqtt = enable_mqtt and MQTT_AVAILABLE
        self.mqtt_client = None
        self.mqtt_host = mqtt_host
        self.mqtt_port = mqtt_port
        self._last_mqtt_reconnect_ts = 0.0
        if self.enable_mqtt:
            self._init_mqtt(mqtt_host, mqtt_port)

    @property
    def tracker(self) -> KinematicTracker:
        """Backwards compatibility accessor for default/primary tracker."""
        if 0 not in self.trackers:
            self.trackers[0] = KinematicTracker()
        return self.trackers[0]

    def reset(self, now_ts: Optional[float] = None):
        ts = time.time() if now_ts is None else float(now_ts)
        self.centroid_tracker = CentroidTracker()
        self.trackers.clear()
        self.bending_start_map.clear()
        self.current_state = STATE_IDLE
        self.state_enter_ts = ts
        self.impact_ts = None
        self.upright_recovery_frames = 0
        self.last_fall_trigger_ts = None
        self.fall_confirmed = False
        self.last_alert_ts = 0.0
        self._last_process_ts = None

    def dismiss(self):
        """Clears active fall alerts across all trackers (e.g., user pressed 'I am OK')."""
        for trk in self.trackers.values():
            trk.fall_confirmed = False
            trk.impact_ts = None
            trk.impact_com_y = None
            trk.last_fall_trigger_ts = None
            trk.upright_recovery_frames = 0
            trk.bending_start_ts = None
            if trk.state in (STATE_CONFIRMED_FALL, STATE_UNRESPONSIVE, STATE_IMPACT):
                trk.state = STATE_STANDING
        self.bending_start_map.clear()
        self.current_state = STATE_STANDING
        self.fall_confirmed = False
        self.impact_ts = None
        self.last_fall_trigger_ts = None
        self.upright_recovery_frames = 0

    def _init_mqtt(self, host: str, port: int):
        self.mqtt_host = host
        self.mqtt_port = port
        self._connect_mqtt()

    def _connect_mqtt(self) -> bool:
        if not MQTT_AVAILABLE or not self.enable_mqtt:
            return False
        try:
            client = mqtt.Client(client_id=f"hesta_edge_ai_{self.device_id}")
            status_topic = f"hesta/nodes/{self.device_id}/status"
            client.will_set(status_topic, "OFFLINE", qos=1, retain=True)
            client.max_queued_messages_set(100)

            def _on_connect(c, userdata, flags, rc):
                if rc == 0:
                    c.publish(status_topic, "ONLINE", qos=1, retain=True)
                    print(f"[MQTT] Connected successfully to {self.mqtt_host}:{self.mqtt_port} (LWT: {status_topic})")
                else:
                    print(f"[MQTT] Connection returned code {rc}")

            client.on_connect = _on_connect
            client.connect_async(self.mqtt_host, self.mqtt_port, 60)
            client.loop_start()
            self.mqtt_client = client
            print(f"[MQTT] Started loop to {self.mqtt_host}:{self.mqtt_port} with LWT on {status_topic}")
            return True
        except Exception as e:
            print(f"[MQTT] Warning: Could not connect to MQTT broker ({e}). Will auto-reconnect in background.")
            self.mqtt_client = None
            return False

    def _ensure_mqtt(self, now_ts: Optional[float] = None):
        if not self.enable_mqtt or not MQTT_AVAILABLE:
            return
        if self.mqtt_client is None:
            now = time.time() if now_ts is None else float(now_ts)
            if now - self._last_mqtt_reconnect_ts >= 10.0:
                self._last_mqtt_reconnect_ts = now
                self._connect_mqtt()

    def publish_event(
        self,
        event_type: str,
        incident_class: str,
        confidence: float,
        metrics: Dict,
        zone_name: Optional[str] = None,
        now_ts: Optional[float] = None,
    ):
        ts = time.time() if now_ts is None else float(now_ts)
        self._ensure_mqtt(now_ts=ts)
        unique_event_id = f"evt-fall-{uuid.uuid4().hex[:12]}"
        payload = {
            "eventId": unique_event_id,
            "deviceId": self.device_id,
            "zoneType": "CAMERA_VISION",
            "eventType": event_type,
            "incidentClassification": incident_class,
            "confidence": round(confidence, 2),
            "metrics": metrics,
            "auxiliaryContext": {
                "inRestZone": False,
                "zoneName": zone_name if zone_name else "Toàn Cảnh Camera (Omnipresent)",
                "isWorkout": self.is_workout_active,
            },
            "timestamp": int(ts * 1000),
        }
        json_str = json.dumps(payload, ensure_ascii=False)
        print(f"\n[ALERT DISPATCH] >>> MQTT Event: {event_type} ({incident_class}) | ID: {unique_event_id} | Conf: {confidence:.2f}")

        if self.enable_mqtt and self.mqtt_client:
            topic = f"hesta/sensor/fall-detection/{self.device_id}/event"
            self.mqtt_client.publish(topic, json_str, qos=1)
            if event_type == "FALL_DETECTED":
                try:
                    siren_payload = json.dumps({
                        "command": "START",
                        "mode": "BEEP_INTERMITTENT",
                        "durationSec": 30,
                        "eventId": unique_event_id,
                    })
                    self.mqtt_client.publish(config.MQTT_TOPIC_SIREN_CMD, siren_payload, qos=1)
                except Exception as e:
                    print(f"[MQTT] Warning: Could not publish siren command: {e}")

    def publish_camera_health(self, health_info: Dict[str, Any]):
        self._ensure_mqtt()
        if self.enable_mqtt and self.mqtt_client:
            topic = f"hesta/sensor/camera/{self.device_id}/health"
            try:
                self.mqtt_client.publish(topic, json.dumps(health_info), qos=0)
            except Exception:
                pass

    def extract_keypoints(self, keypoints_arr, box_xyxy: Optional[np.ndarray] = None, box_conf: float = 1.0) -> Dict[str, Any]:
        kpts = keypoints_arr.xy[0].cpu().numpy()
        confs = keypoints_arr.conf[0].cpu().numpy() if keypoints_arr.conf is not None else np.ones(17)

        hip_valid = bool(confs[11] > 0.35 or confs[12] > 0.35)
        if confs[11] > 0.35 and confs[12] > 0.35:
            com_x = float((kpts[11][0] + kpts[12][0]) / 2.0)
            com_y = float((kpts[11][1] + kpts[12][1]) / 2.0)
        elif confs[11] > 0.35:
            com_x, com_y = float(kpts[11][0]), float(kpts[11][1])
        elif confs[12] > 0.35:
            com_x, com_y = float(kpts[12][0]), float(kpts[12][1])
        else:
            com_x, com_y = None, None

        s_left = kpts[5] if confs[5] > 0.25 else None
        s_right = kpts[6] if confs[6] > 0.25 else None
        shoulder_valid = bool(s_left is not None or s_right is not None)
        if s_left is not None and s_right is not None:
            sh_x = float((s_left[0] + s_right[0]) / 2.0)
            sh_y = float((s_left[1] + s_right[1]) / 2.0)
        elif s_left is not None:
            sh_x, sh_y = float(s_left[0]), float(s_left[1])
        elif s_right is not None:
            sh_x, sh_y = float(s_right[0]), float(s_right[1])
        else:
            sh_x, sh_y = None, None

        valid_pts = [kpts[i] for i in range(17) if confs[i] > 0.25]
        if com_x is not None and com_y is not None:
            match_x, match_y = com_x, com_y
        elif sh_x is not None and sh_y is not None:
            match_x, match_y = sh_x, sh_y
        elif len(valid_pts) > 0:
            match_x = float(np.mean([pt[0] for pt in valid_pts]))
            match_y = float(np.mean([pt[1] for pt in valid_pts]))
        else:
            match_x, match_y = float(kpts[0][0]), float(kpts[0][1])

        ankle_l = kpts[15][1] if confs[15] > 0.25 else None
        ankle_r = kpts[16][1] if confs[16] > 0.25 else None
        if ankle_l is not None and ankle_r is not None:
            ankle_y = max(ankle_l, ankle_r)
        elif ankle_l is not None:
            ankle_y = ankle_l
        elif ankle_r is not None:
            ankle_y = ankle_r
        else:
            ankle_y = None

        knee_l = kpts[13][1] if confs[13] > 0.25 else None
        knee_r = kpts[14][1] if confs[14] > 0.25 else None
        if knee_l is not None and knee_r is not None:
            knee_y = max(knee_l, knee_r)
        elif knee_l is not None:
            knee_y = knee_l
        elif knee_r is not None:
            knee_y = knee_r
        else:
            knee_y = None

        if com_x is not None and sh_x is not None:
            dx = sh_x - com_x
            dy = com_y - sh_y
            spine_angle = float(math.degrees(math.atan2(dy, abs(dx) + 1e-5))) if dy > 0 else 0.0
        else:
            spine_angle = 90.0

        valid_x = [float(kpts[i][0]) for i in range(17) if confs[i] > 0.25]
        valid_y = [float(kpts[i][1]) for i in range(17) if confs[i] > 0.25]
        if len(valid_x) >= 4 and len(valid_y) >= 4:
            w_px = max(valid_x) - min(valid_x)
            h_px = max(valid_y) - min(valid_y)
            aspect_ratio = float(w_px / max(h_px, 10.0))
            max_y = float(max(valid_y))
        else:
            height_fallback = (max(valid_y) - min(valid_y)) if len(valid_y) >= 2 else 120.0
            h_px = max(height_fallback, 120.0)
            aspect_ratio = 0.5
            max_y = 200.0

        if box_xyxy is not None:
            bx1, by1, bx2, by2 = [float(v) for v in box_xyxy]
            box_ar = float((bx2 - bx1) / max(by2 - by1, 10.0))
            box_list = [int(bx1), int(by1), int(bx2), int(by2)]
        else:
            box_ar = aspect_ratio
            box_list = None

        nose_y = float(kpts[0][1]) if confs[0] > 0.25 else (float(sh_y - 30.0) if sh_y is not None else 0.0)
        left_wrist_y = kpts[9][1] if confs[9] > 0.3 else None
        pose_conf_high = bool((confs[11] > 0.35 or confs[12] > 0.35) and (confs[5] > 0.35 or confs[6] > 0.35))

        return {
            "com_x": com_x,
            "com_y": com_y,
            "sh_x": sh_x,
            "sh_y": sh_y,
            "match_x": match_x,
            "match_y": match_y,
            "hip_valid": hip_valid,
            "shoulder_valid": shoulder_valid,
            "ankle_y": float(ankle_y) if ankle_y is not None else None,
            "knee_y": float(knee_y) if knee_y is not None else None,
            "nose_y": float(nose_y),
            "spine_angle": float(spine_angle),
            "height_px": float(h_px),
            "aspect_ratio": round(aspect_ratio, 2),
            "box_ar": round(box_ar, 2),
            "box_xyxy": box_list,
            "box_conf": float(box_conf),
            "max_y": max_y,
            "left_wrist_y": left_wrist_y,
            "pose_conf_high": pose_conf_high,
            "mean_kconf": float(np.mean(confs)),
            "valid_kpts": int(np.sum(confs > 0.25)),
            "raw_kpts": kpts,
            "raw_confs": confs,
        }

    def check_periodic_workout(self, wrist_y: Optional[float]) -> bool:
        if wrist_y is None:
            return False
        self.wrist_y_history.append(wrist_y)
        if len(self.wrist_y_history) < 20:
            return False
        diffs = np.diff(self.wrist_y_history)
        zero_crossings = np.where(np.diff(np.sign(diffs)))[0]
        return 4 <= len(zero_crossings) <= 12

    def _adjust_for_stream_gap(self, now_ts: float):
        """Shifts fall timers forward if the video stream stalled/reconnected (> 2.0s)."""
        if self._last_process_ts is None:
            self._last_process_ts = now_ts
            return
        gap = now_ts - self._last_process_ts
        self._last_process_ts = now_ts
        if gap <= 2.0:
            return
        shift = gap - 0.1
        for trk in self.trackers.values():
            trk.last_fall_trigger_ts = None
            trk.last_update_ts += shift
            if trk.impact_ts is not None:
                trk.impact_ts += shift
            if trk.bending_start_ts is not None:
                trk.bending_start_ts += shift

    def _evaluate_track(
        self,
        p_id: int,
        trk: KinematicTracker,
        parsed: Dict[str, Any],
        metrics: Dict[str, Any],
        now_ts: float,
        zone_name: str,
    ):
        com_y_filt = trk.com_y_filtered if trk.com_y_filtered is not None else parsed["com_y"]
        com_y = parsed["com_y"]
        ankle_y = parsed["ankle_y"]
        knee_y = parsed.get("knee_y")
        spine_angle = parsed["spine_angle"]
        height_px = parsed["height_px"]
        aspect_ratio = parsed.get("aspect_ratio", 0.5)
        box_ar = parsed.get("box_ar", aspect_ratio)
        h_baseline = trk.get_baseline_height(height_px)

        v_norm = metrics["v_norm"]
        accel_g = metrics["accel_g"]
        jerk_g = metrics["jerk_g_per_sec"]
        ptt_sec = metrics["ptt_sec"]
        motion_energy = metrics["motion_energy"]

        if parsed.get("box_xyxy") is not None:
            trk.last_bbox = parsed["box_xyxy"]

        # Track upright calibration and clear stale squat triggers when stably upright/seated
        if spine_angle > 58.0 and height_px > 0.52 * h_baseline and aspect_ratio < 1.05:
            trk.up_frames += 1
            if trk.up_frames >= 3:
                trk.ever_upright = True

        if spine_angle > 65.0 and aspect_ratio < 1.05 and abs(v_norm) < 0.45 and parsed.get("frame_reliable", True):
            trk.stable_upright_frames += 1
            if trk.stable_upright_frames >= 3:
                trk.last_fall_trigger_ts = None
                trk.upright_anchor_ts = now_ts
        else:
            trk.stable_upright_frames = 0

        trk.com_hist.append((now_ts, float(com_y_filt), float(v_norm), float(height_px), float(spine_angle)))

        # Windowed trajectory since last upright anchor (bounded to 1.5s)
        cutoff = max(now_ts - 1.5, trk.upright_anchor_ts - 0.1) if trk.upright_anchor_ts else (now_ts - 1.5)
        win = [h for h in trk.com_hist if h[0] >= cutoff]
        max_dv_15 = max([h[2] for h in win], default=0.0)
        net_drop_15 = (float(com_y_filt) - min([h[1] for h in win], default=float(com_y_filt))) / h_baseline

        # Bending detection (feet below elevated hips, e.g., tying shoes / squatting / picking up)
        is_bending_posture = False
        if ankle_y is not None:
            if (ankle_y - com_y) > (0.28 * h_baseline):
                is_bending_posture = True
        elif knee_y is not None:
            if (knee_y - com_y) > (0.14 * h_baseline):
                is_bending_posture = True
        elif box_ar < 1.15 and (parsed.get("max_y", com_y) - com_y) > (0.28 * h_baseline):
            is_bending_posture = True

        # Camera-angle-invariant horizontal / floor-lying posture
        is_floor_lying = (
            (aspect_ratio >= 1.20 and height_px < 0.50 * h_baseline)
            or (aspect_ratio >= 1.32 and box_ar >= 1.22)
        )
        is_horizontal = (
            (spine_angle <= 35.0)
            or (spine_angle <= 58.0 and is_floor_lying)
            or (aspect_ratio >= 1.40 and box_ar >= 1.30)
        )
        trk.last_seen_upright = (spine_angle > 65.0 and aspect_ratio < 1.05 and len(trk.history) >= 3)

        # Trajectory collapse trigger (bridges short 0.25-0.55s YOLO motion-blur gaps or rapid sideways collapses)
        blur_collapse_trigger = False
        is_compact_squat = box_ar < 1.15 and aspect_ratio < 1.25
        slow_controlled_bend = (
            trk.state == STATE_BENDING
            and trk.bending_start_ts is not None
            and (now_ts - trk.bending_start_ts) >= 0.45
            and max_dv_15 < 0.70
        )
        if is_horizontal and not is_compact_squat and not slow_controlled_bend and not (
            is_bending_posture and not metrics["has_impact"] and accel_g < 2.2
        ):
            up_samples = [h for h in win if h[4] >= 55.0 or h[3] >= 0.45 * h_baseline]
            if up_samples:
                # Compare against highest/earliest upright pose in window
                best_up = min(up_samples, key=lambda h: h[1])
                dt_c = now_ts - best_up[0]
                has_blur_gap = any(
                    (win[i][0] - win[i - 1][0]) >= 0.12 for i in range(1, len(win))
                )
                max_dt_c = 1.35 if has_blur_gap else 0.90
                if 0.15 <= dt_c <= max_dt_c:
                    drop_c = (float(com_y_filt) - best_up[1]) / h_baseline
                    ang_rate = (best_up[4] - spine_angle) / dt_c
                    h_ratio = height_px / max(best_up[3], 1.0)
                    if drop_c >= 0.14 and (ang_rate >= 45.0 or h_ratio <= 0.65):
                        blur_collapse_trigger = True

        # Instantaneous kinematic fall trigger (suppressed during vertical squatting unless true hard impact)
        vertical_squat = is_bending_posture or (box_ar < 1.15 and aspect_ratio < 1.20 and (parsed.get("max_y", com_y) - com_y) > (0.18 * h_baseline))
        if vertical_squat:
            trk.last_bending_ts = now_ts
        squat_guard = vertical_squat and not metrics["has_impact"] and accel_g < 4.5
        already_resting_flat = (
            trk.state == STATE_CONTROLLED_REST
            and ptt_sec >= 1.2
            and spine_angle <= 20.0
            and abs(v_norm) < 1.8
        )
        current_fall_trigger = (
            metrics["has_impact"]
            or (not squat_guard and not already_resting_flat and (
                (v_norm > 1.1 and ptt_sec < 1.2)
                or (accel_g >= 1.6 and jerk_g >= 15.0)
                or (v_norm > 0.9 and ptt_sec < 0.8 and accel_g >= 1.3)
                or blur_collapse_trigger
            ))
        )
        if not trk.ever_upright:
            current_fall_trigger = (
                (v_norm > 0.80 and max_dv_15 >= 0.80 and spine_angle <= 48.0)
                or blur_collapse_trigger
            )

        if current_fall_trigger and parsed.get("frame_reliable", True):
            trk.last_fall_trigger_ts = now_ts

        # Upright recovery check (prevents false reset from curled legs or high diagonal spine distortion)
        has_standing_baseline = len(trk.standing_height_samples) >= 3
        com_rose_ok = True
        if trk.impact_com_y is not None:
            standing_com = float(np.median(trk.standing_com_ys)) if len(trk.standing_com_ys) >= 3 else None
            rose_from_imp = com_y < (trk.impact_com_y - 0.12 * h_baseline)
            back_near_stand = (standing_com is not None and com_y < standing_com + 0.28 * h_baseline)
            com_rose_ok = rose_from_imp or back_near_stand

        height_upright_ok = (height_px > 0.50 * h_baseline) if has_standing_baseline else (spine_angle > 68.0 and aspect_ratio < 0.80)
        is_truly_upright = (
            (spine_angle > 55.0)
            and (aspect_ratio < 1.05)
            and (box_ar < 1.05)
            and height_upright_ok
            and com_rose_ok
        )

        # Squat-to-seated-upright recovery: only clears triggers that originated from a vertical squat
        is_seated_upright = (
            trk.impact_from_squat
            and spine_angle > 65.0
            and aspect_ratio < 0.95
            and box_ar < 1.25
            and abs(v_norm) < 0.50
            and parsed.get("pose_conf_high", False)
        )

        if trk.impact_ts is not None:
            if (is_truly_upright and parsed.get("pose_conf_high", False)) or ((now_ts - trk.impact_ts) <= 2.5 and is_seated_upright):
                trk.upright_recovery_frames += 1
            else:
                trk.upright_recovery_frames = 0

            clear_needed = 3 if ((now_ts - trk.impact_ts) <= 2.5 and is_seated_upright) else 6
            if trk.upright_recovery_frames >= clear_needed:
                trk.impact_ts = None
                trk.impact_com_y = None
                trk.impact_from_squat = False
                trk.fall_confirmed = False
                trk.incident_type = "NONE"
                trk.last_fall_trigger_ts = None
                trk.upright_recovery_frames = 0
                if is_seated_upright:
                    trk.upright_anchor_ts = now_ts

        is_bending = False
        if trk.impact_ts is None and is_horizontal and is_bending_posture and v_norm < 1.3 and not metrics["has_impact"]:
            is_bending = True

        # Per-track state machine transition
        if trk.impact_ts is not None:
            trk.bending_start_ts = None
            self.bending_start_map.pop(p_id, None)
            time_since_impact = now_ts - trk.impact_ts

            is_mature_tracker = len(trk.history) >= 6 and (has_standing_baseline or trk.ever_upright)
            if is_mature_tracker and is_truly_upright and motion_energy > 0.45 and time_since_impact > 1.5:
                trk.impact_ts = None
                trk.impact_com_y = None
                trk.fall_confirmed = False
                trk.incident_type = "NONE"
                trk.upright_recovery_frames = 0
                trk.state = STATE_CONTROLLED_REST
            else:
                # Pause confirmation while victim is actively sitting/standing up
                actively_rising = (
                    trk.upright_recovery_frames > 0
                    or (spine_angle > 45.0 and aspect_ratio < 1.05 and box_ar < 1.05 and com_rose_ok)
                )
                is_confirmed = (
                    not actively_rising
                    and ((time_since_impact >= 3.5 and motion_energy < 0.38) or (time_since_impact >= 6.0))
                )
                if is_confirmed:
                    trk.state = STATE_CONFIRMED_FALL
                    trk.incident_type = "ACCIDENTAL_FALL"
                    if not trk.fall_confirmed and (now_ts - trk.last_alert_ts > 10.0):
                        self.publish_event("FALL_DETECTED", "ACCIDENTAL_FALL", 0.97, metrics, zone_name, now_ts=now_ts)
                        trk.fall_confirmed = True
                        trk.last_alert_ts = now_ts
                        self.last_alert_ts = now_ts
                elif not trk.fall_confirmed:
                    trk.state = STATE_UNRESPONSIVE
                else:
                    trk.state = STATE_CONFIRMED_FALL

        elif not is_horizontal:
            trk.state = STATE_STANDING if spine_angle > 65.0 else STATE_SITTING
            trk.fall_confirmed = False
            trk.incident_type = "NONE"
            trk.impact_ts = None
            trk.impact_com_y = None
            trk.bending_start_ts = None
            self.bending_start_map.pop(p_id, None)

        elif is_bending:
            trk.last_bending_ts = now_ts
            if trk.bending_start_ts is None:
                trk.bending_start_ts = now_ts
            self.bending_start_map[p_id] = trk.bending_start_ts
            bending_dur = now_ts - trk.bending_start_ts

            if trk.ever_upright and ((bending_dur > 5.0 and motion_energy < 0.12) or (bending_dur > 8.0)):
                trk.state = STATE_CONFIRMED_FALL
                trk.incident_type = "HEAD_SLUMP_SUSPECTED"
                if not trk.fall_confirmed and (now_ts - trk.last_alert_ts > 10.0):
                    self.publish_event("FALL_DETECTED", "HEAD_SLUMP_SUSPECTED", 0.93, metrics, zone_name, now_ts=now_ts)
                    trk.fall_confirmed = True
                    trk.last_alert_ts = now_ts
                    self.last_alert_ts = now_ts
            else:
                trk.state = STATE_BENDING
                trk.fall_confirmed = False
                trk.incident_type = "NONE"
                trk.impact_ts = None

        else:
            trk.bending_start_ts = None
            self.bending_start_map.pop(p_id, None)

            recent_trigger = (
                parsed.get("frame_reliable", True)
                and trk.last_fall_trigger_ts is not None
                and (now_ts - trk.last_fall_trigger_ts) <= 1.5
            )
            has_fall_trigger = current_fall_trigger or recent_trigger
            if has_fall_trigger and not blur_collapse_trigger and max_dv_15 < 0.75 and net_drop_15 < 0.12:
                has_fall_trigger = False

            if has_fall_trigger:
                trk.impact_ts = now_ts
                trk.impact_com_y = com_y
                trk.impact_from_squat = bool(
                    vertical_squat
                    or trk.state == STATE_BENDING
                    or (trk.last_bending_ts is not None and (now_ts - trk.last_bending_ts) <= 1.0)
                )
                trk.last_fall_trigger_ts = None
                trk.upright_recovery_frames = 0
                trk.state = STATE_UNRESPONSIVE
            else:
                trk.state = STATE_CONTROLLED_REST
                trk.impact_ts = None
                trk.impact_com_y = None
                trk.impact_from_squat = False

        trk.last_metrics = dict(metrics)

    @staticmethod
    def _state_color(state: str) -> Tuple[int, int, int]:
        if state == STATE_CONFIRMED_FALL:
            return (0, 0, 255)
        if state in (STATE_IMPACT, STATE_UNRESPONSIVE):
            return (0, 165, 255)
        if state == STATE_BENDING:
            return (0, 215, 255)
        if state in (STATE_CONTROLLED_REST, STATE_LYING):
            return (255, 255, 0)
        if state == STATE_SITTING:
            return (0, 220, 255)
        return (0, 255, 0)

    def _advance_occluded_fallen_tracks(
        self,
        matched_ids: Set[int],
        now_ts: float,
        zone_name: str,
    ):
        """Keeps UNRESPONSIVE / CONFIRMED_FALL tracks alive across brief YOLO floor-miss dropouts."""
        for p_id, trk in list(self.trackers.items()):
            if p_id in matched_ids or not trk.ever_valid_human:
                continue
            if trk.impact_ts is None or trk.last_seen_upright:
                continue
            miss_dur = now_ts - trk.last_update_ts
            limit = FALLEN_OCCLUSION_RETENTION_SEC if trk.fall_confirmed else UNCONFIRMED_OCCLUSION_RETENTION_SEC
            if miss_dur > limit:
                del self.trackers[p_id]
                self.bending_start_map.pop(p_id, None)
                self.centroid_tracker.deregister(p_id)
                continue

            time_since_impact = now_ts - trk.impact_ts
            metrics = trk.last_metrics
            is_confirmed = (time_since_impact >= 3.5 and metrics.get("motion_energy", 0.0) < 0.38) or (time_since_impact >= 6.0)
            if is_confirmed:
                trk.state = STATE_CONFIRMED_FALL
                trk.incident_type = "ACCIDENTAL_FALL"
                if not trk.fall_confirmed and (now_ts - trk.last_alert_ts > 10.0):
                    self.publish_event("FALL_DETECTED", "ACCIDENTAL_FALL", 0.97, metrics, zone_name, now_ts=now_ts)
                    trk.fall_confirmed = True
                    trk.last_alert_ts = now_ts
                    self.last_alert_ts = now_ts

    def _aggregate_engine_state(self, status_info: Dict[str, Any], now_ts: Optional[float] = None):
        ts = time.time() if now_ts is None else float(now_ts)
        active_humans = [
            trk for trk in self.trackers.values()
            if trk.ever_valid_human and (trk.impact_ts is not None or (ts - trk.last_update_ts) <= 1.5)
        ]
        if not active_humans:
            self.current_state = STATE_IDLE
            self.fall_confirmed = False
            self.impact_ts = None
            status_info["state"] = STATE_IDLE
            status_info["alert"] = False
            status_info["fall_confirmed"] = False
            status_info["incident_type"] = "NONE"
            return

        primary_trk = max(
            active_humans,
            key=lambda t: (
                STATE_SEVERITY.get(t.state, 0),
                1 if t.fall_confirmed else 0,
                t.last_update_ts,
            ),
        )
        self.current_state = primary_trk.state
        self.fall_confirmed = any(t.fall_confirmed for t in active_humans)
        self.impact_ts = primary_trk.impact_ts
        m = primary_trk.last_metrics

        status_info.update({
            "state": self.current_state,
            "v_norm": m.get("v_norm", 0.0),
            "accel_g": m.get("accel_g", 1.0),
            "ptt_sec": m.get("ptt_sec", 0.0),
            "jerk": m.get("jerk_g_per_sec", 0.0),
            "spine_angle": m.get("spine_angle", 90.0),
            "alert": self.current_state == STATE_CONFIRMED_FALL,
            "incident_type": primary_trk.incident_type,
            "fall_confirmed": self.fall_confirmed,
        })

    def process_frame(self, frame: np.ndarray, now_ts: Optional[float] = None) -> Tuple[np.ndarray, Dict[str, Any]]:
        ts = time.time() if now_ts is None else float(now_ts)
        self._adjust_for_stream_gap(ts)

        results = self.model(frame, verbose=False, conf=config.CONFIDENCE_THRESHOLD)
        zone_name = "Toàn Cảnh Camera (Omnipresent)"
        status_info: Dict[str, Any] = {
            "state": self.current_state,
            "zone": zone_name,
            "v_norm": 0.0,
            "accel_g": 1.0,
            "ptt_sec": 0.0,
            "jerk": 0.0,
            "spine_angle": 90.0,
            "alert": False,
            "incident_type": "NONE",
            "people_count": 0,
            "fall_confirmed": self.fall_confirmed,
        }

        # Clean stale trackers (> 30s for normal, > 3s for unpromoted static phantoms)
        for pid in list(self.trackers.keys()):
            trk = self.trackers[pid]
            age = ts - trk.last_update_ts
            if not trk.ever_valid_human and age > 3.0:
                del self.trackers[pid]
                self.bending_start_map.pop(pid, None)
                self.centroid_tracker.deregister(pid)
            elif trk.impact_ts is None and age > 30.0:
                del self.trackers[pid]
                self.bending_start_map.pop(pid, None)
                self.centroid_tracker.deregister(pid)

        total_detections = len(results[0].keypoints) if (results and len(results[0].keypoints) > 0) else 0
        if total_detections == 0:
            self.centroid_tracker.update([])
            self._advance_occluded_fallen_tracks(set(), ts, zone_name)
            for trk in self.trackers.values():
                if trk.ever_valid_human and trk.state in (STATE_UNRESPONSIVE, STATE_CONFIRMED_FALL) and trk.last_bbox is not None and (ts - trk.last_update_ts) <= 1.5:
                    b = trk.last_bbox
                    col = self._state_color(trk.state)
                    cv2.rectangle(frame, (b[0], b[1]), (b[2], b[3]), col, 2)
                    if trk.state == STATE_CONFIRMED_FALL:
                        cv2.rectangle(frame, (b[0], max(0, b[1] - 40)), (b[2], b[1]), (0, 0, 255), -1)
                        cv2.putText(frame, "CRITICAL FALL!", (b[0] + 5, max(20, b[1] - 10)),
                                    cv2.FONT_HERSHEY_DUPLEX, 0.75, (255, 255, 255), 2)
            self._aggregate_engine_state(status_info, now_ts=ts)
            return frame, status_info

        boxes = results[0].boxes
        candidates_raw = []
        for idx in range(total_detections):
            kpts_data = results[0].keypoints[idx:idx + 1]
            b_xyxy = boxes[idx].xyxy[0].cpu().numpy() if (boxes and idx < len(boxes)) else None
            b_conf = float(boxes[idx].conf) if (boxes and idx < len(boxes)) else 1.0
            cand = self.extract_keypoints(kpts_data, box_xyxy=b_xyxy, box_conf=b_conf)
            cand["_orig_b_idx"] = idx
            candidates_raw.append(cand)

        # Intra-frame NMS deduplication (prevents duplicate overlapping boxes spawning duplicate fall tracks)
        candidates_raw.sort(key=lambda c: c["box_conf"] * c["mean_kconf"], reverse=True)
        deduped = []
        for cand in candidates_raw:
            if any(_iou(cand.get("box_xyxy"), kept.get("box_xyxy")) > 0.55 for kept in deduped):
                continue
            deduped.append(cand)
        candidates_raw = deduped

        centroids = [(c["match_x"], c["match_y"]) for c in candidates_raw]
        cand_boxes = [c.get("box_xyxy") for c in candidates_raw]
        upright_flags = [
            bool(c["spine_angle"] > 60.0 and c["aspect_ratio"] < 0.9 and c["box_ar"] < 0.9)
            for c in candidates_raw
        ]

        baseline_map = {pid: trk.get_baseline_height() for pid, trk in self.trackers.items()}
        last_bboxes = {pid: trk.last_bbox for pid, trk in self.trackers.items() if trk.last_bbox is not None}
        reserved_ids = {
            pid for pid, trk in self.trackers.items()
            if trk.ever_valid_human and trk.state in (STATE_UNRESPONSIVE, STATE_CONFIRMED_FALL)
        }
        track_ids = self.centroid_tracker.update(
            centroids,
            baseline_map=baseline_map,
            reserved_ids=reserved_ids,
            candidate_upright_flags=upright_flags,
            candidate_boxes=cand_boxes,
            last_bboxes=last_bboxes,
        )

        matched_ids: Set[int] = set()
        validated_count = 0
        draw_items = []

        for idx, cand in enumerate(candidates_raw):
            p_id = track_ids[idx]
            matched_ids.add(p_id)
            if p_id not in self.trackers:
                self.trackers[p_id] = KinematicTracker(now_ts=ts)
                self.bending_start_map.pop(p_id, None)

            trk = self.trackers[p_id]
            trk.aspect_ratio = cand.get("aspect_ratio", 0.5)
            trk.box_aspect_ratio = cand.get("box_ar", trk.aspect_ratio)
            trk.update_validation_gate(cand)

            if not trk.ever_valid_human:
                trk.last_update_ts = ts
                continue

            validated_count += 1
            resolved_cand = trk.resolve_and_update(cand, False, now_ts=ts)
            metrics = trk.compute_metrics(now_ts=ts)
            self._evaluate_track(p_id, trk, resolved_cand, metrics, ts, zone_name)
            draw_items.append((p_id, trk, resolved_cand, cand["_orig_b_idx"]))

        status_info["people_count"] = validated_count
        self._advance_occluded_fallen_tracks(matched_ids, ts, zone_name)

        # Draw per-person bounding boxes and skeletons using each person's own state
        skeleton_links = [
            (5, 6), (5, 7), (7, 9), (6, 8), (8, 10),
            (5, 11), (6, 12), (11, 12),
            (11, 13), (13, 15), (12, 14), (14, 16),
        ]
        for p_id, trk, resolved_cand, b_idx in draw_items:
            p_color = self._state_color(trk.state)
            if boxes and b_idx < len(boxes):
                box = boxes[b_idx].xyxy[0].cpu().numpy().astype(int)
                cv2.rectangle(frame, (box[0], box[1]), (box[2], box[3]), p_color, 2)
                if trk.state == STATE_CONFIRMED_FALL:
                    cv2.rectangle(frame, (box[0], max(0, box[1] - 40)), (box[2], box[1]), (0, 0, 255), -1)
                    cv2.putText(frame, "CRITICAL FALL!", (box[0] + 5, max(20, box[1] - 10)),
                                cv2.FONT_HERSHEY_DUPLEX, 0.75, (255, 255, 255), 2)

            # [TẮT HIỂN THỊ] Khung xương 17 điểm — chỉ giữ bounding box trên camera.
            # AI vẫn dùng keypoints để phát hiện té ngã, chỉ không vẽ lên khung hình.
            # Bỏ comment khối dưới nếu muốn hiện lại khung xương.
            # cand_kpts = resolved_cand["raw_kpts"]
            # cand_confs = resolved_cand["raw_confs"]
            # for p1, p2 in skeleton_links:
            #     if cand_confs[p1] > 0.25 and cand_confs[p2] > 0.25:
            #         cv2.line(frame, (int(cand_kpts[p1][0]), int(cand_kpts[p1][1])),
            #                  (int(cand_kpts[p2][0]), int(cand_kpts[p2][1])), p_color, 2, cv2.LINE_AA)
            # for i in range(17):
            #     if cand_confs[i] > 0.25:
            #         cv2.circle(frame, (int(cand_kpts[i][0]), int(cand_kpts[i][1])), 4, (0, 255, 255), -1, cv2.LINE_AA)

        # Preserve visual alert box briefly during momentary YOLO flicker (<= 1.5s)
        for p_id, trk in self.trackers.items():
            if p_id in matched_ids or not trk.ever_valid_human:
                continue
            if trk.state in (STATE_UNRESPONSIVE, STATE_CONFIRMED_FALL) and trk.last_bbox is not None and (ts - trk.last_update_ts) <= 1.5:
                b = trk.last_bbox
                p_color = self._state_color(trk.state)
                cv2.rectangle(frame, (b[0], b[1]), (b[2], b[3]), p_color, 2)
                if trk.state == STATE_CONFIRMED_FALL:
                    cv2.rectangle(frame, (b[0], max(0, b[1] - 40)), (b[2], b[1]), (0, 0, 255), -1)
                    cv2.putText(frame, "CRITICAL FALL!", (b[0] + 5, max(20, b[1] - 10)),
                                cv2.FONT_HERSHEY_DUPLEX, 0.75, (255, 255, 255), 2)

        self._aggregate_engine_state(status_info, now_ts=ts)
        return frame, status_info


def main():
    parser = argparse.ArgumentParser(description="HESTA Edge AI Fall Detection Engine")
    parser.add_argument("--source", type=str, default="auto", help="Webcam index (0), RTSP URL, or 'auto'")
    parser.add_argument("--model", type=str, default=config.YOLO_MODEL_PATH, help="YOLOv8-pose model weights")
    parser.add_argument("--mqtt-host", type=str, default=config.MQTT_HOST, help="MQTT Broker Host")
    parser.add_argument("--mqtt-port", type=int, default=config.MQTT_PORT, help="MQTT Broker Port")
    parser.add_argument("--no-gui", action="store_true", help="Run in headless mode without OpenCV GUI")
    args = parser.parse_args()

    if args.source == "auto":
        if config.CAMERA_MODE == "IP_CAMERA":
            source = config.RTSP_URL
            mode_desc = f"CAMERA IP THẬT (RTSP: {source})"
        else:
            source = config.WEBCAM_INDEX
            mode_desc = f"WEBCAM LAPTOP / MÁY TÍNH (Index: {source})"
    else:
        source = int(args.source) if args.source.isdigit() else args.source
        mode_desc = f"TÙY CHỈNH: {source}"

    print("\n" + "=" * 70)
    print(" HESTA AI FALL DETECTOR (UNIFIED PRODUCTION ENGINE)")
    print(f" - Chế độ nguồn: {mode_desc}")
    print(" - Phạm vi: Giám sát 100% tầm nhìn camera (Toàn cảnh)")
    print(" - Press 'q' to exit")
    print("=" * 70 + "\n")

    engine = FallDetectorEngine(
        model_path=args.model,
        mqtt_host=args.mqtt_host,
        mqtt_port=args.mqtt_port,
        enable_mqtt=True,
    )

    def on_stream_health(health_data):
        engine.publish_camera_health(health_data)

    stream_mgr = RTSPStreamManager(
        source=source,
        name="MainStream",
        health_callback=on_stream_health,
    ).start()

    if not args.no_gui:
        cv2.namedWindow("HESTA Fall Detection Engine", cv2.WINDOW_NORMAL)

    prev_time = time.time()
    try:
        while True:
            is_live, frame = stream_mgr.read()
            if frame is None:
                time.sleep(0.02)
                continue

            if is_live:
                processed_frame, status = engine.process_frame(frame)
            else:
                processed_frame = frame

            curr_time = time.time()
            fps = 1.0 / max(curr_time - prev_time, 0.001)
            prev_time = curr_time
            cv2.putText(processed_frame, f"FPS: {fps:.1f}", (processed_frame.shape[1] - 120, 30),
                        cv2.FONT_HERSHEY_SIMPLEX, 0.6, (0, 255, 0), 2)

            if not args.no_gui:
                cv2.imshow("HESTA Fall Detection Engine", processed_frame)
                key = cv2.waitKey(1) & 0xFF
                if key == ord('q'):
                    break
    finally:
        stream_mgr.stop()
        if not args.no_gui:
            cv2.destroyAllWindows()
        print("[HESTA] Engine shutdown safely.")


if __name__ == "__main__":
    main()
