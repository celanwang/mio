"""Consume existing events exports. Match only structured call IDs, never guess from tool names."""
from collections import Counter, defaultdict
from datetime import datetime
from zoneinfo import ZoneInfo
import json
from .metrics import ratio
from .effects import divergence_kind


SANDBOX_TYPES = ('sandbox_started', 'sandbox_refused', 'sandbox_unavailable')
SANDBOX_NOTE = 'Diagnostic only. sandbox refuse ≠ policy deny; does not flip enforce.'


def _sandbox_text(value):
    return isinstance(value, str) and value.strip() != ''


def _sandbox_detail(raw):
    try:
        detail = json.loads(raw) if isinstance(raw, str) else raw
    except (ValueError, TypeError):
        return None
    return detail if isinstance(detail, dict) else None


def _sandbox_identity(session, detail):
    """Prefer callId. joinKey is only the identity when callId is missing. Never invent a callId."""
    call_id = detail.get('callId')
    join_key = detail.get('joinKey') if isinstance(detail.get('joinKey'), str) and detail.get('joinKey') != '' else None
    if _sandbox_text(call_id):
        return (session, 'call:' + call_id.strip()), 'call_id', call_id.strip(), join_key
    if join_key:
        return (session, 'jk:' + join_key), 'join_key', None, join_key
    return None, 'unjoined', None, join_key


def _sandbox_section(collected):
    diagnostics = {
        'sandbox_events': 0,
        'sandbox_started': 0,
        'sandbox_refused': 0,
        'sandbox_unavailable': 0,
        'sandbox_legacy_or_unstructured': 0,
        'joined_by_call_id': 0,
        'joined_by_join_key': 0,
        'missing_join_key': 0,
        'process_started_true': 0,
        'refused_with_ticket_id': 0,
        'refused_without_ticket_id': 0,
        'weak_join_used_despite_call_id': 0,
        'process_started_mismatch': 0,
    }
    groups = {}
    order = []
    for session, kind, raw in collected:
        diagnostics['sandbox_events'] += 1
        if kind in diagnostics:
            diagnostics[kind] += 1
        detail = _sandbox_detail(raw)
        if detail is None or detail.get('schemaVersion') != 1:
            diagnostics['sandbox_legacy_or_unstructured'] += 1
            continue
        identity, mode, call_id, join_key = _sandbox_identity(session, detail)
        if join_key is None:
            diagnostics['missing_join_key'] += 1
        if mode == 'call_id':
            diagnostics['joined_by_call_id'] += 1
        elif mode == 'join_key':
            diagnostics['joined_by_join_key'] += 1
        else:
            identity = (session, 'unjoined', diagnostics['sandbox_events'])
        # A row that still has a callId must not have been filed under joinKey.
        if mode != 'call_id' and _sandbox_text(detail.get('callId')):
            diagnostics['weak_join_used_despite_call_id'] += 1
        process_started = detail.get('process_started')
        event_kind = detail.get('event_kind')
        if process_started is True:
            diagnostics['process_started_true'] += 1
        if type(process_started) is not bool or (event_kind in ('refused', 'unavailable') and process_started is True):
            diagnostics['process_started_mismatch'] += 1
        if kind in ('sandbox_refused', 'sandbox_unavailable') or event_kind in ('refused', 'unavailable'):
            ticket = detail.get('ticket_id')
            if _sandbox_text(ticket):
                diagnostics['refused_with_ticket_id'] += 1
            else:
                diagnostics['refused_without_ticket_id'] += 1
        bucket = groups.get(identity)
        if bucket is None:
            bucket = dict(session_id=session, join_mode=mode, call_id=call_id, join_key=join_key,
                          types=[], refuse_codes=[], ticket_id=None, process_started=False)
            groups[identity] = bucket
            order.append(identity)
        elif bucket['join_mode'] != mode:
            diagnostics['weak_join_used_despite_call_id'] += 1
        if mode == 'call_id' and bucket['join_mode'] != 'call_id':
            diagnostics['weak_join_used_despite_call_id'] += 1
        bucket['types'].append(kind)
        code = detail.get('refuse_code')
        if _sandbox_text(code):
            bucket['refuse_codes'].append(code)
        ticket = detail.get('ticket_id')
        if _sandbox_text(ticket):
            bucket['ticket_id'] = ticket.strip()
        if process_started is True:
            bucket['process_started'] = True
    if diagnostics['weak_join_used_despite_call_id']:
        raise ValueError('sandbox join with callId must use callId, not joinKey')
    return dict(diagnostics=diagnostics, rows=[groups[key] for key in order], note=SANDBOX_NOTE)


