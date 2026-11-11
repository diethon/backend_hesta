import httpx
import os
from pprint import pprint
import pytest

BASE_URL = "http://localhost:8080"
API_KEY = "super_secret_voice_key_123"

@pytest.mark.skip(reason="Manual integration test against live Spring Boot and hardware")
def test_real_backend():
    print("Testing GET /internal/devices...")
    with httpx.Client(base_url=BASE_URL) as client:
        # GET devices
        r = client.get("/internal/devices", headers={"X-Internal-Api-Key": API_KEY})
        print(f"GET Status: {r.status_code}")
        if r.status_code == 200:
            devices = r.json()
            print(f"Found {len(devices)} devices.")
            if devices:
                pprint(devices[0])
                
                # Try POST action to the first device
                dev_id = devices[0].get("id")
                print(f"\nTesting POST /internal/devices/{dev_id}/actions...")
                action_payload = {
                    "action": "TURN_ON",
                    "parameters": {}
                }
                r_post = client.post(
                    f"/internal/devices/{dev_id}/actions",
                    json=action_payload,
                    headers={"X-Internal-Api-Key": API_KEY}
                )
                print(f"POST Status: {r_post.status_code}")
                print(f"POST Response: {r_post.text}")
        else:
            print(f"Error: {r.text}")

if __name__ == "__main__":
    test_real_backend()
