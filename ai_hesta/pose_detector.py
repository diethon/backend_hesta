#!/usr/bin/env python3
"""
HESTA Smart Home - Unified PoseDetector Engine
=============================================
Unified inference abstraction layer supporting:
1. Laptop / Workstation (x86_64, Windows, macOS, Linux):
   - Fallback backend via PyTorch (ultralytics.YOLO).
2. Orange Pi 5 Plus (Rockchip RK3588 NPU - aarch64):
   - High-throughput hardware-accelerated NPU backend via rknnlite.api.RKNNLite
   - Tri-core NPU execution (NPU_CORE_0_1_2) achieving up to 6 TOPS peak.

Standardized Output Contract:
-----------------------------
Regardless of whether .pt or .rknn is active, predict(frame) returns an
encapsulated result structure providing:
- boxes: np.ndarray (N, 5) -> [x1, y1, x2, y2, confidence]
- keypoints: np.ndarray (N, 17, 3) -> [x, y, confidence] in original image pixel scale
- Full backward compatibility adapter for Ultralytics result interface:
  len(results[0].keypoints), results[0].keypoints[idx:idx+1].xy[0],
  results[0].boxes[b_idx].xyxy[0], etc.
"""

import os
import sys
import math
import platform
import time
from typing import List, Tuple, Dict, Any, Optional, Union

import cv2
import numpy as np

# Safe conditional imports
try:
    from ultralytics import YOLO
    PYTORCH_YOLO_AVAILABLE = True
except ImportError:
    PYTORCH_YOLO_AVAILABLE = False

try:
    from rknnlite.api import RKNNLite
    RKNN_LITE_AVAILABLE = True
except ImportError:
    RKNN_LITE_AVAILABLE = False


# ==============================================================================
# ULTRALYTICS BACKWARD-COMPATIBLE ADAPTER DTOs
# ==============================================================================
class KeypointsSlice:
    """Simulates results[0].keypoints[idx:idx+1] slice."""
    def __init__(self, kpts_xy: np.ndarray, kpts_conf: np.ndarray):
        # kpts_xy: (1, 17, 2), kpts_conf: (1, 17)
        self._xy = kpts_xy
        self._conf = kpts_conf

    @property
    def xy(self):
        class TorchShim:
            def __init__(self, arr):
                self._arr = arr
            def cpu(self):
                return self
            def numpy(self):
                return self._arr
        return [TorchShim(self._xy[0])]

    @property
    def conf(self):
        class TorchShim:
            def __init__(self, arr):
                self._arr = arr
            def cpu(self):
                return self
            def numpy(self):
                return self._arr
        return [TorchShim(self._conf[0])] if self._conf is not None else None


class KeypointsContainer:
    """Simulates results[0].keypoints container supporting len() and slicing."""
    def __init__(self, raw_kpts: np.ndarray):
        # raw_kpts: (N, 17, 3) -> [x, y, conf]
        self.raw_kpts = raw_kpts
        self.count = len(raw_kpts) if raw_kpts is not None else 0

    def __len__(self):
        return self.count

    def __getitem__(self, idx: Union[int, slice]):
        if isinstance(idx, slice):
            sliced = self.raw_kpts[idx]
            if len(sliced) == 0:
                return KeypointsSlice(np.zeros((0, 17, 2)), np.zeros((0, 17)))
            return KeypointsSlice(sliced[:, :, :2], sliced[:, :, 2])
        else:
            item = self.raw_kpts[idx:idx+1]
            return KeypointsSlice(item[:, :, :2], item[:, :, 2])

    @property
    def xy(self):
        class TorchShim:
            def __init__(self, arr):
                self._arr = arr
            def cpu(self):
                return self
            def numpy(self):
                return self._arr
        return TorchShim(self.raw_kpts[:, :, :2] if self.count > 0 else np.zeros((0, 17, 2)))

    @property
    def conf(self):
        class TorchShim:
            def __init__(self, arr):
                self._arr = arr
            def cpu(self):
                return self
            def numpy(self):
                return self._arr
        return TorchShim(self.raw_kpts[:, :, 2] if self.count > 0 else np.zeros((0, 17)))


