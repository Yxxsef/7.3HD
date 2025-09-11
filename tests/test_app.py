from fastapi.testclient import TestClient
from app.main import app
c = TestClient(app)

def test_health():
    r = c.get("/health")
    assert r.status_code == 200 and r.json()["status"] == "ok"

def test_crud():
    r = c.post("/todos", json={"title":"study"}); assert r.status_code == 200
    tid = r.json()["id"]
    assert c.get(f"/todos/{tid}").status_code == 200
    assert c.delete(f"/todos/{tid}").status_code == 200
