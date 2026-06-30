"""FastAPI orchestrator service.

Task 0.2: the bare-bones service with a `/health` endpoint. Phase 1+ will
add the shell, WebSocket chat, and pi runner on top of this skeleton.
"""
from fastapi import FastAPI

app = FastAPI()


@app.get("/health")
def health():
    return {"status": "ok"}