class BoxItem:
    """Simulates results[0].boxes[b_idx]."""
    def __init__(self, box_xyxy: np.ndarray, conf: float):
        self._xyxy = box_xyxy.reshape(1, 4) # (1, 4)
        self._conf = float(conf)

    @property
    def xyxy(self):
        class TorchShim:
            def __init__(self, arr):
                self._arr = arr
            def cpu(self):
                return self
            def numpy(self):
                return self._arr
        return [TorchShim(self._xyxy[0])]

    @property
    def conf(self):
        return self._conf


class BoxesContainer:
    """Simulates results[0].boxes container."""
    def __init__(self, raw_boxes: np.ndarray):
        # raw_boxes: (N, 5) -> [x1, y1, x2, y2, conf]
        self.raw_boxes = raw_boxes
        self.count = len(raw_boxes) if raw_boxes is not None else 0
        self._items = [BoxItem(raw_boxes[i, :4], raw_boxes[i, 4]) for i in range(self.count)]

    def __len__(self):
        return self.count

    def __getitem__(self, idx: int):
        return self._items[idx]


class StandardPoseResult:
    """
    Standardized inference result returned by PoseDetector regardless of backend.
    Maintains clean NumPy array attributes AND full Ultralytics API compatibility.
    """
    def __init__(self, boxes: np.ndarray, keypoints: np.ndarray, orig_shape: Tuple[int, int]):
        # boxes: np.ndarray shape (N, 5) -> [x1, y1, x2, y2, conf]
        # keypoints: np.ndarray shape (N, 17, 3) -> [x, y, conf]
        self.raw_boxes = boxes if boxes is not None else np.zeros((0, 5), dtype=np.float32)
        self.raw_keypoints = keypoints if keypoints is not None else np.zeros((0, 17, 3), dtype=np.float32)
        self.orig_shape = orig_shape

        # Backward compatibility bridges
        self.boxes = BoxesContainer(self.raw_boxes)
        self.keypoints = KeypointsContainer(self.raw_keypoints)


# ==============================================================================
# LETTERBOX & POST-PROCESSING UTILITIES FOR RKNN NPU
# ==============================================================================
def letterbox(
    img: np.ndarray,
    new_shape: Tuple[int, int] = (640, 640),
    color: Tuple[int, int, int] = (114, 114, 114)
) -> Tuple[np.ndarray, float, Tuple[float, float]]:
    """Resizes and pads image to square while preserving aspect ratio."""
    shape = img.shape[:2] # [h, w]
    r = min(new_shape[0] / shape[0], new_shape[1] / shape[1])
    new_unpad = (int(round(shape[1] * r)), int(round(shape[0] * r)))
    dw, dh = new_shape[1] - new_unpad[0], new_shape[0] - new_unpad[1]
    dw, dh = dw / 2.0, dh / 2.0

    if shape[::-1] != new_unpad:
        img_resized = cv2.resize(img, new_unpad, interpolation=cv2.INTER_LINEAR)
    else:
        img_resized = img

    top, bottom = int(round(dh - 0.1)), int(round(dh + 0.1))
    left, right = int(round(dw - 0.1)), int(round(dw + 0.1))
    img_padded = cv2.copyMakeBorder(img_resized, top, bottom, left, right, cv2.BORDER_CONSTANT, value=color)
    return img_padded, r, (dw, dh)


