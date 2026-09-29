#!/usr/bin/env python3
"""Reply to get_state with a correlated but invalid RPC response."""
import json
import sys

for line in sys.stdin:
    request = json.loads(line)
    response = {'type': 'response', 'id': request.get('id'), 'command': 'get_state', 'success': True}
    response[sys.argv[1]] = {'command': 'prompt', 'success': False,
                             'id': 'not-the-request-id'}[sys.argv[1]]
    print(json.dumps(response), flush=True)
