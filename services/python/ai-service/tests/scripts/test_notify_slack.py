"""Phase 6 tests — notify_slack helper."""
from __future__ import annotations

import io
import json

from scripts import notify_slack


def test_build_blocks_includes_header_metrics_and_promote_command():
    args = notify_slack._parse_args([
        "--line", "HEALTH", "--candidate", "v3",
        "--auc", "0.842", "--precision", "0.71", "--recall", "0.66",
        "--train-samples", "1247", "--val-samples", "267",
    ])
    blocks = notify_slack._build_blocks(args)

    dumped = json.dumps(blocks)
    assert "HEALTH" in dumped
    assert "v3" in dumped
    assert "AUC" in dumped and "0.842" in dumped
    assert "Precision" in dumped and "0.710" in dumped
    assert "promote_model" in dumped  # promote command surfaced


def test_fmt_handles_none_and_floats():
    assert notify_slack._fmt(None) == "—"
    assert notify_slack._fmt(0.5) == "0.500"


def test_main_prints_payload_when_webhook_missing(capsys, monkeypatch):
    monkeypatch.delenv("SLACK_WEBHOOK_URL", raising=False)
    notify_slack.main([
        "--line", "HEALTH", "--candidate", "v3",
        "--auc", "0.75", "--precision", "0.65", "--recall", "0.55",
        "--train-samples", "100", "--val-samples", "20",
    ])
    output = capsys.readouterr().out
    parsed = json.loads(output)
    assert "blocks" in parsed


def test_post_returns_zero_on_url_error(monkeypatch):
    """Simulated network outage — must not raise."""
    import urllib.error

    def raise_url_error(*args, **kwargs):
        raise urllib.error.URLError("network down")
    monkeypatch.setattr("urllib.request.urlopen", raise_url_error)

    status = notify_slack.post("https://example.com/hook", [{"type": "section"}])
    assert status == 0
