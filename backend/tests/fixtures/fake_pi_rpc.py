#!/usr/bin/env python3
import json
import sys
import time

for raw in sys.stdin:
    try:
        request = json.loads(raw)
    except json.JSONDecodeError:
        continue
    request_id = request.get("id")
    command = request.get("type")
    if command == "delay":
        time.sleep(2)
    if command == "get_available_models":
        data = {"models": [{"provider": "openai", "id": "gpt-test", "name": "Test", "reasoning": True, "input": ["text"], "contextWindow": 100, "maxTokens": 10}]}
        response = {"id": request_id, "type": "response", "command": command, "success": True, "data": data}
    else:
        response = {"id": request_id, "type": "response", "command": command, "success": True, "data": {"ok": True}}
    print(json.dumps(response), flush=True)
