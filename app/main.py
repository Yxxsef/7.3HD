from fastapi import FastAPI, HTTPException
from fastapi.responses import PlainTextResponse
from prometheus_client import Counter, generate_latest, CONTENT_TYPE_LATEST

app = FastAPI(title="Todo API")
hits = Counter("hits_total", "Total hits")
DB, SEQ = {}, 0

@app.get("/health")
def health(): return {"status":"ok"}

@app.post("/todos")
def create(todo: dict):
    global SEQ
    SEQ += 1
    DB[SEQ] = {"id": SEQ, "title": todo.get("title",""), "done": False}
    return DB[SEQ]

@app.get("/todos")        # list
def list_all(): return list(DB.values())

@app.get("/todos/{tid}")  # read
def get_one(tid: int):
    if tid not in DB: raise HTTPException(404, "not found")
    return DB[tid]

@app.delete("/todos/{tid}")
def delete_one(tid: int):
    if tid not in DB: raise HTTPException(404, "not found")
    del DB[tid]; return {"deleted": tid}

@app.get("/metrics", response_class=PlainTextResponse)
def metrics():
    hits.inc()
    return PlainTextResponse(generate_latest(), media_type=CONTENT_TYPE_LATEST)
