import json
import urllib.request
import os

TOKEN_PATH = os.path.expanduser('~/.config/onmom/home_assistant_token')
HA_URL = 'http://127.0.0.1:8123'

with open(TOKEN_PATH, 'r') as f:
    token = f.read().strip()

req = urllib.request.Request(
    f"{HA_URL}/api/states",
    headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"}
)

try:
    with urllib.request.urlopen(req) as response:
        states = json.loads(response.read().decode('utf-8'))
except Exception as e:
    print(f"Error fetching HA states: {e}")
    exit(1)

KINDS = {
    "light": {"kind": "LIGHT", "actions": ["TURN_ON", "TURN_OFF", "TOGGLE"]},
    "switch": {"kind": "SWITCH", "actions": ["TURN_ON", "TURN_OFF", "TOGGLE"]},
    "fan": {"kind": "FAN", "actions": ["TURN_ON", "TURN_OFF", "TOGGLE"]},
    "lock": {"kind": "LOCK", "actions": ["LOCK", "UNLOCK"]},
    "cover": {"kind": "COVER", "actions": ["OPEN", "CLOSE"]}
}

devices = []
for state in states:
    entity_id = state['entity_id']
    domain = entity_id.split('.')[0]
    
    if domain in KINDS:
        friendly_name = state.get('attributes', {}).get('friendly_name', entity_id)
        config = KINDS[domain]
        devices.append({
            "id": entity_id.replace('.', '_'),
            "name": friendly_name,
            "entity_id": entity_id,
            "kind": config['kind'],
            "actions": config['actions']
        })

iot_config = {
    "schema_version": 1,
    "home_assistant_url": HA_URL,
    "token_file": "~/.config/onmom/home_assistant_token",
    "devices": devices
}

config_path = os.path.expanduser('~/onmom_mergeVer/derived_insights/iot_devices.json')
with open(config_path, 'w', encoding='utf-8') as f:
    json.dump(iot_config, f, ensure_ascii=False, indent=2)

print(f"Generated {len(devices)} devices in {config_path}")