def decode_yolov8_pose_output(
    output: np.ndarray,
    conf_thresh: float = 0.35,
    iou_thresh: float = 0.45,
    orig_shape: Tuple[int, int] = (480, 640),
    ratio: float = 1.0,
    pads: Tuple[float, float] = (0.0, 0.0)
) -> Tuple[np.ndarray, np.ndarray]:
    """
    Decodes raw YOLOv8-Pose head output tensor (1, 56, 8400) or (1, 8400, 56),
    applies NMS, and rescales coordinates back to original image space.
    """
    if output.ndim == 3:
        if output.shape[1] == 56: # (1, 56, 8400) -> transpose to (8400, 56)
            output = output[0].T
        else:
            output = output[0] # (1, 8400, 56) -> (8400, 56)

    # Output structure:
    # 0..3: cx, cy, w, h
    # 4: person class score
    # 5..55: 17 keypoints x [kx, ky, k_conf]
    scores = output[:, 4]
    valid_mask = scores >= conf_thresh
    if not np.any(valid_mask):
        return np.zeros((0, 5), dtype=np.float32), np.zeros((0, 17, 3), dtype=np.float32)

    valid_output = output[valid_mask]
    valid_scores = scores[valid_mask]

    # Compute bounding boxes in letterbox space
    cx = valid_output[:, 0]
    cy = valid_output[:, 1]
    w = valid_output[:, 2]
    h = valid_output[:, 3]

    x1 = cx - w / 2.0
    y1 = cy - h / 2.0
    x2 = cx + w / 2.0
    y2 = cy + h / 2.0

    boxes_ltrb = np.column_stack([x1, y1, x2 - x1, y2 - y1]) # [x, y, w, h] for cv2.dnn.NMSBoxes

    # OpenCV NMS
    indices = cv2.dnn.NMSBoxes(
        bboxes=boxes_ltrb.tolist(),
        scores=valid_scores.tolist(),
        score_threshold=float(conf_thresh),
        nms_threshold=float(iou_thresh)
    )

    if len(indices) == 0:
        return np.zeros((0, 5), dtype=np.float32), np.zeros((0, 17, 3), dtype=np.float32)

    indices = np.array(indices).flatten()

    dw, dh = pads
    final_boxes = []
    final_kpts = []

    for idx in indices:
        bx1 = (x1[idx] - dw) / ratio
        by1 = (y1[idx] - dh) / ratio
        bx2 = (x2[idx] - dw) / ratio
        by2 = (y2[idx] - dh) / ratio
        bscore = valid_scores[idx]

        # Clip boxes to original image limits
        orig_h, orig_w = orig_shape
        bx1 = max(0.0, min(bx1, orig_w))
        by1 = max(0.0, min(by1, orig_h))
        bx2 = max(0.0, min(bx2, orig_w))
        by2 = max(0.0, min(by2, orig_h))
        final_boxes.append([bx1, by1, bx2, by2, bscore])

        # Keypoints extraction (17 keypoints)
        raw_kpt = valid_output[idx, 5:].reshape(17, 3)
        rescaled_kpt = np.zeros((17, 3), dtype=np.float32)
        rescaled_kpt[:, 0] = (raw_kpt[:, 0] - dw) / ratio
        rescaled_kpt[:, 1] = (raw_kpt[:, 1] - dh) / ratio
        # Keypoint confidence handling:
        # In YOLOv8 pose export for ONNX/RKNN, raw keypoint confidence is logits unless explicit sigmoid was appended.
        k_conf = raw_kpt[:, 2].astype(np.float32)
        if np.any(k_conf > 1.0) or np.any(k_conf < 0.0) or (np.max(k_conf) <= 0.0):
            k_conf = 1.0 / (1.0 + np.exp(-np.clip(k_conf, -20.0, 20.0)))
        rescaled_kpt[:, 2] = k_conf
        final_kpts.append(rescaled_kpt)

    return np.array(final_boxes, dtype=np.float32), np.array(final_kpts, dtype=np.float32)


