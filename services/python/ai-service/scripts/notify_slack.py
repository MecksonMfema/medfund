"""Post a training-run summary to the AI-training Slack channel.

Usage:
    uv run python -m scripts.notify_slack \\
        --line HEALTH --candidate v3 \\
        --auc 0.842 --precision 0.71 --recall 0.66 \\
        --train-samples 1247 --val-samples 267

Runs after train_fraud + evaluate_model in the CronJob. Operator reads
the message, decides whether to promote, and clicks Promote v3 on
/tenant/admin/ai-predictions/models (or copy-pastes the promote_model.py
one-liner in the message body).

Best-effort — a Slack outage never fails a training run. Uses only the
standard library so the training image doesn't need slack-sdk.
"""
from __future__ import annotations

import argparse
import json
import logging
import os
import sys
import urllib.error
import urllib.request


logger = logging.getLogger(__name__)


ENV_WEBHOOK = "SLACK_WEBHOOK_URL"


def _build_blocks(args: argparse.Namespace) -> list[dict]:
    """Slack Block Kit payload."""
    header = (
        f":robot_face: Weekly fraud retrain — {args.line} → *{args.candidate}*"
    )
    metrics_lines = [
        f"• AUC:      {_fmt(args.auc)}",
        f"• Precision:{_fmt(args.precision)}",
        f"• Recall:   {_fmt(args.recall)}",
        f"• Train samples: {args.train_samples}",
        f"• Val samples:   {args.val_samples}",
    ]
    promote_hint = (
        "*Promote command* (copy/paste when ready — no auto-promotion):\n"
        "```\n"
        f"uv run python -m scripts.promote_model "
        f"--model-type fraud --line {args.line} --version {args.candidate} "
        "--actor-id <you> --actor-email <you@medfund.io> "
        f"--reason 'weekly cron retrain'\n"
        "```"
    )
    return [
        {"type": "section", "text": {"type": "mrkdwn", "text": header}},
        {"type": "section", "text": {"type": "mrkdwn", "text": "\n".join(metrics_lines)}},
        {"type": "divider"},
        {"type": "section", "text": {"type": "mrkdwn", "text": promote_hint}},
    ]


def _fmt(value: float | None) -> str:
    if value is None:
        return "—"
    return f"{value:.3f}"


def post(webhook_url: str, blocks: list[dict]) -> int:
    """POST the payload; return the HTTP status. Never raises."""
    payload = json.dumps({"blocks": blocks}).encode("utf-8")
    request = urllib.request.Request(
        webhook_url,
        data=payload,
        headers={"Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(request, timeout=10) as response:
            return response.status
    except urllib.error.HTTPError as e:
        logger.warning("Slack post failed with HTTP %s: %s", e.code, e.reason)
        return e.code
    except urllib.error.URLError as e:
        logger.warning("Slack post failed: %s", e.reason)
        return 0


def _parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--line", required=True)
    parser.add_argument("--candidate", required=True)
    parser.add_argument("--auc", type=float, default=None)
    parser.add_argument("--precision", type=float, default=None)
    parser.add_argument("--recall", type=float, default=None)
    parser.add_argument("--train-samples", type=int, default=0)
    parser.add_argument("--val-samples", type=int, default=0)
    parser.add_argument(
        "--webhook",
        default=os.environ.get(ENV_WEBHOOK),
        help=f"Slack webhook URL (default: ${ENV_WEBHOOK})",
    )
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> None:
    args = _parse_args(argv)
    if not args.webhook:
        # Absence of the webhook is not an error — dev environments
        # often run the CronJob locally without wiring Slack. Log the
        # payload so the operator still has visibility.
        logger.info("SLACK_WEBHOOK_URL unset — printing payload instead")
        print(json.dumps({"blocks": _build_blocks(args)}, indent=2))
        return
    status = post(args.webhook, _build_blocks(args))
    if 200 <= status < 300:
        logger.info("Slack post OK (HTTP %s)", status)
    else:
        logger.warning("Slack post returned HTTP %s", status)
        sys.exit(1)


if __name__ == "__main__":
    main()
