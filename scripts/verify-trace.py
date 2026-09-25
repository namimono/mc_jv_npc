#!/usr/bin/env python3
"""Check an actual recorder session. --models additionally checks both providers and decision outcomes."""
import argparse
import collections
import json
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('session', type=Path)
parser.add_argument('--models', action='store_true')
args = parser.parse_args()
folder = args.session if args.session.is_dir() else args.session.parent
rows = [json.loads(line) for line in (folder / 'events.jsonl').read_text().splitlines() if line.strip()]
assert rows, 'empty trace'
assert all(row['schema'] == 1 and row['session'] == rows[0]['session'] for row in rows), 'mixed session/schema'
assert [row['seq'] for row in rows] == list(range(1, len(rows) + 1)), 'missing or unordered events'
starts = {row['span']: row for row in rows if row['phase'] == 'start'}
assert all(row['span'] in starts and (not row['parent'] or row['parent'] in starts) for row in rows), 'broken parent/span links'
assert (folder / 'index.html').exists(), 'missing HTML'
assert all('/*TRACE_DATA*/{}' not in path.read_text() for path in folder.glob('*.html')), 'unrendered HTML'
methods = [row for row in rows if row['kind'] in ('method', 'recovery')]
assert methods and any(row['phase'] in ('result', 'failed') for row in methods), 'no physical method result'
if args.models:
    for provider in ('jev', 'deepseek'):
        requests = [row for row in rows if row['kind'] == provider and row['phase'] == 'request']
        assert requests, f'no {provider} requests'
        for request in requests:
            replies = [row for row in rows if row['span'] == request['span'] and row['phase'] == 'response']
            if not replies:
                assert any(row['span'] == request['span'] and row['phase'] == 'error' for row in rows), f'unanswered request without failure record {request["seq"]}'
                continue  # Transport failure/cancel has no provider output; do not invent one.
            if provider == 'jev' and replies[0]['data']['http_status'] == 200:
                choice = replies[0]['data']['output']['answers']['next_action']
                criteria = request['data']['input']['questions']['next_action']['criteria']
                assert set(choice['probabilities']) == set(criteria), 'incomplete option probabilities'
                assert all(0 <= p <= 1 for p in choice['probabilities'].values()), 'invalid probability'
                assert any(row['span'] == request['span'] and row['phase'] in ('applied', 'rejected', 'discarded', 'error') for row in rows), 'decision missing application outcome'
    for applied in (row for row in rows if row['kind'] == 'jev' and row['phase'] == 'applied' and row['data'].get('outcome') in ('SEND', 'ADOPT')):
        request = next((row for row in rows if row['span'] == applied['span'] and row['phase'] == 'request'), None)
        proposal = request['data']['input'].get('state', {}).get('dialogue', {}).get('proposal', {}) if request else {}
        # A generated nonempty reply adopted/sent by this decision must actually retain this parent.
        # Local /jev do talk also uses NEARBY; it is not a delegated language response.
        if not proposal.get('reply', '').strip():
            continue
        speeches = [row for row in rows if row['kind'] == 'speech' and row['phase'] == 'sent' and row['parent'] == applied['span']]
        assert speeches, f'missing actual speech for adopted dialogue decision {applied["seq"]}'
        assert all(speech['tags'].get('dialogue') == applied['tags'].get('dialogue') for speech in speeches), 'speech dialogue mismatch'
print(json.dumps({'result': 'PASS', 'session': rows[0]['session'], 'events': len(rows), 'spans': len(starts),
                  'kinds': dict(collections.Counter(row['kind'] for row in rows)), 'html': str(folder / 'index.html')}, ensure_ascii=False, indent=2))
