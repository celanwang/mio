"""Human-readable report from the same JSON artifacts used by the gates."""
from .runner import read_json


def _fmt(value):
    if value is None:
        return 'N/A'
    if isinstance(value, float):
        return f'{value:.3f}'
    return str(value)


def write_summary(directory):
    from pathlib import Path
    root = Path(directory)
    report = read_json(root/'report.json')
    injection = read_json(root/'injection-report.json')
    promotion = read_json(root/'promotion.json')
    lines = ['# Mio 离线评测报告', '', f'生成时间：{report["generated_at"]}', '',
             '**这是一份固定剧本设施基线，不是模型可靠性或上线资格结论。shadow 不会因此改为 enforce。**', '',
             '| 任务 | 成功/运行 | pass³（设施） | 模型资格证据 |',
             '| --- | --- | --- | --- |']
    for group in report['tasks']['groups']:
        lines.append(f'| {group["task_id"]} | {group["successes"]}/{group["trials"]} | {_fmt(group["pass_power_3"])} | {"是" if group["qualifies_as_model_evidence"] else "否"} |')
    authority = report.get('authority_continuity') or {}
    friction = report.get('confirm_friction') or {}
    cost = report.get('route_cost') or {}
    parity = report.get('production_agent') or {}
    lift = report.get('memory_lift') or {}
    habit = report.get('habit_active') or {}
    multi = report.get('multi_agent') or {}
    observations = sum(arm['count'] for group in injection['groups'] for arm in group['arms'])
    blocked = [check['name'] for check in promotion['checks'] if not check['passed']]
    lines += ['',
              f'评测目标：{", ".join(report.get("evaluation_targets") or ["未标记"])}。生产等价：`production_parity={parity.get("production_parity")}`，缺失 {parity.get("missing") or []}。',
              f'Authority Continuity Score：{_fmt(authority.get("score"))}；hard_pass={authority.get("hard_pass")}；缺失规则 {authority.get("missing_rules") or []}；未通过 {authority.get("failed_rules") or []}。沙箱规则只承认 sandboxed 观察，旁路和无票据效果不算通过。',
              f'ConfirmFriction：{_fmt(friction.get("prompts_per_success"))}（{friction.get("status")}）；意外确认 {friction.get("unexpected_confirms")}。',
              f'RouteCost：状态 {cost.get("status")}；model_calls={cost.get("model_calls")}；input_tokens={cost.get("input_tokens")}；output_tokens={cost.get("output_tokens")}。离线 token 成本保持 N/A。',
              f'记忆消融资格证据：{"是" if lift.get("qualifies_as_model_evidence") else "否"}。习惯 ACTIVE 攻击成功率：{_fmt(habit.get("attack_success_rate"))}；策略仍被遵守：{habit.get("policy_respected")}。',
              f'多 Agent 效果失败用例：{multi.get("failed")}；缺失 {multi.get("missing_cases") or []}。',
              '',
              f'注入对照：{len(injection["groups"])} 组，{observations} 条观察；固定攻击模型只验证权限链与四因子接线。',
              '',
              f'晋升评审：`{promotion["status"]}`；`approved_for_promotion` 未被本报告改写；生产配置未改动。',
              '',
              '未满足的证据或门禁：',
              ''] + ['- ' + name for name in blocked] + ['',
              '原始数据、指标与诊断见同目录 JSON/JSONL。scripted 观察、空的 human_winner、以及未齐备的 production-agent 校验都不能当作 enforce 证据。',
              '']
    (root/'summary.md').write_text('\n'.join(lines))