def shadow_report(rows, day=None):
    counts = Counter()
    policies, results = {}, {}
    approvals = {}
    seen = {}
    daily = defaultdict(Counter)
    sandbox_rows = []
    for row in rows:
        # Accept pg events rows, or exported TraceStore events enriched with sessionId.
        session = row.get('session_id', row.get('sessionId'))
        ts = row.get('ts', row.get('timestamp'))
        if not isinstance(session,str) or not session or type(ts) is not int:
            raise ValueError('shadow events require session_id and integer ts/timestamp milliseconds')
        local_day = datetime.fromtimestamp(ts/1000, ZoneInfo('Asia/Shanghai')).date().isoformat()
        if day and local_day != day:
            continue
        # PG id is the idempotency key; without it, duplicate observations cannot safely be collapsed.
        identity = row.get('id')
        if identity:
            if identity in seen:
                if seen[identity] != row:
                    raise ValueError('duplicate event ID has conflicting content')
                counts['duplicate_events'] += 1
                continue
            seen[identity] = row
        kind = row.get('type')
        daily[local_day]['events'] += 1
        if kind in SANDBOX_TYPES:
            sandbox_rows.append((session, kind, row.get('detail')))
            continue
        if kind not in ('policy_decision','tool_result','confirm'):
            continue
        raw = row.get('detail')
        try:
            detail = json.loads(raw) if isinstance(raw,str) else raw
        except (ValueError, TypeError):
            detail = None
        if not isinstance(detail,dict) or detail.get('schemaVersion') != 1:
            counts['legacy_or_unstructured'] += 1
            continue
        if kind == 'confirm':
            for decision in detail.get('decisions',[]):
                key = (session, decision['toolCallId'])
                if type(decision['approved']) is not bool:
                    raise ValueError('approval must be boolean')
                if key in approvals and approvals[key] != decision['approved']:
                    raise ValueError('conflicting human decisions')
                approvals[key] = decision['approved']
            continue
        key = (session,detail['callId'])
        collection = policies if kind == 'policy_decision' else results
        if key in collection:
            if collection[key] != detail:
                raise ValueError(f'conflicting {kind} for {key}')
            counts['duplicate_snapshots'] += 1
        collection[key] = detail
    comparable=divergences=executed_after_refusal=0
    kinds=Counter()
    pairs=[]
    for key, policy in policies.items():
        effect, state = policy.get('effect'), policy.get('state')
        effect = effect.upper() if isinstance(effect,str) else effect
        state = state.upper() if isinstance(state,str) else state
        comparable_pair = effect in ('ALLOW','ASK','DENY') and state in ('ASKING','ALLOWED','SUBMITTED','FINISHED')
        divergent = None
        if comparable_pair:
            comparable += 1
            divergent = ((effect == 'ALLOW' and state == 'ASKING') or
                         (effect in ('ASK','DENY') and state in ('ALLOWED','SUBMITTED','FINISHED')))
            divergences += divergent
        else:
            counts['uncomparable_policy_snapshots'] += 1
        result = results.get(key)
        executed = bool(result and str(result.get('state')).upper() == 'SUCCESS')
        refusal_violation = executed and approvals.get(key) is False
        executed_after_refusal += refusal_violation
        command = policy.get('command') if isinstance(policy.get('command'), str) else None
        kind = divergence_kind(effect, state, policy.get('tool'), command, approvals.get(key)) if divergent else None
        if kind:
            kinds[kind] += 1
        pairs.append(dict(session_id=key[0],call_id=key[1],tool=policy['tool'],effect=effect,snapshot_state=state,
                          snapshot_divergent=divergent, divergence_kind=kind, result_state=result.get('state') if result else None,
                          human_approved=approvals.get(key),executed_after_refusal=refusal_violation))
    return dict(policy_snapshots=len(policies),comparable_snapshots=comparable, snapshot_divergences=divergences,
                snapshot_divergence_rate=ratio(divergences,comparable), divergence_kinds=dict(kinds),
                executed_after_refusal=executed_after_refusal,
                unmatched_results=len(set(results)-set(policies)),unresolved_calls=len(set(policies)-set(results)),
                diagnostics=dict(counts),daily={d:dict(c) for d,c in sorted(daily.items())},pairs=pairs,
                sandbox=_sandbox_section(sandbox_rows),
                note='Snapshot disagreement is diagnostic, not a false-allow metric. ASK may later execute after human/Jev approval; '
                     'command-sensitive tools can ALLOW despite static ASK. Legacy text is uncomparable. Truncated outputs are never final-state evidence. '
                     'sandbox_* rows are a separate continuity diagnostic and are not policy denies.')


def compare_runs(before, after):
    # Compare only semantically comparable versions. Skill/prompt hashes may differ: those are the changes under test.
    keys=('task_id','sample_id','source','model','route','temperature','dataset_sha256')
    def index(rows):
        result={}
        for row in rows:
            key=tuple(row[k] for k in keys)
            if key in result:
                raise ValueError('duplicate regression key')
            result[key]=row
        return result
    left,right=index(before),index(after)
    changed=[]
    for key in sorted(set(left)&set(right),key=str):
        # Deliberately omit volatile IDs/timestamps/events; assertions consume full mock state and actions.
        fields=[f for f in ('final_state','actions','reply','error') if left[key].get(f) != right[key].get(f)]
        if fields:
            changed.append(dict(key=dict(zip(keys,key)),changed_fields=fields,needs_judge=fields==['reply']))
    return dict(comparable_pairs=len(set(left)&set(right)),changed=changed,
                removed=len(set(left)-set(right)),added=len(set(right)-set(left)),
                note='Different dataset/model/route/temperature are not paired. Only reply-only changes can request a calibrated judge; state/action differences use assertions.')
