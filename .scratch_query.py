import sqlite3, json
db = sqlite3.connect('/home/iot/onmom_mergeVer/data/insights/onmom_context.db')
db.row_factory = sqlite3.Row

print('=== context_cards 전체 ===')
for r in db.execute('SELECT card_id, domain, evidence_group, confidence, card_json FROM context_cards ORDER BY generated_at DESC'):
    raw = r['card_json']
    try:
        card = json.loads(raw) if isinstance(raw, str) else raw
        if isinstance(card, str):
            card = json.loads(card)
        obs = card.get('observation', {}) if isinstance(card, dict) else {}
        print(f"  id={r['card_id'][:20]} dom={r['domain']} evidence={r['evidence_group']} conf={r['confidence']}")
        print(f"    code={obs.get('code','?')} summary={str(obs.get('summary_ko','?'))[:80]}")
    except Exception as e:
        print(f"  id={r['card_id'][:20]} dom={r['domain']} PARSE_ERROR: {e}")
        print(f"    raw[:100]={str(raw)[:100]}")

print('\n=== derived_summaries ===')
for r in db.execute('SELECT category, length(summary) as len FROM derived_summaries'):
    print(f"  {r['category']}: {r['len']} chars")

print('\n=== audit_log 최근 5개 ===')
for r in db.execute('SELECT action, reference_id, details_json FROM audit_log ORDER BY created_at DESC LIMIT 5'):
    print(f"  action={r['action']} ref={str(r['reference_id'])[:30]}")

print('\n=== 서비스 실행 위치 vs ~/Jetson ===')
import os
merge_files = set(os.listdir('/home/iot/onmom_mergeVer/derived_insights/'))
jetson_files = set(os.listdir('/home/iot/Jetson/'))
only_merge = merge_files - jetson_files
only_jetson = jetson_files - merge_files
print(f"  onmom_mergeVer에만 있는: {sorted(only_merge)[:10]}")
print(f"  ~/Jetson에만 있는: {sorted(only_jetson)[:10]}")
print(f"  공통: {len(merge_files & jetson_files)}개")

db.close()
