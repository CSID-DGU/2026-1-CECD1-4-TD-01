import urllib.request
import json
import os

token_path = os.path.expanduser('~/.config/onmom/jetson_sync_token')
with open(token_path, 'r') as f:
    token = f.read().strip()

url = 'http://localhost:8765/v1/trigger-alert'
headers = {
    'Authorization': f'Bearer {token}',
    'Content-Type': 'application/json',
    'X-OnMom-Schema': 'trigger-alert-v1'
}
data = {
    'severity': 'CRITICAL',
    'title': '테스트 위험 신호',
    'message': '이것은 테스트 목적으로 발생한 위험 신호입니다.'
}
req = urllib.request.Request(url, data=json.dumps(data).encode('utf-8'), headers=headers)
try:
    with urllib.request.urlopen(req) as response:
        print(response.read().decode('utf-8'))
except Exception as e:
    print(e)