def decode_yolov8_pose_9head_outputs(
    outputs: List[np.ndarray],
    conf_thresh: float = 0.35,
    iou_thresh: float = 0.45,
    orig_shape: Tuple[int, int] = (480, 640),
    ratio: float = 1.0,
    pads: Tuple[float, float] = (0.0, 0.0)
) -> Tuple[np.ndarray, np.ndarray]:
    """
    Decodes the 9 cropped YOLOv8-Pose head outputs (strides 8, 16, 32: box DFL,
    cls logit, keypoint raw maps) directly in NumPy to avoid RK3588 NPU 13-bit
    width register overflow on 8400-anchor post-processing tensors.
    """
    dfl_bins = np.arange(16, dtype=np.float32)
    clamped_conf = min(max(float(conf_thresh), 1e-6), 1.0 - 1e-6)
    logit_thresh = math.log(clamped_conf / (1.0 - clamped_conf))

    all_boxes_xyxy: List[np.ndarray] = []
    all_scores: List[np.ndarray] = []
    all_kpts: List[np.ndarray] = []

    for s_idx, stride in enumerate((8, 16, 32)):
        box_map = outputs[s_idx * 3 + 0][0]     # (64, H, W)
        cls_map = outputs[s_idx * 3 + 1][0, 0]  # (H, W)
        kpt_map = outputs[s_idx * 3 + 2][0]     # (51, H, W)

        gy, gx = np.where(cls_map >= logit_thresh)
        if len(gy) == 0:
            continue

        scores = 1.0 / (1.0 + np.exp(-np.clip(cls_map[gy, gx].astype(np.float32), -20.0, 20.0)))

        dfl = box_map[:, gy, gx].T.reshape(-1, 4, 16).astype(np.float32)
        dfl = dfl - np.max(dfl, axis=2, keepdims=True)
        exp_dfl = np.exp(dfl)
        ltrb = (exp_dfl / np.sum(exp_dfl, axis=2, keepdims=True)) @ dfl_bins

        gx_f = gx.astype(np.float32)
        gy_f = gy.astype(np.float32)
        x1 = (gx_f + 0.5 - ltrb[:, 0]) * stride
        y1 = (gy_f + 0.5 - ltrb[:, 1]) * stride
        x2 = (gx_f + 0.5 + ltrb[:, 2]) * stride
        y2 = (gy_f + 0.5 + ltrb[:, 3]) * stride

        kp = kpt_map[:, gy, gx].T.reshape(-1, 17, 3).astype(np.float32)
        kx = (kp[:, :, 0] * 2.0 + gx_f[:, None]) * stride
        ky = (kp[:, :, 1] * 2.0 + gy_f[:, None]) * stride
        kc = 1.0 / (1.0 + np.exp(-np.clip(kp[:, :, 2], -20.0, 20.0)))

        all_boxes_xyxy.append(np.column_stack([x1, y1, x2, y2]))
        all_scores.append(scores.astype(np.float32))
        all_kpts.append(np.stack([kx, ky, kc], axis=2).astype(np.float32))

    if not all_scores:
        return np.zeros((0, 5), dtype=np.float32), np.zeros((0, 17, 3), dtype=np.float32)

    boxes_cat = np.concatenate(all_boxes_xyxy, axis=0)
    scores_cat = np.concatenate(all_scores, axis=0)
    kpts_cat = np.concatenate(all_kpts, axis=0)

    boxes_xywh = np.column_stack([
        boxes_cat[:, 0],
        boxes_cat[:, 1],
        boxes_cat[:, 2] - boxes_cat[:, 0],
        boxes_cat[:, 3] - boxes_cat[:, 1],
    ])
    indices = cv2.dnn.NMSBoxes(
        bboxes=boxes_xywh.tolist(),
        scores=scores_cat.tolist(),
        score_threshold=float(conf_thresh),
        nms_threshold=float(iou_thresh),
    )
    if len(indices) == 0:
        return np.zeros((0, 5), dtype=np.float32), np.zeros((0, 17, 3), dtype=np.float32)

    indices = np.array(indices).flatten()
    dw, dh = pads
    orig_h, orig_w = orig_shape

    kept_boxes = boxes_cat[indices].copy()
    kept_boxes[:, [0, 2]] = np.clip((kept_boxes[:, [0, 2]] - dw) / ratio, 0.0, orig_w)
    kept_boxes[:, [1, 3]] = np.clip((kept_boxes[:, [1, 3]] - dh) / ratio, 0.0, orig_h)
    final_boxes = np.column_stack([kept_boxes, scores_cat[indices]]).astype(np.float32)

    kept_kpts = kpts_cat[indices].copy()
    kept_kpts[:, :, 0] = (kept_kpts[:, :, 0] - dw) / ratio
    kept_kpts[:, :, 1] = (kept_kpts[:, :, 1] - dh) / ratio
    final_kpts = kept_kpts.astype(np.float32)

    return final_boxes, final_kpts


