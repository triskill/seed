import json
import sys
import os

mode = sys.argv[1] if len(sys.argv) > 1 else 'healthy'
for line in sys.stdin:
    try:
        cmd = json.loads(line)
    except ValueError:
        continue
    kind = cmd.get('type')
    if os.environ.get('RECOVERY_LOG'):
        with open(os.environ['RECOVERY_LOG'], 'a') as log:
            log.write(json.dumps(cmd) + '\n')
    if kind == 'get_state' and mode == 'silent':
        continue
    print(json.dumps({'type': 'response', 'id': cmd.get('id'), 'command': kind,
                      'success': not (kind == 'get_state' and mode == 'bad')}), flush=True)
    if kind == 'prompt' and mode == 'violation':
        print(json.dumps({'type': 'tool_execution_start', 'toolName': 'write'}), flush=True)
    if kind == 'prompt' and mode == 'eof':
        sys.exit(1)
