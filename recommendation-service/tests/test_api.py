import unittest
from unittest.mock import patch

from fastapi.testclient import TestClient

from app.config import settings
from app.main import app
from app.schemas import ModelStatusResponse


class TestAPI(unittest.TestCase):

    def setUp(self):
        self.client = TestClient(app)
        # Tests must not depend on the developer's .env; ones that need a key patch it themselves.
        self.enterContext(patch.object(settings, "API_KEY", ""))

    def test_health_endpoint_is_open(self):
        with patch.object(settings, "API_KEY", "secret"):
            response = self.client.get("/health")
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["status"], "UP")

    def test_model_status_per_home(self):
        response = self.client.get("/model/status", params={"home_id": "home-without-model"})
        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertEqual(data["status"], "COLD_START")
        self.assertEqual(data["confidenceThreshold"], 0.80)

    def test_api_key_is_required_when_configured(self):
        with patch.object(settings, "API_KEY", "secret"):
            denied = self.client.get("/model/status")
            allowed = self.client.get("/model/status", headers={"X-API-Key": "secret"})
        self.assertEqual(denied.status_code, 401)
        self.assertEqual(allowed.status_code, 200)

    def test_generate_requires_home_and_survives_missing_database(self):
        with patch("app.model.load_events_from_db", side_effect=RuntimeError("no db")):
            response = self.client.post("/recommendations/generate", json={"home_id": "h1"})
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["recommendations"], [])
        self.assertEqual(self.client.post("/recommendations/generate", json={}).status_code, 422)

    def test_train_all_homes_survives_one_failing_home(self):
        def fake_train(home, test_size):
            if home == "bad":
                raise RuntimeError("db down")
            return ModelStatusResponse(status="READY", version="v1")
        with patch("app.main.list_homes_with_actions", return_value=["bad", "good"]),                 patch("app.main.train_one", side_effect=fake_train):
            response = self.client.post("/train", json={})
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["metrics"]["homes"], {"bad": "ERROR", "good": "READY"})

    def test_train_rejects_bad_test_size(self):
        self.assertEqual(self.client.post("/train", json={"home_id": "h1", "test_size": 0.9}).status_code, 422)

    def test_predict_endpoint_was_removed(self):
        self.assertEqual(self.client.post("/predict", json={}).status_code, 404)


if __name__ == '__main__':
    unittest.main()