# ==============================================================================
# UNIFIED POSE DETECTOR WRAPPER
# ==============================================================================
class PoseDetector:
    """
    Unified Pose Estimation Interface.
    Auto-detects runtime environment (Laptop x86/ARM PyTorch vs. Orange Pi 5 Plus RK3588 NPU).
    """
    def __init__(
        self,
        model_path: Optional[str] = None,
        backend: str = "AUTO",
        conf: float = 0.35,
        target_platform: str = "rk3588"
    ):
        self.conf = conf
        self.target_platform = target_platform
        self.backend = self._resolve_backend(backend, model_path)
        self.model = None
        self.rknn = None

        self._init_backend(model_path)

    def _resolve_backend(self, requested_backend: str, model_path: Optional[str]) -> str:
        """Determines the active backend (RKNN vs. PT) with intelligent auto-detection."""
        env_backend = os.getenv("HESTA_AI_BACKEND", requested_backend).upper()
        if env_backend in ("RKNN", "NPU"):
            return "RKNN"
        if env_backend in ("PT", "PYTORCH", "CPU", "GPU"):
            return "PT"

        # AUTO Detection
        is_aarch64 = platform.machine().lower() in ("aarch64", "arm64")
        has_rknn = RKNN_LITE_AVAILABLE

        # Check RKNN weights availability
        base_dir = os.path.dirname(os.path.abspath(__file__))
        rknn_file = model_path if (model_path and model_path.endswith(".rknn")) else os.path.join(base_dir, "yolov8n-pose.rknn")
        rknn_exists = os.path.isfile(rknn_file)

        if is_aarch64 and has_rknn and rknn_exists:
            print(f"[PoseDetector] Auto-detected ARM aarch64 environment with rknnlite & {os.path.basename(rknn_file)}. Using RKNN NPU backend.")
            return "RKNN"

        return "PT"

    def _init_backend(self, model_path: Optional[str]):
        base_dir = os.path.dirname(os.path.abspath(__file__))

        if self.backend == "RKNN":
            rknn_file = model_path if (model_path and model_path.endswith(".rknn")) else os.path.join(base_dir, "yolov8n-pose.rknn")
            if not os.path.isfile(rknn_file):
                print(f"[PoseDetector] RKNN file not found ({rknn_file}). Falling back to PyTorch .pt.")
                self.backend = "PT"
                self._init_backend(model_path)
                return

            print(f"[PoseDetector] Initializing RKNNLite NPU on {self.target_platform}...")
            self.rknn = RKNNLite(verbose=False)
            ret = self.rknn.load_rknn(rknn_file)
            if ret != 0:
                print(f"[PoseDetector] Error loading RKNN model ({ret}). Falling back to PyTorch .pt.")
                self.backend = "PT"
                self._init_backend(model_path)
                return

            # Maximize throughput across all 3 NPU cores on RK3588
            ret = self.rknn.init_runtime(core_mask=RKNNLite.NPU_CORE_0_1_2)
            if ret != 0:
                print(f"[PoseDetector] Error initializing NPU multi-core runtime ({ret}). Falling back to single core.")
                ret = self.rknn.init_runtime()
                if ret != 0:
                    print(f"[PoseDetector] Could not initialize RKNN runtime. Falling back to PyTorch.")
                    self.backend = "PT"
                    self._init_backend(model_path)
                    return

            print(f"[PoseDetector] RKNN NPU Engine ONLINE on Tri-Core NPU_CORE_0_1_2 (Model: {os.path.basename(rknn_file)})")

        else:
            pt_file = model_path if (model_path and model_path.endswith(".pt")) else os.path.join(base_dir, "yolov8n-pose.pt")
            if not PYTORCH_YOLO_AVAILABLE:
                raise RuntimeError("PyTorch/Ultralytics is not installed. Please install ultralytics or run in RKNN mode.")

            print(f"[PoseDetector] Initializing PyTorch YOLOv8-Pose (Fallback / Laptop Mode: {os.path.basename(pt_file)})...")
            self.model = YOLO(pt_file)
            print("[PoseDetector] PyTorch YOLOv8-Pose Engine ONLINE.")

    def predict(self, frame: np.ndarray, conf: Optional[float] = None) -> List[StandardPoseResult]:
        """
        Executes pose detection on input frame (BGR numpy image).
        Returns list containing one StandardPoseResult matching Ultralytics results[0] convention.
        """
        conf_thresh = conf if conf is not None else self.conf
        orig_shape = frame.shape[:2] # (h, w)

        if self.backend == "RKNN":
            # 1. Letterbox resize (640, 640)
            img_padded, ratio, (dw, dh) = letterbox(frame, new_shape=(640, 640))
            # 2. Convert BGR to RGB
            img_rgb = cv2.cvtColor(img_padded, cv2.COLOR_BGR2RGB)
            img_input = np.expand_dims(img_rgb, axis=0) # (1, 640, 640, 3)

            # 3. NPU Inference
            outputs = self.rknn.inference(inputs=[img_input], data_format="nhwc")

            # 4. Post-processing & Letterbox Rescaling
            if len(outputs) == 9:
                raw_boxes, raw_kpts = decode_yolov8_pose_9head_outputs(
                    outputs=outputs,
                    conf_thresh=conf_thresh,
                    iou_thresh=0.45,
                    orig_shape=orig_shape,
                    ratio=ratio,
                    pads=(dw, dh)
                )
            else:
                raw_boxes, raw_kpts = decode_yolov8_pose_output(
                    output=outputs[0],
                    conf_thresh=conf_thresh,
                    iou_thresh=0.45,
                    orig_shape=orig_shape,
                    ratio=ratio,
                    pads=(dw, dh)
                )
            return [StandardPoseResult(raw_boxes, raw_kpts, orig_shape)]

        else:
            # PyTorch Ultralytics Inference
            res = self.model(frame, verbose=False, conf=conf_thresh)
            if not res or len(res[0].keypoints) == 0:
                return [StandardPoseResult(np.zeros((0, 5), dtype=np.float32), np.zeros((0, 17, 3), dtype=np.float32), orig_shape)]

            # Convert PyTorch tensors to standardized numpy arrays
            kpts_xy = res[0].keypoints.xy.cpu().numpy() # (N, 17, 2)
            kpts_conf = res[0].keypoints.conf.cpu().numpy() if res[0].keypoints.conf is not None else np.ones(kpts_xy.shape[:2]) # (N, 17)
            raw_kpts = np.dstack([kpts_xy, kpts_conf]) # (N, 17, 3)

            boxes_xyxy = res[0].boxes.xyxy.cpu().numpy() # (N, 4)
            boxes_conf = res[0].boxes.conf.cpu().numpy() # (N,)
            raw_boxes = np.column_stack([boxes_xyxy, boxes_conf]) # (N, 5)

            return [StandardPoseResult(raw_boxes, raw_kpts, orig_shape)]

    def __call__(self, frame: np.ndarray, verbose: bool = False, conf: Optional[float] = None) -> List[StandardPoseResult]:
        """Provides callable interface identical to ultralytics.YOLO(frame)."""
        return self.predict(frame, conf=conf)

    def release(self):
        """Releases hardware resources."""
        if self.rknn is not None:
            try:
                self.rknn.release()
            except Exception:
                pass
            self.rknn = None
